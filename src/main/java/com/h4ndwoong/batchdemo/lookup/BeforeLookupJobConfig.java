package com.h4ndwoong.batchdemo.lookup;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.support.DatabaseWorkloadListener;
import com.h4ndwoong.batchdemo.support.RunIdOnlyIncrementer;
import com.h4ndwoong.batchdemo.support.TableSeededValidator;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 4번 문제(Processor N+1 조회) <b>before</b> 구성. 추천인을 행마다 조회한다.
 *
 * <p><b>재현하는 증상</b><br>
 * {@code member_d} 50만 건을 순회하며 행마다 SELECT 2회를 보낸다 — <b>100만 번의 왕복</b>이다.
 * 3번 문제와 대칭을 이룬다. 3번은 왕복 수가 같은데 쿼리 하나가 20억 행을 읽어서 느렸고,
 * 4번은 <b>쿼리 하나하나는 1ms 도 안 걸리는 PK 조회인데 그것이 100만 번</b>이라 느리다.
 * 느린 쿼리 로그에는 아무것도 남지 않는다.
 *
 * <p><b>이 구성이 "잘못 짠 코드" 처럼 보이지 않는다는 점이 중요하다.</b> 프로세서는 조회를 한 번
 * 요청할 뿐이고({@link GradeRecalculatingItemProcessor}), 조회기는 PK 로 한 건을 정확히 찾는다.
 * 어느 메서드도 느리지 않다. 문제는 <b>메서드가 아니라 호출 횟수</b>에 있고, 그 횟수는 코드
 * 어디에도 적혀 있지 않다 — 데이터 건수가 정한다.
 *
 * <p><b>실행</b>
 * <pre>{@code
 * # 먼저 시딩 (한 번만, 50만 건):
 * ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.batch.job.name=seedJob target=member_d'
 *
 * # 전체 순회
 * ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.profiles.active=before --spring.batch.job.name=lookupJob'
 *
 * # 앞 5만 건만 (after 에도 같은 값을 준다)
 * ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.profiles.active=before --spring.batch.job.name=lookupJob' limit=50000
 *
 * # 청크 크기를 바꿔서 (before 에서는 조회 횟수가 <b>변하지 않는다</b>는 것이 관찰 대상이다)
 * ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.profiles.active=before --lookup.chunk-size=5000 --spring.batch.job.name=lookupJob'
 * }</pre>
 *
 * <p><b>리셋이 필요 없다.</b> 이 Job 은 읽기만 하므로 몇 번을 돌려도 {@code member_d} 가 변하지 않는다.
 */
@Configuration
@Profile("before")
public class BeforeLookupJobConfig {

    /**
     * 4번 문제 Job. Step 은 {@code lookupStep} 하나뿐이다.
     *
     * <p>리스너 등록 순서의 의미는 1~3번 문제와 같다. {@link DatabaseWorkloadListener} 를 맨 앞에
     * 두어 측정 범위가 Job 전체를 덮게 한다.
     *
     * @param jobRepository    Job 저장소
     * @param lookupStep       가공 Step
     * @param workloadListener DB 작업량을 기록하는 리스너
     * @param seededValidator  {@code member_d} 에 데이터가 있는지 확인하는 리스너
     * @return {@code lookupJob}
     */
    @Bean
    public Job lookupJob(JobRepository jobRepository,
                         Step lookupStep,
                         DatabaseWorkloadListener workloadListener,
                         @Qualifier("memberDSeededValidator") TableSeededValidator seededValidator) {
        return new JobBuilder("lookupJob", jobRepository)
                .incrementer(new RunIdOnlyIncrementer())
                .listener(workloadListener)
                .listener(seededValidator)
                .start(lookupStep)
                .build();
    }

    /**
     * 가공 Step. after 와 이름·청크 크기·리더·프로세서·라이터가 모두 같고 <b>조회 전략만 다르다</b>.
     *
     * <p>after 와 비교하면 이 메서드에는 <b>리스너 등록이 하나 적다</b>. 조회 전략이 청크를 알아야
     * 할 이유가 before 에는 없기 때문이며, 그 "청크를 모른다" 가 곧 N+1 의 원인이다.
     *
     * @param jobRepository        Job 저장소
     * @param transactionManager   트랜잭션 관리자
     * @param memberDItemReader    커서 리더
     * @param memberDItemProcessor 등급 재산정 프로세서
     * @param memberDItemWriter    결과를 세는 라이터
     * @param lookupReporter       조회 계측 보고자
     * @param lookupChunkSize      청크 크기
     * @return {@code lookupStep}
     */
    @Bean
    public Step lookupStep(JobRepository jobRepository,
                           PlatformTransactionManager transactionManager,
                           JdbcCursorItemReader<MemberBase> memberDItemReader,
                           GradeRecalculatingItemProcessor memberDItemProcessor,
                           GradeDecisionItemWriter memberDItemWriter,
                           ReferrerLookupReporter lookupReporter,
                           LookupChunkSize lookupChunkSize) {
        return new StepBuilder("lookupStep", jobRepository)
                .<MemberBase, GradeDecision>chunk(lookupChunkSize.value(), transactionManager)
                .reader(memberDItemReader)
                .processor(memberDItemProcessor)
                .writer(memberDItemWriter)
                .listener(lookupReporter)
                .build();
    }

    /**
     * 행별 추천인 조회기. <b>before 의 전부</b>다.
     *
     * <p>싱글턴이어도 되는 이유는 계측치를 {@link ReferrerLookupReporter} 가 Step 시작 시
     * 비우기 때문이다 (3번의 {@code TraversalChecksumItemWriter} 와 같은 이유로, 싱글턴이어야
     * 테스트가 Step 종료 후 같은 인스턴스에서 값을 읽을 수 있다).
     *
     * @param jdbcTemplate JDBC 템플릿
     * @return 조회기
     */
    @Bean
    public ReferrerLookup referrerLookup(JdbcTemplate jdbcTemplate) {
        return new PerItemReferrerLookup(jdbcTemplate);
    }
}
