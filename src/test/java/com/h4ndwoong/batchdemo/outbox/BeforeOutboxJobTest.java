package com.h4ndwoong.batchdemo.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.StepExecution;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 7번 문제 before 구성({@link BeforeOutboxJobConfig})을 실제 MariaDB 에 실행해 검증한다.
 *
 * <p>상속받은 계약 시험이 "DB 에 대해서는 아무 문제가 없다" 를 보이고, 여기 있는 시험이 <b>그
 * 아무 문제 없는 배치가 무엇을 하고 있는가</b> 를 보인다. 7번의 증상은 배치 안에서는 관측되지
 * 않는다 — Step 통계도, 상태 분포도, 멱등성도 전부 정상이다.
 *
 * <p><b>여기서 고정하는 두 숫자가 7번의 전부다.</b>
 * <pre>
 *   유령 알림 (실패한 실행 직후)  = 청크 크기
 *   중복 발송 (재실행 뒤)         = 청크 크기
 * </pre>
 * 두 값이 같은 것은 우연이 아니다. <b>유령이 사라지는 것이 아니라 이름을 바꿔 남는다.</b>
 */
@ActiveProfiles("before")
class BeforeOutboxJobTest extends OutboxJobContract {

    @Test
    @DisplayName("롤백된 청크의 알림이 남는다 - 유령 알림")
    void 유령_알림() throws Exception {
        launch(runWithFailure());

        NotificationDeliveryChecksum checksum = reporter.current();
        assertThat(checksum.sendAttempts())
                .as("실패한 청크도 발송을 마친 뒤에 죽는다")
                .isEqualTo(OutboxFixture.FAIL_AFTER + OutboxFixture.phantomCount());
        assertThat(checksum.changedRows())
                .as("그런데 커밋된 상태 변경은 그보다 청크 하나만큼 적다")
                .isEqualTo(OutboxFixture.FAIL_AFTER);
        assertThat(checksum.phantomSends())
                .as("알림은 받았는데 여전히 ACTIVE 인 회원이 정확히 청크 하나만큼 있다")
                .isEqualTo(OutboxFixture.phantomCount());
    }

    @Test
    @DisplayName("재실행하면 유령이 중복이 된다 - 같은 사람이 같은 알림을 두 번 받는다")
    void 재실행은_중복_발송이_된다() throws Exception {
        launch(runWithFailure());

        launch(nextRun());

        NotificationDeliveryChecksum checksum = reporter.current();
        assertThat(checksum.sendAttempts())
                .as("대상은 %d 명인데 알림은 그보다 청크 하나만큼 더 나갔다", OutboxFixture.targetCount())
                .isEqualTo(OutboxFixture.targetCount() + OutboxFixture.phantomCount());
        assertThat(checksum.distinctKeys()).isEqualTo(OutboxFixture.targetCount());
        assertThat(checksum.duplicateSends()).isEqualTo(OutboxFixture.phantomCount());
        assertThat(checksum.phantomSends())
                .as("유령은 사라진 것이 아니라 중복으로 이름을 바꾼 것이다")
                .isZero();
    }

    @Test
    @DisplayName("DB 는 멱등한데 알림은 아니다 - 5번의 처리 표시가 성 밖을 지키지 못한다")
    void DB_는_멱등하다() throws Exception {
        launch(runWithFailure());

        launch(nextRun());

        NotificationDeliveryChecksum checksum = reporter.current();
        assertThat(checksum.changedRows())
                .as("상태를 두 번 바꾼 회원은 한 명도 없다. WHERE 절이 정확히 일했다")
                .isEqualTo(OutboxFixture.targetCount());
        assertThat(checksum.activeRows()).isZero();
        assertThat(checksum.duplicateSends())
                .as("그런데 알림은 중복됐다. 트랜잭션은 자기가 아는 것만 되돌린다")
                .isPositive();
    }

    @Test
    @DisplayName("멱등키를 실어 보내도 중복은 막히지 않는다 - 키는 거절해 주는 쪽이 있을 때만 산다")
    void 키만_붙여서는_막지_못한다() throws Exception {
        launch(runWithFailure());

        launch(nextRun());

        assertThat(recorder.attempts())
                .as("before 의 메시지에도 멱등키가 전부 들어 있다")
                .allSatisfy(attempt -> assertThat(attempt.idempotencyKey())
                        .isEqualTo(NotificationIdempotencyKey.of(attempt.memberId())));
        assertThat(recorder.duplicateCount())
                .as("그런데도 같은 키가 두 번 나갔다. 5번의 'UK 는 쓰는 코드가 있을 때만 산다' 의 바깥 판이다")
                .isEqualTo(OutboxFixture.phantomCount());
    }

    @Test
    @DisplayName("Step 통계에는 아무 이상이 없다 - 증상은 배치 안에서 관측되지 않는다")
    void 통계는_정상이다() throws Exception {
        StepExecution step = step(launch(run()), "outboxStep");

        assertThat(step.getReadCount()).isEqualTo(OutboxFixture.targetCount());
        assertThat(step.getWriteCount()).isEqualTo(OutboxFixture.targetCount());
        assertThat(step.getFilterCount())
                .as("리더의 조건과 프로세서의 판단이 어긋나면 여기가 0 이 아니다")
                .isZero();
    }
}
