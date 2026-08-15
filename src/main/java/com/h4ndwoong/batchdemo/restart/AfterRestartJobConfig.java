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
import java.time.Clock;

/**
 * 5번 문제(재시작 멱등성) <b>after</b> 구성. 처리했다는 사실을 데이터에 남긴다.
 *
 * <p><b>before 와 다른 것은 세 조각뿐이다.</b> Job 이름, Step 이름, 청크 크기, 소멸 계산, 장애 주입,
 * 측정 장치가 모두 같다.
 * <pre>
 *   리더      WHERE status = 'ACTIVE'      →  WHERE <b>processed = 0 AND</b> status = 'ACTIVE'
 *             saveState = true (기본)       →  saveState = <b>false</b>
 *   프로세서   (소멸 계산만)                →  소멸 계산 + <b>처리 표시</b>
 *   라이터     SET point, updated_at        →  SET point, <b>processed = 1, idempotency_key</b>, updated_at
 *             WHERE id = ?                  →  WHERE id = ? <b>AND processed = 0</b>
 * </pre>
 *
 * <p><b>표시가 차감과 같은 UPDATE 안에 있다는 점이 전부다.</b> 문장을 나누면 "차감은 됐는데 표시는
 * 안 된" 틈이 생기고, 그 틈에서 이중 차감이 그대로 살아난다. 같은 문장이므로 <b>왕복도 늘지
 * 않는다</b> — {@code COM_UPDATE} 가 before 와 같다. 2번의 격리가 스킵 500건에 대해 INSERT 500회를
 * 더 냈던 것과 달리, 5번의 멱등은 왕복 비용이 0이다.
 *
 * <p><b>공짜는 아니다. 청구서는 인덱스로 온다.</b> 이 UPDATE 는 {@code idempotency_key} 를 쓰므로
 * 행마다 {@code uk_member_e_idem} 을 갱신한다. 1번 문제에서 유니크 인덱스가 가장 비싼 인덱스였음을
 * 생각하면 이 대가는 실재한다 — 다만 <b>정당한 지출</b>이다.
 *
 * <p><b>기대되는 결과</b>
 * <pre>
 *   실행 1 (failAfterCount=150000)  FAILED,   15만 건 커밋
 *   실행 2 (재시작)                 COMPLETED, 남은 것만 처리          ← before 도 여기까지는 맞다
 *   실행 3 (새 인스턴스 재실행)     COMPLETED, <b>READ_COUNT = 0</b>          ← 여기서 갈린다
 *
 *   실행 2 후 잔액 체크섬 == 실행 3 후 잔액 체크섬   <b>완전히 같다</b>
 *   negativeRows = 0                                 이중 차감의 흔적이 없다
 *   processedRows = 대상 행 수
 * </pre>
 *
 * <p><b>"0건 처리 후 {@code COMPLETED}" 가 정답인 유일한 문제다.</b> 2·3·4번에서 그 결과는 가장
 * 위험한 신호였다 ({@code TableSeededValidator} 가 막으려던 것이 정확히 그것이다). 여기서는 반대로,
 * <b>읽을 것이 없다는 사실 자체가 개선의 증거</b>다. 같은 모양의 결과가 문제에 따라 정반대의 뜻을
 * 가진다.
 *
 * <p><b>실행</b>
 * <pre>{@code
 * # before 를 돌린 뒤라면 반드시 재시딩한다 (before 가 포인트 값을 파괴했다):
 * #   TRUNCATE TABLE member_e;
 * #   ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.batch.job.name=seedJob target=member_e'
 *
 * ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.profiles.active=after --spring.batch.job.name=restartJob failAfterCount=150000,java.lang.Long,false'
 * ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.profiles.active=after --spring.batch.job.name=restartJob run.id=1'
 * ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.profiles.active=after --spring.batch.job.name=restartJob'
 * }</pre>
 *
 * @see MemberEIdempotencyIndex UK 가 실제로 막는 것과 막지 못하는 것
 */
@Configuration
@Profile("after")
public class AfterRestartJobConfig {

    /**
     * 대상 선별 조건. <b>{@code processed = 0} 이 붙은 것이 before 와의 차이다.</b>
     *
     * <p>이 한 조각이 "재실행" 을 "할 일이 없는 실행" 으로 바꾼다. 흔적을 남기는 쓰기와 그 흔적을
     * 보는 읽기는 <b>함께여야 의미가 있다</b> — 한쪽만 있으면 아무것도 걸러지지 않는다.
     */
    private static final String WHERE = "processed = 0 AND status = 'ACTIVE'";

    /**
     * 가공 결과를 반영하는 UPDATE. <b>차감과 처리 표시가 한 문장, 한 트랜잭션이다.</b>
     *
     * <p>{@code AND processed = 0} 은 두 번째 방어선이다. 어떤 이유로든 이미 처리된 행이 여기까지
     * 왔다면 이 UPDATE 는 0행을 갱신하고, {@code JdbcBatchItemWriter} 의 기본 설정
     * ({@code assertUpdates = true})이 그것을 <b>조용히 넘기지 않고 실패시킨다.</b> 멱등성은
     * "두 번 해도 괜찮다" 가 아니라 <b>"두 번째는 일어나지 않는다"</b> 로 만드는 편이 안전하다.
     */
    private static final String UPDATE_SQL = """
            UPDATE member_e
            SET point = :point,
                processed = 1,
                idempotency_key = :idempotencyKey,
                updated_at = :updatedAt
            WHERE id = :id AND processed = 0""";

    /** {@code updated_at} 의 출처. 공통 구성의 프로세서와 같은 시계를 쓴다. */
    private static final Clock CLOCK = Clock.systemDefaultZone();

    /**
     * 5번 문제 Job. before 와 이름이 같고 리스너 등록 순서의 의미도 같다.
     *
     * @param jobRepository    Job 저장소
     * @param restartStep      소멸 Step
     * @param workloadListener DB 작업량을 기록하는 리스너
     * @param seededValidator  {@code member_e} 에 데이터가 있는지 확인하는 리스너
     * @param indexListener    멱등키 UNIQUE 제약을 만드는 리스너
     * @return {@code restartJob}
     */
    @Bean
    public Job restartJob(JobRepository jobRepository,
                          Step restartStep,
                          DatabaseWorkloadListener workloadListener,
                          @Qualifier("memberESeededValidator") TableSeededValidator seededValidator,
                          IdempotencyIndexCreatingListener indexListener) {
        return new JobBuilder("restartJob", jobRepository)
                .incrementer(new RunIdOnlyIncrementer())
                .listener(workloadListener)
                .listener(seededValidator)
                .listener(indexListener)
                .start(restartStep)
                .build();
    }

    /**
     * 소멸 Step. before 와 이름이 같아야 같은 축에서 비교할 수 있다.
     *
     * <p>before 와 비교하면 프로세서 자리에 <b>데코레이터가 하나 끼어 있다</b>. 그 안쪽은 before 가
     * 쓰는 것과 같은 빈이다.
     *
     * @param jobRepository        Job 저장소
     * @param transactionManager   트랜잭션 관리자
     * @param memberEItemReader    커서 리더
     * @param memberEItemProcessor 소멸 계산을 감싸 처리 표시를 세우는 프로세서
     * @param memberEItemWriter    장애 주입기로 감싼 UPDATE 라이터
     * @param balanceReporter      잔액 지문 리포터
     * @return {@code restartStep}
     */
    @Bean
    public Step restartStep(JobRepository jobRepository,
                            PlatformTransactionManager transactionManager,
                            JdbcCursorItemReader<MemberBase> memberEItemReader,
                            ProcessMarkingItemProcessor memberEItemProcessor,
                            FailAfterCountItemWriter memberEItemWriter,
                            PointBalanceReporter balanceReporter) {
        return new StepBuilder("restartStep", jobRepository)
                .<MemberBase, MemberBase>chunk(RestartJobCommonConfig.CHUNK_SIZE, transactionManager)
                .reader(memberEItemReader)
                .processor(memberEItemProcessor)
                .writer(memberEItemWriter)
                .listener(balanceReporter)
                .build();
    }

    /**
     * 아직 처리하지 않은 활성 회원을 읽는 커서 리더. <b>{@code saveState} 를 꺼야 한다.</b>
     *
     * <p>켜 두면 재시작에서 망가진다. 저장된 {@code read.count} 는 <em>그때의</em> 결과셋에서의
     * 위치인데, {@code processed = 0} 때문에 다음 실행의 결과셋은 그만큼 짧아져 있다. 15만을
     * 건너뛰라는 지시가 13만 5천 건짜리 결과셋에 적용되면 <b>남은 전부를 건너뛰고 성공으로 끝난다.</b>
     *
     * <p>이것이 5번의 가장 미묘한 지점이다 — <b>프레임워크의 기억과 데이터의 기억은 함께 쓸 수
     * 없다.</b> 둘 다 "어디까지 했는가" 를 답하는데 근거가 달라서, 겹쳐 두면 서로를 무효화한다.
     * 하나를 골라야 하고, 새 JobInstance 에서도 살아남는 쪽은 데이터다.
     *
     * @param dataSource 데이터 소스
     * @return 리더
     */
    @Bean
    public JdbcCursorItemReader<MemberBase> memberEItemReader(DataSource dataSource) {
        return RestartJobCommonConfig.newReader(dataSource, WHERE, false);
    }

    /**
     * 소멸 계산을 감싸 처리 표시를 세우는 프로세서. <b>after 에만 있다.</b>
     *
     * @param pointExpiryItemProcessor before 와 공유하는 소멸 계산 프로세서
     * @return 프로세서
     */
    @Bean
    public ProcessMarkingItemProcessor memberEItemProcessor(PointExpiryItemProcessor pointExpiryItemProcessor) {
        return new ProcessMarkingItemProcessor(pointExpiryItemProcessor, CLOCK);
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
     * 멱등키 UNIQUE 제약을 만드는 리스너. <b>after 에만 있다.</b>
     *
     * @param index DDL 실행기
     * @return 리스너
     */
    @Bean
    public IdempotencyIndexCreatingListener idempotencyIndexCreatingListener(MemberEIdempotencyIndex index) {
        return new IdempotencyIndexCreatingListener(index);
    }
}
