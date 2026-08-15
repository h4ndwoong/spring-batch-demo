package com.h4ndwoong.batchdemo.outbox;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.domain.MemberG;
import com.h4ndwoong.batchdemo.domain.MemberGrade;
import com.h4ndwoong.batchdemo.domain.MemberStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StatusTransitionItemProcessor} 단위 테스트.
 *
 * <p>7번에서 프로세서는 비교 축이 아니지만, 여기서 정한 {@code updatedAt} 이 그대로 <b>알림의
 * 시각</b>이 된다. 시각의 출처가 하나여야 before 와 after 가 같은 메시지를 만든다.
 */
class StatusTransitionItemProcessorTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 1, 1, 12, 0);

    private final StatusTransitionItemProcessor processor = new StatusTransitionItemProcessor(
            MemberStatus.ACTIVE, MemberStatus.DORMANT, fixedClock());

    @Test
    @DisplayName("대상 상태를 전이시키고 수정 시각을 남긴다")
    void 전이() {
        MemberBase processed = processor.process(member(MemberStatus.ACTIVE));

        assertThat(processed).isNotNull();
        assertThat(processed.getStatus()).isEqualTo(MemberStatus.DORMANT);
        assertThat(processed.getUpdatedAt())
                .as("이 시각이 그대로 알림의 시각이 된다")
                .isEqualTo(NOW);
    }

    @Test
    @DisplayName("대상이 아니면 걸러낸다 - 리더의 조건을 프로세서가 검산한다")
    void 대상이_아니면_null() {
        assertThat(processor.process(member(MemberStatus.DORMANT))).isNull();
        assertThat(processor.process(member(MemberStatus.WITHDRAWN))).isNull();
    }

    @Test
    @DisplayName("전이된 회원으로 알림을 만들 수 있다 - 프로세서와 메시지 팩토리의 접점")
    void 전이_후_알림() {
        MemberBase processed = processor.process(member(MemberStatus.ACTIVE));

        NotificationMessage message = StatusChangedNotification.of(processed);

        assertThat(message.createdAt()).isEqualTo(NOW);
        assertThat(message.payload()).contains("\"status\":\"DORMANT\"");
    }

    private static MemberBase member(MemberStatus status) {
        return new MemberG(1L, "user1@example.com", "김민준", MemberGrade.BRONZE,
                1_000L, status, null, false, null, LocalDateTime.of(2026, 1, 1, 0, 0), null);
    }

    private static Clock fixedClock() {
        ZoneId zone = ZoneId.systemDefault();
        return Clock.fixed(NOW.atZone(zone).toInstant(), zone);
    }
}
