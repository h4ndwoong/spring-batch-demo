package com.h4ndwoong.batchdemo.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link NotificationRecorder} 단위 테스트.
 *
 * <p>이 기록기가 7번의 눈금이다. 눈금이 틀리면 개선도 증상도 증명할 수 없으므로, <b>중복을 세는
 * 방식</b>과 <b>비우지 않는다는 성질</b>을 여기서 고정한다.
 */
class NotificationRecorderTest {

    private final NotificationRecorder recorder = new NotificationRecorder();

    @Test
    @DisplayName("서로 다른 회원에게 보내면 중복이 0이다")
    void 중복_없음() {
        recorder.record(message(1L));
        recorder.record(message(2L));

        assertThat(recorder.attemptCount()).isEqualTo(2);
        assertThat(recorder.distinctKeyCount()).isEqualTo(2);
        assertThat(recorder.duplicateCount()).isZero();
    }

    @Test
    @DisplayName("같은 키가 두 번 나가면 중복 1건이다 - 7번의 결승선")
    void 중복_집계() {
        recorder.record(message(1L));
        recorder.record(message(2L));
        recorder.record(message(1L));

        assertThat(recorder.attemptCount()).isEqualTo(3);
        assertThat(recorder.distinctKeyCount())
                .as("실제로 알림을 받은 사람은 둘이다")
                .isEqualTo(2);
        assertThat(recorder.duplicateCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("발송 순서를 유지한다 - 릴레이가 id 순으로 보냈는지 확인하는 근거")
    void 순서() {
        recorder.record(message(3L));
        recorder.record(message(1L));
        recorder.record(message(2L));

        assertThat(recorder.attempts())
                .extracting(SendAttempt::memberId)
                .containsExactly(3L, 1L, 2L);
    }

    @Test
    @DisplayName("스스로 비우지 않는다 - 중복 발송은 실행을 가로질러야만 보인다")
    void 비우지_않는다() {
        recorder.record(message(1L));
        recorder.record(message(1L));

        assertThat(recorder.duplicateCount())
                .as("Step 마다 비우면 두 실행에 걸친 중복이 사라진다")
                .isEqualTo(1);

        recorder.reset();

        assertThat(recorder.attemptCount()).isZero();
    }

    private static NotificationMessage message(long memberId) {
        return new NotificationMessage(memberId, StatusChangedNotification.EVENT_TYPE,
                "{\"memberId\":%d}".formatted(memberId),
                NotificationIdempotencyKey.of(memberId),
                LocalDateTime.of(2026, 1, 1, 12, 0));
    }
}
