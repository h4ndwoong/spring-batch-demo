package com.h4ndwoong.batchdemo.outbox;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.support.DatabaseWorkloadListener;
import com.h4ndwoong.batchdemo.support.RunIdOnlyIncrementer;
import com.h4ndwoong.batchdemo.support.TableSeededValidator;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 7번 문제(외부 통보와 트랜잭션 경계) <b>before</b> 구성. 라이터 안에서 알림을 보낸다.
 *
 * <p><b>재현하는 증상</b><br>
 * {@code member_g} 의 활성 회원 상태를 휴면으로 바꾸고 알림을 보낸다. 라이터가 발송과 UPDATE 를
 * 함께 수행하므로, 청크 커밋이 실패하면 <b>상태는 롤백되고 알림만 남는다</b>. 그리고 재실행하면
 * 그 회원들이 다시 대상이 되어 <b>같은 알림을 한 번 더 받는다</b>.
 *
 * <pre>
 *   실행 1 (failAfterCount=F)   커밋 F 건 / 발송 F + 청크 건    ← 유령 알림 = 청크 크기
 *   실행 2 (재실행)             남은 것 처리                    ← 유령이 중복이 된다
 *   누적 발송                   대상 수 + 청크 크기             ← 대상보다 많다
 * </pre>
 *
 * <p><b>이 구성은 잘못 짠 코드처럼 보이지 않는다.</b> 5·6번의 before 가 그랬듯 여기서도 교과서적이다.
 * 오히려 <b>5번에서 배운 것을 이미 적용해 두었다</b> — 읽기는 {@code WHERE status = 'ACTIVE'},
 * 쓰기는 {@code WHERE id = ? AND status = 'ACTIVE'}. DB 에 대해서는 완벽히 멱등하고, 재실행하면
 * 상태 변경 0행이다. <b>그런데도 알림은 두 번 나간다.</b>
 * <blockquote>
 * 트랜잭션은 자기가 아는 것만 되돌린다. 알림은 트랜잭션이 아는 것이 아니다.
 * </blockquote>
 *
 * <p><b>멱등키를 실어 보내는데도 막지 못한다.</b> before 의 메시지에도 {@code idempotency_key} 가
 * 들어 있다 (양쪽이 같은 {@link StatusChangedNotification} 을 쓴다). 그런데 아무 일도 일어나지
 * 않는다 — <b>키는 그것을 보고 거절해 주는 쪽이 있을 때만 산다.</b> 5번에서 "UNIQUE 제약은 그
 * 컬럼에 실제로 쓰는 코드가 있을 때만 산다" 를 배웠고, 여기서는 그 문장의 바깥 판을 본다.
 *
 * <p><b>Step 이 하나뿐인 것이 증상의 요약이다.</b> 발송할 자리가 Step 안밖에 없으니 트랜잭션 안에서
 * 보낼 수밖에 없다. after 가 하는 일은 라이터를 고치는 것이 아니라 <b>보낼 자리를 하나 더 만드는
 * 것</b>이다.
 *
 * <p><b>실행</b>
 * <pre>{@code
 * # 먼저 시딩 (한 번만, 10만 건):
 * ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.batch.job.name=seedJob target=member_g'
 *
 * # 실행 1 - 커밋 직전에 죽는다
 * ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.profiles.active=before --spring.batch.job.name=outboxJob failAfterCount=50000,java.lang.Long,false'
 *
 * # 실행 2 - 재실행
 * ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.profiles.active=before --spring.batch.job.name=outboxJob'
 * }</pre>
 *
 * <p><b>리셋은 재시딩이다.</b> 이 배치는 {@code status} 를 파괴하고, 시드의 상태는 순번 해시라
 * SQL 로 되돌릴 수 없다 (5·6번과 같은 사정이다).
 * <pre>{@code
 * TRUNCATE TABLE member_g;
 * TRUNCATE TABLE member_g_outbox;
 * ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.batch.job.name=seedJob target=member_g'
 * }</pre>
 */
@Configuration
@Profile("before")
public class BeforeOutboxJobConfig {

    /**
     * 7번 문제 Job. Step 은 {@code outboxStep} 하나뿐이다.
     *
     * <p>리스너 등록 순서의 의미는 1~6번과 같다. {@link DatabaseWorkloadListener} 를 맨 앞에 두어
     * 측정 범위가 Job 전체를 덮게 하고, 지문 리포터는 그 안쪽에 둔다.
     *
     * @param jobRepository    Job 저장소
     * @param outboxStep       상태 전이와 발송 Step
     * @param workloadListener DB 작업량을 기록하는 리스너
     * @param seededValidator  {@code member_g} 에 데이터가 있는지 확인하는 리스너
     * @param deliveryReporter 알림 전달 지문 리포터
     * @return {@code outboxJob}
     */
    @Bean
    public Job outboxJob(JobRepository jobRepository,
                         Step outboxStep,
                         DatabaseWorkloadListener workloadListener,
                         @Qualifier("memberGSeededValidator") TableSeededValidator seededValidator,
                         NotificationDeliveryReporter deliveryReporter) {
        return new JobBuilder("outboxJob", jobRepository)
                .incrementer(new RunIdOnlyIncrementer())
                .listener(workloadListener)
                .listener(seededValidator)
                .listener(deliveryReporter)
                .start(outboxStep)
                .build();
    }

    /**
     * 상태를 전이시키며 알림을 보내는 Step. after 와 이름이 같아야 같은 축에서 비교할 수 있다.
     *
     * <p>after 의 {@code outboxStep} 과 리더·프로세서·청크 크기가 전부 같다. 다른 것은 라이터
     * 하나이며, after 에는 <b>이 Step 뒤에 Step 이 하나 더 있다</b>.
     *
     * @param jobRepository      Job 저장소
     * @param transactionManager 트랜잭션 관리자
     * @param memberGItemReader  커서 리더
     * @param memberGItemProcessor 상태 전이 프로세서
     * @param memberGItemWriter  장애 주입기로 감싼 라이터
     * @return {@code outboxStep}
     */
    @Bean
    public Step outboxStep(JobRepository jobRepository,
                           PlatformTransactionManager transactionManager,
                           JdbcCursorItemReader<MemberBase> memberGItemReader,
                           StatusTransitionItemProcessor memberGItemProcessor,
                           FailAfterWriteItemWriter memberGItemWriter) {
        return new StepBuilder("outboxStep", jobRepository)
                .<MemberBase, MemberBase>chunk(OutboxJobCommonConfig.CHUNK_SIZE, transactionManager)
                .reader(memberGItemReader)
                .processor(memberGItemProcessor)
                .writer(memberGItemWriter)
                .build();
    }

    /**
     * 발송과 상태 변경을 함께 수행하는 라이터. <b>before 의 전부가 여기 있다.</b>
     *
     * <p>감싸는 대상은 양쪽이 공유하는 {@code memberGStatusWriter} 다. after 는 같은 라이터를
     * Outbox 적재와 함께 묶는다 — <b>감싸는 것이 발송기냐 INSERT 냐가 7번의 유일한 차이</b>다.
     *
     * @param sender       알림 발송기
     * @param statusWriter 상태를 바꾸는 라이터
     * @return 라이터
     */
    @Bean
    public ItemWriter<MemberBase> memberGDelegateWriter(
            NotificationSender sender,
            @Qualifier("memberGStatusWriter") JdbcBatchItemWriter<MemberBase> statusWriter) {
        return new NotifyingItemWriter(sender, statusWriter);
    }
}
