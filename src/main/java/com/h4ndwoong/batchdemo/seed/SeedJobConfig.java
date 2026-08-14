package com.h4ndwoong.batchdemo.seed;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.sql.Types;

/**
 * 실습용 테스트 데이터를 적재하는 {@code seedJob} 구성.
 *
 * <p><b>대상 테이블 하나당 한 번 실행한다.</b> 6개 테이블을 한 Job 의 6개 Step 으로 묶지 않은 이유는
 * "문제 1개 = Job 1개" 규칙의 취지가 한 실습이 다른 실습을 오염시키지 않게 하는 것이기 때문이다.
 * 대상을 Job 파라미터로 받으면 특정 테이블만 다시 시딩할 수 있고, 200만 건 시딩이 실패해도
 * 다른 테이블의 데이터는 영향을 받지 않는다.
 *
 * <p><b>실행</b>
 * <pre>{@code
 * # member_c 를 기본 건수(200만)로 시딩
 * ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.batch.job.name=seedJob target=member_c'
 *
 * # 건수와 청크 크기를 지정
 * ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.batch.job.name=seedJob target=member_b count=100000 chunkSize=5000'
 * }</pre>
 *
 * <p>Spring Boot 설정은 {@code --} 를 붙인 옵션 인자로, Job 파라미터는 {@code --} 없는 인자로 넘긴다.
 * {@code application.properties} 의 {@code spring.batch.job.enabled=false} 때문에 기본 상태에서는
 * 어떤 Job 도 실행되지 않으므로, 실행할 때마다 {@code --spring.batch.job.enabled=true} 를 함께 넘긴다.
 *
 * <p><b>Job 파라미터</b>
 * <ul>
 *   <li>{@code target} (필수) — 대상 테이블. {@link SeedTarget} 에 정의된 값만 허용한다</li>
 *   <li>{@code count} — 생성 건수. 기본값은 {@link SeedTarget#defaultCount()}</li>
 *   <li>{@code chunkSize} — 커밋 단위. 기본값 {@value #DEFAULT_CHUNK_SIZE}</li>
 *   <li>{@code seed} — 난수 시드. 기본값 {@link MemberSeedGenerator#DEFAULT_SEED}</li>
 * </ul>
 *
 * <p>적재되는 데이터는 {@code (target, count, seed)} 의 순수 함수다. {@code id} 까지 순번으로
 * 정해지므로 같은 파라미터로 시딩하면 언제나 같은 테이블이 된다. before/after 가 같은 입력을
 * 받는다는 보장이 여기서 나온다.
 */
@Configuration
public class SeedJobConfig {

    /** 기본 커밋 단위. 시딩은 측정 대상이 아니라 준비 작업이므로 처음부터 크게 잡는다. */
    public static final int DEFAULT_CHUNK_SIZE = 5_000;

    private static final String INSERT_SQL_TEMPLATE = """
            INSERT INTO %s (id, email, name, grade, point, status, referrer_id,
                            processed, idempotency_key, created_at, updated_at)
            VALUES (:id, :email, :name, :grade, :point, :status, :referrerId,
                    :processed, :idempotencyKey, :createdAt, :updatedAt)""";

    /**
     * 시딩 Job.
     *
     * <p>{@link SeedRunIdIncrementer} 를 붙여 같은 {@code target} 으로 다시 실행할 수 있게 한다.
     * 이것만 두면 중복 시딩 사고가 나므로 {@link TargetTableEmptyValidator} 가 짝을 이룬다.
     * Spring Batch 가 기본 제공하는 {@code RunIdIncrementer} 를 쓰지 않는 이유는
     * {@link SeedRunIdIncrementer} 에 적었다.
     *
     * @param jobRepository Job 저장소
     * @param seedStep      시딩 Step
     * @param validator     대상 테이블이 비어 있는지 확인하는 리스너
     * @return {@code seedJob}
     */
    @Bean
    public Job seedJob(JobRepository jobRepository, Step seedStep, TargetTableEmptyValidator validator) {
        return new JobBuilder("seedJob", jobRepository)
                .incrementer(new SeedRunIdIncrementer())
                .listener(validator)
                .start(seedStep)
                .build();
    }

    /**
     * 시딩 Step. {@code chunkSize} 파라미터를 읽으려면 Job 파라미터 바인딩 시점이 필요해
     * {@link JobScope} 로 둔다.
     *
     * @param jobRepository        Job 저장소
     * @param transactionManager   트랜잭션 관리자
     * @param memberSeedItemReader 회원 생성 리더
     * @param memberSeedItemWriter 대상 테이블 INSERT 라이터
     * @param chunkSize            커밋 단위. 없으면 {@value #DEFAULT_CHUNK_SIZE}
     * @return {@code seedStep}
     */
    @Bean
    @JobScope
    public Step seedStep(JobRepository jobRepository,
                         PlatformTransactionManager transactionManager,
                         MemberSeedItemReader memberSeedItemReader,
                         JdbcBatchItemWriter<MemberBase> memberSeedItemWriter,
                         @Value("#{jobParameters['chunkSize']}") String chunkSize) {
        int size = chunkSize == null ? DEFAULT_CHUNK_SIZE : Integer.parseInt(chunkSize);
        return new StepBuilder("seedStep", jobRepository)
                .<MemberBase, MemberBase>chunk(size, transactionManager)
                .reader(memberSeedItemReader)
                .writer(memberSeedItemWriter)
                .build();
    }

    /**
     * 회원을 생성하는 리더.
     *
     * <p>반환 타입을 인터페이스가 아니라 구현 클래스로 선언한 것이 중요하다. {@link StepScope} 는
     * 선언된 타입으로 프록시를 만드는데, {@code ItemReader} 로 선언하면 프록시가
     * {@code ItemStream} 을 구현하지 않아 Step 이 리더를 스트림으로 등록하지 못한다.
     * 그러면 읽은 건수가 {@code ExecutionContext} 에 저장되지 않아 재시작이 깨진다.
     *
     * @param target 대상 테이블
     * @param count  생성 건수. 없으면 대상별 기본값
     * @param seed   난수 시드. 없으면 {@link MemberSeedGenerator#DEFAULT_SEED}
     * @return 회원 생성 리더
     */
    @Bean
    @StepScope
    public MemberSeedItemReader memberSeedItemReader(@Value("#{jobParameters['target']}") String target,
                                                    @Value("#{jobParameters['count']}") String count,
                                                    @Value("#{jobParameters['seed']}") String seed) {
        SeedTarget seedTarget = SeedTarget.from(target);
        long rows = count == null ? seedTarget.defaultCount() : Long.parseLong(count);
        long seedValue = seed == null ? MemberSeedGenerator.DEFAULT_SEED : Long.parseLong(seed);

        MemberSeedGenerator generator =
                MemberSeedGenerator.forTarget(seedTarget, seedValue, MemberSeedGenerator.BASE_TIME);
        return new MemberSeedItemReader(generator, rows);
    }

    /**
     * 대상 테이블에 INSERT 하는 라이터.
     *
     * <p>테이블 이름이 SQL 문자열로 조립되지만, {@link SeedTarget#from(String)} 을 통과한 값만
     * 쓰므로 Job 파라미터의 임의 문자열이 SQL 에 섞이지 않는다.
     *
     * @param dataSource 데이터 소스
     * @param target     대상 테이블
     * @return 대상 테이블용 라이터
     */
    @Bean
    @StepScope
    public JdbcBatchItemWriter<MemberBase> memberSeedItemWriter(DataSource dataSource,
                                                               @Value("#{jobParameters['target']}") String target) {
        SeedTarget seedTarget = SeedTarget.from(target);
        return new JdbcBatchItemWriterBuilder<MemberBase>()
                .dataSource(dataSource)
                .sql(INSERT_SQL_TEMPLATE.formatted(seedTarget.tableName()))
                .itemSqlParameterSourceProvider(SeedJobConfig::toParameters)
                .build();
    }

    /**
     * 대상 테이블이 비어 있는지 확인하는 리스너.
     *
     * @param jdbcTemplate JDBC 템플릿
     * @return 리스너
     */
    @Bean
    public TargetTableEmptyValidator targetTableEmptyValidator(JdbcTemplate jdbcTemplate) {
        return new TargetTableEmptyValidator(jdbcTemplate);
    }

    /**
     * 회원을 INSERT 파라미터로 변환한다.
     *
     * <p>enum 은 {@code name()} 으로 넘긴다. JDBC 는 enum 을 모르므로 {@code @Enumerated(STRING)} 과
     * 같은 문자열 표현을 여기서 직접 맞춰야 한다. nullable 컬럼에는 SQL 타입을 명시해 드라이버가
     * {@code null} 의 타입을 추론하지 않게 한다.
     *
     * <p>{@code id} 를 함께 넘기는 것이 중요하다. {@code AUTO_INCREMENT} 에 맡기면 드라이버의 bulk
     * INSERT 와 InnoDB 의 블록 단위 할당 때문에 번호에 구멍이 생겨, 생성기가 정한 순번과 실제
     * {@code id} 가 어긋난다.
     *
     * @param member 생성된 회원
     * @return 이름 있는 파라미터 소스
     */
    private static SqlParameterSource toParameters(MemberBase member) {
        return new MapSqlParameterSource()
                .addValue("id", member.getId())
                .addValue("email", member.getEmail())
                .addValue("name", member.getName())
                .addValue("grade", member.getGrade().name())
                .addValue("point", member.getPoint())
                .addValue("status", member.getStatus().name())
                .addValue("referrerId", member.getReferrerId(), Types.BIGINT)
                .addValue("processed", member.isProcessed())
                .addValue("idempotencyKey", member.getIdempotencyKey(), Types.VARCHAR)
                .addValue("createdAt", member.getCreatedAt())
                .addValue("updatedAt", member.getUpdatedAt(), Types.TIMESTAMP);
    }
}
