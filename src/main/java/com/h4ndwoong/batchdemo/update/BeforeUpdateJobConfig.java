package com.h4ndwoong.batchdemo.update;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.domain.MemberF;
import com.h4ndwoong.batchdemo.support.DatabaseWorkloadListener;
import com.h4ndwoong.batchdemo.support.GradePolicyLoader;
import com.h4ndwoong.batchdemo.support.MemberRowMapper;
import com.h4ndwoong.batchdemo.support.RunIdOnlyIncrementer;
import com.h4ndwoong.batchdemo.support.TableSeededValidator;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.sql.Types;

/**
 * 6번 문제(대량 UPDATE 쓰기 경로) <b>before</b> 구성. 행마다 UPDATE 한 문장을 보낸다.
 *
 * <p><b>재현하는 증상</b><br>
 * {@code member_f} 100만 행의 등급을 포인트로 다시 매긴다. 리더가 전량을 읽고, 프로세서가 등급을
 * 계산하고, 라이터가 바뀐 행마다 {@code UPDATE ... WHERE id = ?} 를 보낸다. 약 75만 행이 바뀌므로
 * <b>왕복도 75만 번</b>이다.
 *
 * <p><b>이 구성이 "잘못 짠 코드" 처럼 보이지 않는다.</b> 오히려 교과서적이다 —
 * {@code JdbcBatchItemWriter} 를 쓰고, 청크마다 커밋하고, 바뀌지 않는 행은 걸러서 쓰지도 않는다.
 * 라이터 이름에 <b>Batch</b> 가 들어 있으니 묶여서 나가리라 믿게 되는데, 실제로는 행마다 한 번씩
 * 나간다. 2번 문제의 실측에서 이미 드러났던 사실이다.
 * <blockquote>
 * {@code rewriteBatchedStatements=true} 는 <b>INSERT 를 다시 쓸 뿐 UPDATE 를 묶지 않는다.</b>
 * </blockquote>
 * 1번 문제에서 "코드만 바꿔서는 개선되지 않는다" 를 배웠다면, 여기서는 <b>1번에서 켠 그 옵션이
 * UPDATE 에는 듣지 않는다</b>는 것을 본다. 개선의 정체는 옵션 이름이 아니라 <b>어떤 문장을 어떻게
 * 보내는가</b>다.
 *
 * <p><b>부록 A — 연결 설정으로는 살 수 없다</b><br>
 * 1번 문제는 {@code rewriteBatchedStatements=true} 라는 연결 설정 하나로 INSERT 왕복을 1,000분의
 * 1로 줄였다. UPDATE 에도 그런 옵션이 있다 — MariaDB 드라이버의 {@code useBulkStmts=true} 는
 * 배치를 전용 프로토콜로 <b>패킷 하나에 묶어</b> 보낸다. before 를 그대로 두고 연결 설정만 바꿔
 * 한 번 더 측정한다.
 * <pre>{@code
 * ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.profiles.active=before \
 *   --spring.batch.job.name=updateJob \
 *   --spring.datasource.url=jdbc:mariadb://localhost:3307/batch_demo?useBulkStmts=true'
 * }</pre>
 * <b>그런데 {@code COM_UPDATE} 는 꿈쩍도 하지 않는다.</b> 2만 행을 묶어 보낸 프로브 측정에서
 * 문장 수는 그대로 20,000 이었고 {@code Com_stmt_execute} 가 20,000 늘었을 뿐이다. 시간은
 * 3.75초에서 2.49초로 줄었다 — <b>드라이버가 묶은 것은 패킷이고, 서버는 여전히 행 수만큼의 문장을
 * 실행한다.</b> 1번에서 옵션 하나로 해결됐던 문제가 여기서는 옵션으로 살 수 없고, 그것이 6번이
 * 별도의 문제인 이유다. 개선은 문장 자체를 줄여야 온다 ({@code AfterUpdateJobConfig}).
 *
 * <p>{@code BulkStatementsUpdateTest} 가 이 사실을 고정한다.
 *
 * <p><b>실행</b>
 * <pre>{@code
 * # 먼저 시딩 (한 번만, 100만 건):
 * ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.batch.job.name=seedJob target=member_f'
 *
 * ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.profiles.active=before --spring.batch.job.name=updateJob'
 * }</pre>
 *
 * <p><b>리셋은 재시딩이다.</b> 이 배치는 {@code grade} 를 파괴하고, 시드의 등급은 순번 해시라
 * SQL 로 되돌릴 수 없다 (5번의 포인트와 같은 사정이다). <b>before 를 돌린 뒤 after 를 측정하기
 * 전에 반드시 다시 시딩한다.</b>
 * <pre>{@code
 * TRUNCATE TABLE member_f;
 * ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.batch.job.name=seedJob target=member_f'
 * }</pre>
 */
@Configuration
@Profile("before")
public class BeforeUpdateJobConfig {

    /**
     * 읽기 SQL. 전량을 {@code id} 순으로 읽는다.
     *
     * <p>대상을 좁히지 않는다. 6번은 <b>전량 재계산</b>이고, 대상 선별은 비교 축이 아니다
     * (after 의 슬라이스도 같은 키 공간을 전부 덮는다).
     */
    private static final String SELECT_SQL = """
            SELECT id, email, name, grade, point, status, referrer_id,
                   processed, idempotency_key, created_at, updated_at
            FROM member_f
            ORDER BY id""";

    /**
     * 행 하나를 갱신하는 UPDATE. <b>이 문장이 75만 번 나간다.</b>
     *
     * <p>after 의 한 문장과 나란히 두면 6번의 전부가 보인다. 여기에는 {@code WHERE id = :id} 가 있고,
     * 저쪽에는 {@code WHERE id BETWEEN :fromId AND :toId AND grade <> CASE ...} 가 있다. 앞의 것은
     * <b>어느 행인지를 애플리케이션이 알고 있다</b>는 뜻이고, 뒤의 것은 <b>어떤 행인지를 서버가
     * 찾는다</b>는 뜻이다.
     */
    private static final String UPDATE_SQL = """
            UPDATE member_f
            SET grade = :grade, updated_at = :updatedAt
            WHERE id = :id""";

    /**
     * 6번 문제 Job. Step 은 {@code updateStep} 하나뿐이다.
     *
     * <p>리스너 등록 순서의 의미는 1~5번과 같다. {@link DatabaseWorkloadListener} 를 맨 앞에 두어
     * 측정 범위가 Job 전체를 덮게 하고, 스키마를 건드리는 리스너는 시드 확인 뒤에 둔다.
     *
     * @param jobRepository    Job 저장소
     * @param updateStep       등급 재계산 Step
     * @param workloadListener DB 작업량을 기록하는 리스너
     * @param seededValidator  {@code member_f} 에 데이터가 있는지 확인하는 리스너
     * @param indexListener    {@code (grade, point)} 인덱스 상태를 맞추는 리스너
     * @return {@code updateJob}
     */
    @Bean
    public Job updateJob(JobRepository jobRepository,
                         Step updateStep,
                         DatabaseWorkloadListener workloadListener,
                         @Qualifier("memberFSeededValidator") TableSeededValidator seededValidator,
                         GradePointIndexListener indexListener) {
        return new JobBuilder("updateJob", jobRepository)
                .incrementer(new RunIdOnlyIncrementer())
                .listener(workloadListener)
                .listener(seededValidator)
                .listener(indexListener)
                .start(updateStep)
                .build();
    }

    /**
     * 등급 재계산 Step. after 와 <b>이름만</b> 같다.
     *
     * <p>아이템 타입부터 다르다 (회원 대 {@code id} 구간). 그래서 6번은 {@code READ_COUNT} 로
     * 두 프로파일을 비교할 수 없는 첫 문제이며, 비교는 왕복 횟수와 갱신 행 수로 한다. Step 이름을
     * 맞추는 것은 메타데이터 조회를 한 축에서 하기 위해서다.
     *
     * <p>여기서 {@code READ = WRITE + FILTER} 가 성립한다 — 등급이 이미 옳던 약 25만 행은 프로세서가
     * {@code null} 로 걸러 쓰지 않는다. after 의 {@code AND grade <> CASE ...} 가 그 필터에 대응한다.
     *
     * @param jobRepository        Job 저장소
     * @param transactionManager   트랜잭션 관리자
     * @param memberFItemReader    커서 리더
     * @param memberFItemProcessor 등급 재계산 프로세서
     * @param memberFItemWriter    행 단위 UPDATE 라이터
     * @param reporter             등급 분포 지문 리포터
     * @return {@code updateStep}
     */
    @Bean
    public Step updateStep(JobRepository jobRepository,
                           PlatformTransactionManager transactionManager,
                           JdbcCursorItemReader<MemberBase> memberFItemReader,
                           GradeAssigningItemProcessor memberFItemProcessor,
                           JdbcBatchItemWriter<MemberBase> memberFItemWriter,
                           GradeRecalcReporter reporter) {
        return new StepBuilder("updateStep", jobRepository)
                .<MemberBase, MemberBase>chunk(UpdateJobCommonConfig.CHUNK_SIZE, transactionManager)
                .reader(memberFItemReader)
                .processor(memberFItemProcessor)
                .writer(memberFItemWriter)
                .listener(reporter)
                .build();
    }

    /**
     * {@code member_f} 전량을 읽는 커서 리더.
     *
     * <p>커서인 이유는 3·4·5번과 같다. 페이징은 3번의 주제이고, 여기서 섞으면 읽기 방식이 비교 축에
     * 끼어든다. {@code verifyCursorPosition = false} 인 이유는 2번 문제의 리더에 적었다.
     *
     * @param dataSource 데이터 소스
     * @return 리더
     */
    @Bean
    public JdbcCursorItemReader<MemberBase> memberFItemReader(DataSource dataSource) {
        return new JdbcCursorItemReaderBuilder<MemberBase>()
                .name("memberFItemReader")
                .dataSource(dataSource)
                .sql(SELECT_SQL)
                .rowMapper(new MemberRowMapper(MemberF::new))
                .fetchSize(UpdateJobCommonConfig.CHUNK_SIZE)
                .verifyCursorPosition(false)
                .build();
    }

    /**
     * 등급을 다시 매기는 프로세서.
     *
     * <p><b>{@code @StepScope} 가 "Step 시작 시 정책 1회 로딩" 을 구현한다</b> (4번에서 확립한 형태).
     * 정책은 {@code member_f} 의 포인트 분포에서 나오며, after 도 같은 로더로 같은 값을 얻는다.
     *
     * @param gradePolicyLoader 정책 로더
     * @return 프로세서
     */
    @Bean
    @StepScope
    public GradeAssigningItemProcessor memberFItemProcessor(
            @Qualifier("memberFGradePolicyLoader") GradePolicyLoader gradePolicyLoader) {
        return new GradeAssigningItemProcessor(gradePolicyLoader.load(), UpdateJobCommonConfig.CLOCK);
    }

    /**
     * 행마다 UPDATE 를 보내는 라이터.
     *
     * <p>이름과 달리 문장을 묶어 주지 않는다 — 드라이버가 UPDATE 를 다시 쓰지 못하기 때문이다.
     * 그 사실이 6번 before 의 증상 전체이고, {@code AfterUpdateJobConfig} 가 그것을 문장 수준에서
     * 해결한다.
     *
     * @param dataSource 데이터 소스
     * @return 라이터
     */
    @Bean
    public JdbcBatchItemWriter<MemberBase> memberFItemWriter(DataSource dataSource) {
        return new JdbcBatchItemWriterBuilder<MemberBase>()
                .dataSource(dataSource)
                .sql(UPDATE_SQL)
                .itemSqlParameterSourceProvider(BeforeUpdateJobConfig::toParameters)
                .build();
    }

    /**
     * 회원을 UPDATE 파라미터로 변환한다.
     *
     * @param member 가공된 회원
     * @return 이름 있는 파라미터 소스
     */
    private static SqlParameterSource toParameters(MemberBase member) {
        return new MapSqlParameterSource()
                .addValue("grade", member.getGrade().name())
                .addValue("updatedAt", member.getUpdatedAt(), Types.TIMESTAMP)
                .addValue("id", member.getId());
    }
}
