package com.h4ndwoong.batchdemo.skip;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.support.DatabaseWorkloadListener;
import com.h4ndwoong.batchdemo.support.RunIdOnlyIncrementer;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 2번 문제(skip/retry, 오류 행 격리) <b>before</b> 구성. 오류 처리가 없는 평범한 청크 Step 이다.
 *
 * <p><b>재현하는 증상</b><br>
 * {@code member_b} 10만 건 중 200번째 행마다 오염되어 있다. 첫 청크(1~100)는 깨끗해서 커밋되고,
 * 두 번째 청크(101~200)의 마지막 행에서 {@link MemberValidationException} 이 올라온다.
 * 그 예외를 잡는 것이 아무것도 없으므로 <b>Step 전체가 {@code FAILED}</b> 로 끝난다. 결과는
 * <ul>
 *   <li>10만 건 중 <b>100건만</b> 처리되었다.</li>
 *   <li>어디까지 처리되었는지 알려면 {@code processed} 컬럼을 직접 세어 봐야 한다.</li>
 *   <li>오염 행 500건이 <b>어느 행이었는지 아무 데도 남지 않는다.</b> 로그의 스택트레이스에
 *       첫 번째 행 하나만 있다.</li>
 *   <li>같은 파라미터로 재시작해도 같은 행에서 다시 죽는다. 사람이 데이터를 고치기 전에는
 *       이 배치는 영원히 끝나지 않는다.</li>
 * </ul>
 *
 * <p>이 구성이 "잘못 짠 코드" 처럼 보이지 않는다는 점이 중요하다. 리더·프로세서·라이터가
 * 모두 정상이고, 다만 <b>입력이 완벽할 것이라고 가정</b>했을 뿐이다.
 *
 * <p><b>실행</b>
 * <pre>{@code
 * # 먼저 시딩: ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.batch.job.name=seedJob target=member_b'
 * ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.profiles.active=before --spring.batch.job.name=skipJob'
 * }</pre>
 *
 * <p><b>Job 파라미터</b> — 장애 주입용 세 가지뿐이며 기본값은 "장애 없음" 이다.
 * ({@code faultAtId}, {@code faultTimes}, {@code faultKind}. {@link SkipJobCommonConfig} 참고)
 * before 는 재시도를 하지 않으므로 장애를 심으면 그 청크에서 곧바로 실패한다.
 *
 * <p><b>측정</b>
 * <pre>{@code
 * SELECT s.STEP_NAME, s.STATUS, s.READ_COUNT, s.WRITE_COUNT,
 *        s.PROCESS_SKIP_COUNT, s.ROLLBACK_COUNT, s.COMMIT_COUNT
 * FROM BATCH_STEP_EXECUTION s ORDER BY s.STEP_EXECUTION_ID DESC;
 *
 * SELECT COUNT(*) FROM member_b WHERE processed = 1;   -- 실제로 반영된 건수
 * SELECT COUNT(*) FROM member_b_error;                 -- before 는 0 이다
 * }</pre>
 */
@Configuration
@Profile("before")
public class BeforeSkipJobConfig {

    /**
     * 2번 문제 Job. Step 은 {@code skipStep} 하나뿐이다.
     *
     * <p>리스너 등록 순서의 의미는 1번 문제와 같다. {@link DatabaseWorkloadListener} 를 맨 앞에 두어
     * 측정 범위가 나머지 리스너의 작업까지 덮게 한다.
     *
     * @param jobRepository    Job 저장소
     * @param skipStep         처리 Step
     * @param workloadListener DB 작업량을 기록하는 리스너
     * @param seededValidator  {@code member_b} 에 데이터가 있는지 확인하는 리스너
     * @return {@code skipJob}
     */
    @Bean
    public Job skipJob(JobRepository jobRepository,
                       Step skipStep,
                       DatabaseWorkloadListener workloadListener,
                       MemberBSeededValidator seededValidator) {
        return new JobBuilder("skipJob", jobRepository)
                .incrementer(new RunIdOnlyIncrementer())
                .listener(workloadListener)
                .listener(seededValidator)
                .start(skipStep)
                .build();
    }

    /**
     * 처리 Step. after 와 이름이 같아야 같은 축에서 비교할 수 있다.
     *
     * <p>after 와의 차이는 이 메서드에 <b>{@code .faultTolerant()} 가 없다</b>는 것뿐이다.
     *
     * @param jobRepository      Job 저장소
     * @param transactionManager 트랜잭션 관리자
     * @param memberBItemReader  {@code member_b} 커서 리더
     * @param memberBItemProcessor 검증·가공 프로세서
     * @param memberBItemWriter  장애 주입기로 감싼 UPDATE 라이터
     * @return {@code skipStep}
     */
    @Bean
    public Step skipStep(JobRepository jobRepository,
                         PlatformTransactionManager transactionManager,
                         JdbcCursorItemReader<MemberBase> memberBItemReader,
                         MemberValidatingItemProcessor memberBItemProcessor,
                         FaultInjectingItemWriter memberBItemWriter) {
        return new StepBuilder("skipStep", jobRepository)
                .<MemberBase, MemberBase>chunk(SkipJobCommonConfig.CHUNK_SIZE, transactionManager)
                .reader(memberBItemReader)
                .processor(memberBItemProcessor)
                .writer(memberBItemWriter)
                .build();
    }
}
