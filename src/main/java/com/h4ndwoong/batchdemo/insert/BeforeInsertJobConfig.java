package com.h4ndwoong.batchdemo.insert;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.seed.MemberSeedGenerator;
import com.h4ndwoong.batchdemo.seed.MemberSeedItemReader;
import com.h4ndwoong.batchdemo.support.DatabaseWorkloadListener;
import com.h4ndwoong.batchdemo.support.RunIdOnlyIncrementer;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JpaItemWriter;
import org.springframework.batch.item.database.builder.JpaItemWriterBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 1번 문제(대량 INSERT 성능) <b>before</b> 구성. {@code member_a} 에 100만 건을 최악의 경로로 적재한다.
 *
 * <p><b>재현하는 증상 세 가지</b>
 * <ol>
 *   <li><b>인덱스를 미리 만든 상태로 적재</b> — {@link IndexPreCreationListener} 가 Job 시작 시
 *       {@code email} UK 와 {@code grade}, {@code created_at} 인덱스를 만든다. 이후 모든 행이
 *       인덱스를 랜덤 위치에 갱신한다.</li>
 *   <li><b>{@code chunk(100)}</b> — 100만 건이면 <b>1만 번</b> 커밋한다. 커밋마다 트랜잭션 로그
 *       flush 가 따라붙는다. after 의 {@code chunk(5000)} 은 같은 데이터를 200번에 끝낸다.</li>
 *   <li><b>{@link JpaItemWriter} 행별 INSERT</b> — {@link com.h4ndwoong.batchdemo.domain.MemberBase}
 *       가 {@code GenerationType.IDENTITY} 라서 Hibernate 는 INSERT 직후 생성 키를 읽어야 하고,
 *       그 때문에 JDBC batch 가 꺼진다. 즉 행 수만큼 왕복한다.</li>
 * </ol>
 *
 * <p><b>실행</b>
 * <pre>{@code
 * ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.profiles.active=before --spring.batch.job.name=insertJob'
 *
 * # 규모를 줄여서 확인
 * ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.profiles.active=before --spring.batch.job.name=insertJob count=10000'
 * }</pre>
 *
 * <p><b>Job 파라미터</b>
 * <ul>
 *   <li>{@code count} — 적재 건수. 기본값 {@link MemberAItemReaderFactory#DEFAULT_COUNT}</li>
 *   <li>{@code seed} — 난수 시드. 기본값 {@link MemberSeedGenerator#DEFAULT_SEED}</li>
 * </ul>
 * 청크 크기는 파라미터가 아니다. {@code 100} 이라는 값 자체가 before 의 정의이고, before/after 가
 * 서로 다른 축에서 측정되는 사고를 막아야 하기 때문이다.
 *
 * <p><b>측정</b>은 별도 로깅 없이 배치 메타데이터에서 읽는다.
 * <pre>{@code
 * SELECT s.STEP_NAME, s.READ_COUNT, s.WRITE_COUNT, s.COMMIT_COUNT,
 *        TIMESTAMPDIFF(SECOND, s.START_TIME, s.END_TIME) AS SECONDS
 * FROM BATCH_STEP_EXECUTION s
 * JOIN BATCH_JOB_EXECUTION j ON j.JOB_EXECUTION_ID = s.JOB_EXECUTION_ID
 * ORDER BY s.STEP_EXECUTION_ID DESC;
 * }</pre>
 *
 * <p>after 구성은 Job/Step 이름을 그대로 두고 이 클래스와 같은 층위에서
 * {@code @Profile("after")} 로 추가한다. 런타임 분기가 아니라 빈 구성으로 나누는 것이 실습 규칙이다.
 */
@Configuration
@Profile("before")
public class BeforeInsertJobConfig {

    /**
     * before 의 커밋 단위. 100만 건이면 1만 번 커밋한다.
     *
     * <p>"작은 청크가 안전하다"는 직관이 어디서 비용을 치르는지 보는 것이 이 실습의 목적이다.
     */
    public static final int CHUNK_SIZE = 100;

    /**
     * 1번 문제 Job. Step 은 {@code insertStep} 하나뿐이다.
     *
     * <p><b>리스너 등록 순서에 의미가 있다.</b>
     * <ol>
     *   <li>{@link DatabaseWorkloadListener} — 맨 앞에 둔다. {@code beforeJob} 은 등록 순서대로,
     *       {@code afterJob} 은 역순으로 호출되므로 측정 범위가 나머지 리스너의 작업까지 덮는다.
     *       인덱스 생성 비용이 before(적재 전)와 after(적재 후) 양쪽에서 똑같이 계산되어야 공정하다.</li>
     *   <li>{@link MemberAEmptyValidator} — 적재할 수 없는 상태면 인덱스도 건드리지 않고 끝낸다.</li>
     *   <li>{@link IndexPreCreationListener} — before 의 증상을 만든다.</li>
     * </ol>
     *
     * <p>{@link RunIdOnlyIncrementer} 를 붙여 {@code TRUNCATE} 후 같은 파라미터로 다시 측정할 수
     * 있게 한다. 이전 실행의 {@code count} 가 상속되지 않는 것이 중요하다.
     *
     * @param jobRepository    Job 저장소
     * @param insertStep       적재 Step
     * @param workloadListener DB 작업량(쿼리 왕복·디스크 write)을 기록하는 리스너
     * @param emptyValidator   {@code member_a} 가 비어 있는지 확인하는 리스너
     * @param indexPreCreator  적재 전에 보조 인덱스를 만드는 리스너
     * @return {@code insertJob}
     */
    @Bean
    public Job insertJob(JobRepository jobRepository,
                         Step insertStep,
                         DatabaseWorkloadListener workloadListener,
                         MemberAEmptyValidator emptyValidator,
                         IndexPreCreationListener indexPreCreator) {
        return new JobBuilder("insertJob", jobRepository)
                .incrementer(new RunIdOnlyIncrementer())
                .listener(workloadListener)
                .listener(emptyValidator)
                .listener(indexPreCreator)
                .start(insertStep)
                .build();
    }

    /**
     * 적재 Step. after 와 이름이 같아야 같은 축에서 비교할 수 있다.
     *
     * @param jobRepository      Job 저장소
     * @param transactionManager 트랜잭션 관리자. JPA 가 있으므로 {@code JpaTransactionManager} 이며,
     *                           {@link JpaItemWriter} 는 이 트랜잭션에 묶인 영속성 컨텍스트를 쓴다
     * @param memberAItemReader  회원 생성 리더
     * @param memberAItemWriter  JPA 라이터
     * @return {@code insertStep}
     */
    @Bean
    public Step insertStep(JobRepository jobRepository,
                           PlatformTransactionManager transactionManager,
                           @Qualifier("memberAItemReader") MemberSeedItemReader memberAItemReader,
                           JpaItemWriter<MemberBase> memberAItemWriter) {
        return new StepBuilder("insertStep", jobRepository)
                .<MemberBase, MemberBase>chunk(CHUNK_SIZE, transactionManager)
                .reader(memberAItemReader)
                .writer(memberAItemWriter)
                .build();
    }

    /**
     * JPA 라이터. before 의 쓰기 경로다.
     *
     * <p>{@code usePersist(true)} 가 중요하다. 기본값인 {@code merge} 는 행마다 SELECT 를 먼저
     * 날려 이미 존재하는 행인지 확인하는데, 그러면 재현하려는 증상이 "행별 INSERT" 가 아니라
     * "쓰기 전 불필요한 조회" 로 바뀐다. 신규 적재이므로 {@code persist} 가 의미상으로도 맞다.
     *
     * @param entityManagerFactory 엔티티 매니저 팩토리
     * @return {@code member_a} 에 행별로 INSERT 하는 라이터
     */
    @Bean
    public JpaItemWriter<MemberBase> memberAItemWriter(EntityManagerFactory entityManagerFactory) {
        return new JpaItemWriterBuilder<MemberBase>()
                .entityManagerFactory(entityManagerFactory)
                .usePersist(true)
                .build();
    }

    /**
     * 적재 전에 보조 인덱스를 만드는 리스너. after 는 같은 {@link MemberAIndexCreator} 를 적재
     * <em>후</em>에 부르는 {@link IndexPostCreationListener} 로 바꿔 끼운다.
     *
     * @param indexCreator 인덱스 생성기
     * @return 리스너
     */
    @Bean
    public IndexPreCreationListener indexPreCreationListener(MemberAIndexCreator indexCreator) {
        return new IndexPreCreationListener(indexCreator);
    }
}
