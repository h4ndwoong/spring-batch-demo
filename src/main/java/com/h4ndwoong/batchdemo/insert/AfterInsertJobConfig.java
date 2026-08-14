package com.h4ndwoong.batchdemo.insert;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.seed.MemberSeedItemReader;
import com.h4ndwoong.batchdemo.support.DatabaseWorkloadListener;
import com.h4ndwoong.batchdemo.support.RunIdOnlyIncrementer;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
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
 * 1번 문제(대량 INSERT 성능) <b>after</b> 구성. before 와 <em>같은 데이터</em>를 <em>다른 경로</em>로
 * 적재한다.
 *
 * <p><b>before 의 세 가지 증상에 하나씩 대응한다</b>
 * <ol>
 *   <li><b>인덱스 후생성</b> — {@link IndexPostCreationListener} 가 시작 시 보조 인덱스를 제거하고
 *       적재가 끝난 뒤에 만든다. 같은 인덱스를 만들지만 정렬된 데이터를 한 번에 훑어 구축하므로
 *       페이지 분할이 없다.</li>
 *   <li><b>{@code chunk(5000)}</b> — 100만 건이면 커밋 201회. before 의 1만 회 대비 50분의 1이고,
 *       커밋마다 딸려오던 배치 메타데이터 왕복(커밋당 UPDATE 2 + SELECT 1)도 같은 비율로 준다.</li>
 *   <li><b>{@link JdbcBatchItemWriter}</b> — JPA 를 거치지 않으므로 {@code IDENTITY} 채번 때문에
 *       JDBC batch 가 꺼지는 문제가 사라진다. {@code application-after.properties} 의 JDBC URL 이
 *       {@code rewriteBatchedStatements=true} 를 켜서 여러 행을 한 문장으로 묶어 보낸다.</li>
 * </ol>
 *
 * <p>리더는 {@link InsertJobCommonConfig} 의 것을 그대로 쓴다. 입력이 같아야 비교가 성립한다.
 * Job/Step 이름도 before 와 같아야 {@code BATCH_STEP_EXECUTION} 을 같은 축에서 읽을 수 있다.
 *
 * <p><b>실행</b>
 * <pre>{@code
 * # before 를 돌린 뒤라면 TRUNCATE TABLE member_a 로 비운다. 인덱스는 Job 이 알아서 치운다.
 * ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.profiles.active=after --spring.batch.job.name=insertJob'
 * }</pre>
 */
@Configuration
@Profile("after")
public class AfterInsertJobConfig {

    /**
     * after 의 커밋 단위. 100만 건이면 201회 커밋한다.
     *
     * <p>무작정 키우면 되는 값은 아니다. 청크 하나가 트랜잭션 하나이므로 그만큼 언두 로그와 락이
     * 길게 유지되고, 실패 시 되돌아가는 양도 커진다. 5000 은 그 균형점으로 잡은 값이다.
     */
    public static final int CHUNK_SIZE = 5_000;

    /**
     * {@code id} 를 넘기지 않는다. {@code AUTO_INCREMENT} 에 맡겨야 여러 행을 한 문장으로 묶을 수
     * 있고, {@code member_a} 는 자기 참조가 없어 순번을 미리 알 필요가 없다.
     */
    private static final String INSERT_SQL = """
            INSERT INTO member_a (email, name, grade, point, status, referrer_id,
                                  processed, idempotency_key, created_at, updated_at)
            VALUES (:email, :name, :grade, :point, :status, :referrerId,
                    :processed, :idempotencyKey, :createdAt, :updatedAt)""";

    /**
     * 1번 문제 Job. before 와 이름이 같고, 리스너 등록 순서의 의미도 같다.
     *
     * <p>{@link DatabaseWorkloadListener} 를 맨 앞에 두는 것이 after 에서 특히 중요하다.
     * {@code afterJob} 이 역순으로 호출되므로 측정 범위가 {@link IndexPostCreationListener} 의
     * 인덱스 생성까지 덮는다. 그 비용을 빼고 재면 after 가 부당하게 유리해진다.
     *
     * @param jobRepository    Job 저장소
     * @param insertStep       적재 Step
     * @param workloadListener DB 작업량을 기록하는 리스너
     * @param emptyValidator   {@code member_a} 가 비어 있는지 확인하는 리스너
     * @param indexPostCreator 적재 후에 보조 인덱스를 만드는 리스너
     * @return {@code insertJob}
     */
    @Bean
    public Job insertJob(JobRepository jobRepository,
                         Step insertStep,
                         DatabaseWorkloadListener workloadListener,
                         MemberAEmptyValidator emptyValidator,
                         IndexPostCreationListener indexPostCreator) {
        return new JobBuilder("insertJob", jobRepository)
                .incrementer(new RunIdOnlyIncrementer())
                .listener(workloadListener)
                .listener(emptyValidator)
                .listener(indexPostCreator)
                .start(insertStep)
                .build();
    }

    /**
     * 적재 Step. before 와 이름이 같아야 같은 축에서 비교할 수 있다.
     *
     * @param jobRepository      Job 저장소
     * @param transactionManager 트랜잭션 관리자
     * @param memberAItemReader  회원 생성 리더. before 와 동일한 빈이다
     * @param memberAItemWriter  JDBC 배치 라이터
     * @return {@code insertStep}
     */
    @Bean
    public Step insertStep(JobRepository jobRepository,
                           PlatformTransactionManager transactionManager,
                           @Qualifier("memberAItemReader") MemberSeedItemReader memberAItemReader,
                           JdbcBatchItemWriter<MemberBase> memberAItemWriter) {
        return new StepBuilder("insertStep", jobRepository)
                .<MemberBase, MemberBase>chunk(CHUNK_SIZE, transactionManager)
                .reader(memberAItemReader)
                .writer(memberAItemWriter)
                .build();
    }

    /**
     * JDBC 배치 라이터. after 의 쓰기 경로다.
     *
     * <p>영속성 컨텍스트를 거치지 않으므로 5000건을 쌓아도 메모리에 엔티티가 남지 않고, 생성 키를
     * 돌려받을 필요가 없으므로 드라이버가 배치를 통째로 묶어 보낼 수 있다.
     *
     * @param dataSource 데이터 소스. {@code application-after.properties} 의 URL 이 배치 묶음을 켠다
     * @return {@code member_a} 에 배치로 INSERT 하는 라이터
     */
    @Bean
    public JdbcBatchItemWriter<MemberBase> memberAItemWriter(DataSource dataSource) {
        return new JdbcBatchItemWriterBuilder<MemberBase>()
                .dataSource(dataSource)
                .sql(INSERT_SQL)
                .itemSqlParameterSourceProvider(AfterInsertJobConfig::toParameters)
                .build();
    }

    /**
     * 적재 후에 보조 인덱스를 만드는 리스너.
     *
     * @param indexCreator 인덱스 생성기
     * @return 리스너
     */
    @Bean
    public IndexPostCreationListener indexPostCreationListener(MemberAIndexCreator indexCreator) {
        return new IndexPostCreationListener(indexCreator);
    }

    /**
     * 회원을 INSERT 파라미터로 변환한다.
     *
     * <p>enum 은 {@code name()} 으로 넘긴다. JDBC 는 enum 을 모르므로 {@code @Enumerated(STRING)} 과
     * 같은 문자열 표현을 여기서 직접 맞춰야 한다. 이것이 어긋나면 before 와 after 가 다른 데이터를
     * 적재하게 된다. nullable 컬럼에는 SQL 타입을 명시해 드라이버가 {@code null} 의 타입을 추론하지
     * 않게 한다.
     *
     * @param member 생성된 회원
     * @return 이름 있는 파라미터 소스
     */
    private static SqlParameterSource toParameters(MemberBase member) {
        return new MapSqlParameterSource()
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
