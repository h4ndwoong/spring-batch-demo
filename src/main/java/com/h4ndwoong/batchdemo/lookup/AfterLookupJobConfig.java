package com.h4ndwoong.batchdemo.lookup;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.support.DatabaseWorkloadListener;
import com.h4ndwoong.batchdemo.support.RunIdOnlyIncrementer;
import com.h4ndwoong.batchdemo.support.TableSeededValidator;
import org.springframework.batch.core.ChunkListener;
import org.springframework.batch.core.ItemReadListener;
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
 * 4번 문제(Processor N+1 조회) <b>after</b> 구성. 추천인을 청크당 한 번에 모아 조회한다.
 *
 * <p><b>before 와 다른 것은 조회 전략 빈 하나와 그 빈의 리스너 등록 두 줄뿐이다.</b> Job 이름,
 * Step 이름, 청크 크기, 리더, 프로세서, 라이터, 등급 정책, 측정 장치가 모두 같다.
 *
 * <p><b>기대되는 결과</b>
 * <pre>
 *   등급 산정 체크섬   before 와 완전히 같다     ← 같은 답을 냈다는 증거. 이게 먼저다
 *   READ_COUNT       before 와 같다
 *   조회 요구         before 와 같다            ← 프로세서는 똑같이 물었다
 *   SELECT 왕복       before 의 약 1/2000       ← 줄어든 것은 "묻는 방식" 이다
 *   HANDLER_READ_KEY  before 의 약 1/2         ← DB 가 실제로 한 탐색은 절반만 줄었다
 * </pre>
 *
 * <p><b>1·3번 문제와 개선의 성격이 다르다.</b> 1번은 쓰기 왕복을 묶어서, 3번은 쿼리가 읽는 행을
 * 줄여서 빨라졌다. 4번은 <b>읽는 행도 그대로고 답도 그대로인데 왕복만</b> 줄인다. 세 문제를 나란히
 * 놓으면 "느리다" 의 원인이 세 군데에 있을 수 있다는 것이 보인다 — 왕복 횟수, 왕복당 작업량,
 * 그리고 이 둘 중 어느 쪽도 아닌 완주 여부(2번).
 *
 * <p><b>청크 크기가 곧 조회 묶음 크기다.</b> {@code --lookup.chunk-size} 를 바꿔 가며 실행하면
 * 왕복이 반비례로 줄고, 대신 캐시가 들고 있는 추천인이 그만큼 늘어난다
 * ({@link LookupChunkSize} 참고). before 에서는 같은 값을 바꿔도 <b>조회 횟수가 꿈쩍하지 않는다</b> —
 * 청크는 커밋 단위이지 조회 단위가 아니기 때문이며, 그 사실을 눈으로 보는 것이 이 문제의 둘째 축이다.
 *
 * <p><b>실행</b>
 * <pre>{@code
 * ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.profiles.active=after --spring.batch.job.name=lookupJob'
 *
 * # 청크 크기별 비교
 * ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.profiles.active=after --lookup.chunk-size=5000 --spring.batch.job.name=lookupJob'
 * }</pre>
 *
 * @see ChunkedReferrerLookup 청크를 어떻게 알아내는가, 그리고 그 전제(faultTolerant 아님)
 */
@Configuration
@Profile("after")
public class AfterLookupJobConfig {

    /**
     * 4번 문제 Job. before 와 이름이 같고 리스너 등록 순서의 의미도 같다.
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
     * 가공 Step. before 와 이름이 같아야 같은 축에서 비교할 수 있다.
     *
     * <p><b>리스너를 두 번 등록하는 이유</b><br>
     * {@link ChunkedReferrerLookup} 은 조회 전략이자 {@link ItemReadListener} 이자
     * {@link ChunkListener} 다. 읽기 리스너 자격으로는 청크의 {@code referrer_id} 를 모으고,
     * 청크 리스너 자격으로는 청크 경계에서 캐시를 비운다. <b>이 두 등록이 after 의 "청크를 안다" 를
     * 성립시키는 전부</b>이며, 캐스팅 없이 넘기면 어느 오버로드를 부를지 정할 수 없어 컴파일되지
     * 않는다 (2번의 {@code ErrorRowIsolatingSkipListener} 와 같은 상황이다).
     *
     * @param jobRepository        Job 저장소
     * @param transactionManager   트랜잭션 관리자
     * @param memberDItemReader    커서 리더
     * @param memberDItemProcessor 등급 재산정 프로세서
     * @param memberDItemWriter    결과를 세는 라이터
     * @param referrerLookup       청크 일괄 조회기. 리스너로도 등록된다
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
                           ChunkedReferrerLookup referrerLookup,
                           ReferrerLookupReporter lookupReporter,
                           LookupChunkSize lookupChunkSize) {
        return new StepBuilder("lookupStep", jobRepository)
                .<MemberBase, GradeDecision>chunk(lookupChunkSize.value(), transactionManager)
                .reader(memberDItemReader)
                .processor(memberDItemProcessor)
                .writer(memberDItemWriter)
                .listener((ItemReadListener<MemberBase>) referrerLookup)
                .listener((ChunkListener) referrerLookup)
                .listener(lookupReporter)
                .build();
    }

    /**
     * 청크 일괄 추천인 조회기. <b>after 의 전부</b>다.
     *
     * <p>반환 타입이 구현 클래스인 이유는 Step 이 이 빈을 리스너로도 등록해야 하기 때문이다.
     * {@link LookupJobCommonConfig} 의 프로세서·보고자는 여전히 {@link ReferrerLookup} 으로 받는다.
     *
     * @param jdbcTemplate JDBC 템플릿
     * @return 조회기
     */
    @Bean
    public ChunkedReferrerLookup referrerLookup(JdbcTemplate jdbcTemplate) {
        return new ChunkedReferrerLookup(jdbcTemplate);
    }
}
