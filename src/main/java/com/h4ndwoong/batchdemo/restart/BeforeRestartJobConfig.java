package com.h4ndwoong.batchdemo.restart;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.support.DatabaseWorkloadListener;
import com.h4ndwoong.batchdemo.support.RunIdOnlyIncrementer;
import com.h4ndwoong.batchdemo.support.TableSeededValidator;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * 5번 문제(재시작 멱등성) <b>before</b> 구성. 처리 여부를 기록하지 않고 조건만으로 대상을 고른다.
 *
 * <p><b>재현하는 증상</b><br>
 * {@code member_e} 의 활성 회원 포인트를 {@value RestartJobCommonConfig#EXPIRE_AMOUNT} 씩 소멸시킨다.
 * 배치가 중간에 죽은 뒤 다시 돌 때 무슨 일이 일어나는가는 <b>어떻게 다시 도느냐</b>에 달려 있다.
 * <pre>
 *   실행 1  failAfterCount=150000              → FAILED. 15만 건이 커밋된 상태
 *   실행 2  run.id 를 그대로 다시 준다 (재시작) → COMPLETED. <b>정확하다</b>
 *   실행 3  그냥 한 번 더 (새 인스턴스 재실행)  → COMPLETED. <b>전량이 또 차감된다</b>
 * </pre>
 *
 * <p><b>실행 2에서 before 가 살아남는다는 점이 이 구성의 핵심이다.</b> 커서 리더의
 * {@code saveState} 기본값이 저장해 둔 {@code read.count} 만큼 앞을 건너뛰기 때문이다. 그런데 그
 * 보호에는 코드 어디에도 적혀 있지 않은 전제가 둘 붙어 있다.
 * <ol>
 *   <li><b>같은 JobInstance 안에서만</b> 유효하다. 새 인스턴스에는 물려줄 실행 컨텍스트가 없다.</li>
 *   <li><b>결과셋이 변하지 않을 때만</b> 옳다. 여기서는 조회 조건({@code status})이 배치가 쓰는
 *       값({@code point})과 무관해서 성립한다. 조건이 자기 쓰기에 영향받는 순간 — 예를 들어
 *       {@code point >= 1000} 으로 골랐다면 — 저장된 행 수는 <b>다른 결과셋의 인덱스</b>가 되어
 *       재시작에서마저 행을 건너뛰거나 두 번 처리한다.</li>
 * </ol>
 * 즉 before 의 무사함은 설계가 아니라 <b>우연</b>이고, 그 우연은 실행 3에서 끝난다.
 *
 * <p><b>이 구성이 "잘못 짠 코드" 처럼 보이지 않는다.</b> 리더는 대상을 정확히 고르고, 프로세서는
 * 정확히 계산하며, 라이터는 정확한 행을 갱신한다. 어느 문장도 틀리지 않았다. 빠진 것은
 * <b>"이미 했다" 를 적어 두는 일</b> 하나뿐이고, 그것이 없으면 배치의 결과가 <b>실행 횟수의 함수</b>가
 * 된다.
 *
 * <p><b>실행</b>
 * <pre>{@code
 * # 먼저 시딩 (한 번만, 30만 건):
 * ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.batch.job.name=seedJob target=member_e'
 *
 * # 실행 1 — 15만 건을 커밋한 뒤 실패한다
 * ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.profiles.active=before --spring.batch.job.name=restartJob' failAfterCount=150000,java.lang.Long,false
 *
 * # 실행 2 — 재시작. 실패한 실행의 run.id 를 그대로 준다 (BATCH_JOB_EXECUTION_PARAMS 에서 확인)
 * ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.profiles.active=before --spring.batch.job.name=restartJob' run.id=1
 *
 * # 실행 3 — 재실행. run.id 가 증가해 새 JobInstance 가 된다
 * ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.profiles.active=before --spring.batch.job.name=restartJob'
 * }</pre>
 *
 * <p><b>리셋은 재시딩이다.</b> 3·4번은 읽기 전용이라 리셋이 필요 없었고, 2번은
 * {@code processed = 0} 만 되돌리면 됐다. 5번은 <b>값을 파괴</b>하므로 원래 포인트를 되살릴 방법이
 * {@code TRUNCATE} + 재시딩밖에 없다. <b>before 를 돌린 뒤 after 를 측정하기 전에 반드시 다시
 * 시딩한다.</b>
 * <pre>{@code
 * TRUNCATE TABLE member_e;
 * ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.batch.job.name=seedJob target=member_e'
 * }</pre>
 *
 * <p><b>측정</b>
 * <pre>{@code
 * SELECT s.STEP_NAME, s.STATUS, s.READ_COUNT, s.WRITE_COUNT, s.COMMIT_COUNT
 * FROM BATCH_STEP_EXECUTION s ORDER BY s.STEP_EXECUTION_ID DESC;
 *
 * SELECT COUNT(*)                                        AS row_count,
 *        SUM(point)                                      AS point_sum,
 *        SUM(point < 0)                                  AS negative_rows,
 *        SUM(processed = 1)                              AS processed_rows
 * FROM member_e;   -- before 의 processed_rows 는 언제나 0 이다
 * }</pre>
 */
@Configuration
@Profile("before")
public class BeforeRestartJobConfig {

    /**
     * 대상 선별 조건. <b>배치가 쓰는 값({@code point})과 무관한 조건</b>이다.
     *
     * <p>일부러 이렇게 골랐다. 조건이 자기 쓰기에 영향받아 틀리는 것은 다른 이야기이고, 5번이 묻는
     * 것은 <b>조건이 멀쩡해도 멱등하지 않다</b>는 것이기 때문이다.
     */
    private static final String WHERE = "status = 'ACTIVE'";

    /**
     * 가공 결과를 반영하는 UPDATE. <b>포인트와 수정 시각만 건드린다.</b>
     *
     * <p>after 의 같은 문장과 나란히 두면 5번 문제의 전부가 보인다. 여기에 없는 것은
     * {@code processed}, {@code idempotency_key}, 그리고 {@code AND processed = 0} 세 조각이다.
     */
    private static final String UPDATE_SQL = """
            UPDATE member_e
            SET point = :point, updated_at = :updatedAt
            WHERE id = :id""";

    /**
     * 5번 문제 Job. Step 은 {@code restartStep} 하나뿐이다.
     *
     * <p>리스너 등록 순서의 의미는 1~4번과 같다. {@link DatabaseWorkloadListener} 를 맨 앞에 두어
     * 측정 범위가 Job 전체를 덮게 하고, 스키마를 건드리는 리스너는 시드 확인 뒤에 둔다.
     *
     * @param jobRepository    Job 저장소
     * @param restartStep      소멸 Step
     * @param workloadListener DB 작업량을 기록하는 리스너
     * @param seededValidator  {@code member_e} 에 데이터가 있는지 확인하는 리스너
     * @param indexListener    멱등키 UNIQUE 제약을 제거하는 리스너
     * @return {@code restartJob}
     */
    @Bean
    public Job restartJob(JobRepository jobRepository,
                          Step restartStep,
                          DatabaseWorkloadListener workloadListener,
                          @Qualifier("memberESeededValidator") TableSeededValidator seededValidator,
                          IdempotencyIndexDroppingListener indexListener) {
        return new JobBuilder("restartJob", jobRepository)
                .incrementer(new RunIdOnlyIncrementer())
                .listener(workloadListener)
                .listener(seededValidator)
                .listener(indexListener)
                .start(restartStep)
                .build();
    }

    /**
     * 소멸 Step. after 와 이름·청크 크기·프로세서 계산·장애 주입·측정 장치가 모두 같다.
     *
     * <p><b>{@code faultTolerant()} 가 없다.</b> 5번의 실패는 견뎌서는 안 되는 실패다 — 스킵이나
     * 재시도로 회복되면 "실패한 뒤 다시 실행" 이라는 상황 자체가 만들어지지 않는다. 오류를 어떻게
     * 견딜 것인가는 2번 문제의 주제다.
     *
     * @param jobRepository        Job 저장소
     * @param transactionManager   트랜잭션 관리자
     * @param memberEItemReader    커서 리더
     * @param pointExpiryItemProcessor 포인트를 소멸시키는 프로세서. <b>after 와 같은 빈</b>이다
     * @param memberEItemWriter    장애 주입기로 감싼 UPDATE 라이터
     * @param balanceReporter      잔액 지문 리포터
     * @return {@code restartStep}
     */
    @Bean
    public Step restartStep(JobRepository jobRepository,
                            PlatformTransactionManager transactionManager,
                            JdbcCursorItemReader<MemberBase> memberEItemReader,
                            PointExpiryItemProcessor pointExpiryItemProcessor,
                            FailAfterCountItemWriter memberEItemWriter,
                            PointBalanceReporter balanceReporter) {
        return new StepBuilder("restartStep", jobRepository)
                .<MemberBase, MemberBase>chunk(RestartJobCommonConfig.CHUNK_SIZE, transactionManager)
                .reader(memberEItemReader)
                .processor(pointExpiryItemProcessor)
                .writer(memberEItemWriter)
                .listener(balanceReporter)
                .build();
    }

    /**
     * 활성 회원을 읽는 커서 리더. <b>{@code saveState} 를 기본값(켜짐)으로 둔다.</b>
     *
     * <p>before 를 불리하게 조작하지 않기 위해서다. 아무 설정도 건드리지 않은 이 리더가 실행 2의
     * 재시작에서는 정확히 이어서 처리하고, 실행 3의 재실행에서 무너진다.
     *
     * @param dataSource 데이터 소스
     * @return 리더
     */
    @Bean
    public JdbcCursorItemReader<MemberBase> memberEItemReader(DataSource dataSource) {
        return RestartJobCommonConfig.newReader(dataSource, WHERE, true);
    }

    /**
     * 실제 UPDATE 를 보내는 라이터. {@link FailAfterCountItemWriter} 가 이것을 감싼다.
     *
     * @param dataSource 데이터 소스
     * @return 라이터
     */
    @Bean
    public JdbcBatchItemWriter<MemberBase> memberEUpdateWriter(DataSource dataSource) {
        return new JdbcBatchItemWriterBuilder<MemberBase>()
                .dataSource(dataSource)
                .sql(UPDATE_SQL)
                .itemSqlParameterSourceProvider(RestartJobCommonConfig::toParameters)
                .build();
    }

    /**
     * 멱등키 UNIQUE 제약을 제거하는 리스너. <b>before 에만 있다.</b>
     *
     * @param index DDL 실행기
     * @return 리스너
     */
    @Bean
    public IdempotencyIndexDroppingListener idempotencyIndexDroppingListener(MemberEIdempotencyIndex index) {
        return new IdempotencyIndexDroppingListener(index);
    }
}
