package com.h4ndwoong.batchdemo.skip;

import com.h4ndwoong.batchdemo.domain.MemberB;
import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.domain.MemberGrade;
import com.h4ndwoong.batchdemo.domain.MemberStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MemberValidatingItemProcessor} 단위 테스트.
 *
 * <p>가장 중요한 검증은 <b>{@code null} 을 반환하지 않는다</b>는 것이다. 프로세서가 오염 행에
 * {@code null} 을 돌려주면 그 행은 스킵이 아니라 <em>필터</em>로 집계되어 격리 테이블에 남지 않는다.
 * Step 은 여전히 {@code COMPLETED} 이고 통계도 그럴듯해서, 500건이 사라진 사실을 아무도 모른다.
 */
class MemberValidatingItemProcessorTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final LocalDateTime FIXED_TIME = LocalDateTime.of(2026, 8, 14, 19, 0, 0);

    private final Clock clock = Clock.fixed(FIXED_TIME.atZone(ZONE).toInstant(), ZONE);
    private final MemberValidatingItemProcessor processor =
            new MemberValidatingItemProcessor(new MemberValidator(), clock);

    @Test
    @DisplayName("정상 행은 처리 완료로 표시되어 그대로 반환된다")
    void 정상_가공() {
        MemberBase member = member("user1@example.com", 100L);

        MemberBase processed = processor.process(member);

        assertThat(processed).isSameAs(member);
        assertThat(processed.isProcessed()).isTrue();
        assertThat(processed.getUpdatedAt())
                .as("주입한 시계의 시각이어야 한다").isEqualTo(FIXED_TIME);
    }

    @Test
    @DisplayName("멱등키는 건드리지 않는다 - 5·7번 문제의 주제다")
    void 멱등키_미사용() {
        MemberBase processed = processor.process(member("user1@example.com", 100L));

        assertThat(processed.getIdempotencyKey()).isNull();
    }

    @Test
    @DisplayName("오염 행은 null 이 아니라 예외로 알린다 - 필터가 아니라 스킵으로 집계되어야 한다")
    void 오염_행은_예외() {
        assertThatThrownBy(() -> processor.process(member("invalid-email-200", 100L)))
                .isInstanceOf(MemberValidationException.class);
    }

    @Test
    @DisplayName("검증에 걸린 행은 처리 완료로 표시되지 않는다")
    void 오염_행은_미표시() {
        MemberBase member = member("invalid-email-200", 100L);

        assertThatThrownBy(() -> processor.process(member))
                .isInstanceOf(MemberValidationException.class);

        assertThat(member.isProcessed())
                .as("스킵된 행은 member_b 에 processed = 0 으로 남아 사후 추적이 가능해야 한다").isFalse();
        assertThat(member.getUpdatedAt()).isNull();
    }

    @Test
    @DisplayName("여러 건을 처리해도 시계가 정한 시각으로 일정하다")
    void 시각_고정() throws Exception {
        MemberBase first = processor.process(member("user1@example.com", 1L));
        Thread.sleep(5);
        MemberBase second = processor.process(member("user2@example.com", 2L));

        assertThat(first.getUpdatedAt()).isEqualTo(second.getUpdatedAt());
    }

    private MemberBase member(String email, long point) {
        return new MemberB(200L, email, "김민준", MemberGrade.GOLD, point, MemberStatus.ACTIVE,
                null, false, null, LocalDateTime.of(2026, 1, 1, 0, 0), null);
    }
}
