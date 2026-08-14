package com.h4ndwoong.batchdemo.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link FaultInjectingNotificationSender} 단위 테스트.
 *
 * <p>이 장치는 after 의 한계 — Outbox 릴레이가 exactly-once 가 아니라 at-least-once 라는 것 — 를
 * 드러내기 위해 있다. 그러려면 <b>정확히 한 번만, 정해진 지점에서</b> 실패해야 한다. 계속 실패하면
 * 재발송을 관찰할 수 없고, 실패 지점이 흔들리면 중복 건수를 계산으로 예측할 수 없다.
 */
class FaultInjectingNotificationSenderTest {

    private final NotificationRecorder recorder = new NotificationRecorder();
    private final NotificationSender delegate = recorder::record;

    @Test
    @DisplayName("지정한 건수까지는 그대로 위임한다")
    void 지정_건수까지_위임() {
        FaultInjectingNotificationSender sender = new FaultInjectingNotificationSender(delegate, 3L, 1);

        sender.send(message(1L));
        sender.send(message(2L));
        sender.send(message(3L));

        assertThat(recorder.attemptCount()).isEqualTo(3);
        assertThat(sender.thrownCount()).isZero();
    }

    @Test
    @DisplayName("실패한 발송은 나가지 않는다 - 피해는 그보다 먼저 나간 건들이다")
    void 실패한_건은_나가지_않는다() {
        FaultInjectingNotificationSender sender = new FaultInjectingNotificationSender(delegate, 2L, 1);
        sender.send(message(1L));
        sender.send(message(2L));

        assertThatThrownBy(() -> sender.send(message(3L)))
                .isInstanceOf(NotificationException.class);

        assertThat(recorder.attemptCount())
                .as("이미 나간 2건이 그 청크의 재발송에서 중복이 된다")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("정해진 횟수만 실패하고 그 뒤로는 정상 발송한다 - 재발송을 관찰하려면 회복돼야 한다")
    void 한_번만_실패한다() {
        FaultInjectingNotificationSender sender = new FaultInjectingNotificationSender(delegate, 1L, 1);
        sender.send(message(1L));

        assertThatThrownBy(() -> sender.send(message(2L))).isInstanceOf(NotificationException.class);
        assertThatCode(() -> sender.send(message(2L)))
                .as("다음 실행이 같은 자리에서 또 실패하면 영원히 끝나지 않는다")
                .doesNotThrowAnyException();

        assertThat(sender.thrownCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("0 이하면 절대 실패시키지 않는다 - 기본 실행 경로다")
    void 장애_없음() {
        FaultInjectingNotificationSender sender = new FaultInjectingNotificationSender(delegate, 0L, 1);

        assertThatCode(() -> {
            for (long id = 1; id <= 50; id++) {
                sender.send(message(id));
            }
        }).doesNotThrowAnyException();

        assertThat(recorder.attemptCount()).isEqualTo(50);
    }

    private static NotificationMessage message(long memberId) {
        return new NotificationMessage(memberId, StatusChangedNotification.EVENT_TYPE,
                "{\"memberId\":%d}".formatted(memberId),
                NotificationIdempotencyKey.of(memberId),
                LocalDateTime.of(2026, 1, 1, 12, 0));
    }
}
