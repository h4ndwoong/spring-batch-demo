package com.h4ndwoong.batchdemo.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 7번 문제 after 구성({@link AfterOutboxJobConfig})을 실제 MariaDB 에 실행해 검증한다.
 *
 * <p>상속받은 계약 시험이 "before 와 같은 일을 한다" 를 보이고, 여기 있는 시험이 <b>그 일을 어느
 * 트랜잭션 경계에서 했는가</b> 를 보인다. 7번의 주장은 "더 빠르다" 도 "덜 보낸다" 도 아니다 —
 * <b>같은 알림을 정확히 한 번씩 보낸다</b> 이다.
 *
 * <p>after 가 내주는 것도 함께 고정한다. 실패한 실행에서 커밋된 발송 요청은 <b>나가지 않은 채로
 * 남는다.</b> 그것이 유실이 아니라 지연이라는 사실을 {@link #실패하면_발송이_지연된다()} 가 확인하며,
 * exactly-once 가 아니라는 사실은 {@code OutboxRelayFailureTest} 가 확인한다.
 */
@ActiveProfiles("after")
class AfterOutboxJobTest extends OutboxJobContract {

    @Test
    @DisplayName("롤백된 청크는 적재도 함께 사라진다 - 보낼 약속이 없으니 유령도 없다")
    void 유령_알림이_없다() throws Exception {
        launch(runWithFailure());

        NotificationDeliveryChecksum checksum = reporter.current();
        assertThat(checksum.phantomSends())
                .as("before 는 여기서 청크 하나만큼의 유령을 만든다")
                .isZero();
        assertThat(checksum.outboxRows())
                .as("적재와 상태 변경이 같은 트랜잭션이므로 함께 롤백된다")
                .isEqualTo(OutboxFixture.FAIL_AFTER);
        assertThat(checksum.changedRows()).isEqualTo(OutboxFixture.FAIL_AFTER);
    }

    @Test
    @DisplayName("적재 Step 이 실패하면 릴레이는 돌지 않는다 - 유실이 아니라 지연이다")
    void 실패하면_발송이_지연된다() throws Exception {
        JobExecution execution = launch(runWithFailure());

        assertThat(execution.getStepExecutions())
                .as("첫 Step 이 실패하면 Job 이 멈춘다")
                .extracting(StepExecution::getStepName)
                .containsExactly("outboxStep");

        NotificationDeliveryChecksum checksum = reporter.current();
        assertThat(checksum.sendAttempts())
                .as("커밋은 되었지만 아직 한 건도 나가지 않았다")
                .isZero();
        assertThat(checksum.outboxPending())
                .as("나가지 못한 %d 건은 사라진 것이 아니라 기다리고 있다", OutboxFixture.FAIL_AFTER)
                .isEqualTo(OutboxFixture.FAIL_AFTER);
    }

    @Test
    @DisplayName("재실행하면 밀린 것까지 정확히 한 번씩 나간다 - 7번의 결승선")
    void 재실행하면_한_번씩_나간다() throws Exception {
        launch(runWithFailure());

        launch(nextRun());

        NotificationDeliveryChecksum checksum = reporter.current();
        assertThat(checksum.sendAttempts())
                .as("before 는 여기서 대상 수보다 청크 하나만큼 많이 보낸다")
                .isEqualTo(OutboxFixture.targetCount());
        assertThat(checksum.distinctKeys()).isEqualTo(OutboxFixture.targetCount());
        assertThat(checksum.duplicateSends()).isZero();
        assertThat(checksum.outboxPending())
                .as("밀려 있던 발송 요청이 남아 있으면 안 된다")
                .isZero();
    }

    @Test
    @DisplayName("적재 수와 발송 수가 같다 - 약속한 만큼 정확히 보냈다")
    void 적재와_발송이_같다() throws Exception {
        launch(run());

        NotificationDeliveryChecksum checksum = reporter.current();
        assertThat(checksum.outboxRows()).isEqualTo(OutboxFixture.targetCount());
        assertThat(checksum.outboxSent()).isEqualTo(OutboxFixture.targetCount());
        assertThat(checksum.outboxPending()).isZero();
        assertThat(checksum.sendAttempts()).isEqualTo(OutboxFixture.targetCount());
    }

    @Test
    @DisplayName("릴레이는 적재된 순서대로 보낸다")
    void 순서대로_보낸다() throws Exception {
        launch(run());

        assertThat(recorder.attempts())
                .extracting(SendAttempt::memberId)
                .containsExactlyElementsOf(OutboxFixture.targetIds());
    }

    @Test
    @DisplayName("적재 Step 의 통계는 before 와 같다 - 차이는 통계에 잡히지 않는 곳에 있다")
    void 적재_Step_통계() throws Exception {
        JobExecution execution = launch(run());

        StepExecution outboxStep = step(execution, "outboxStep");
        assertThat(outboxStep.getReadCount()).isEqualTo(OutboxFixture.targetCount());
        assertThat(outboxStep.getWriteCount())
                .as("6번과 달리 7번은 Step 통계로 두 프로파일을 구분할 수 없다")
                .isEqualTo(OutboxFixture.targetCount());

        StepExecution relayStep = step(execution, "relayStep");
        assertThat(relayStep.getReadCount()).isEqualTo(OutboxFixture.targetCount());
        assertThat(relayStep.getWriteCount()).isEqualTo(OutboxFixture.targetCount());
    }
}
