package com.h4ndwoong.batchdemo.restart;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.domain.MemberE;
import com.h4ndwoong.batchdemo.domain.MemberGrade;
import com.h4ndwoong.batchdemo.domain.MemberStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link PointExpiryItemProcessor} 단위 테스트.
 *
 * <p>이 프로세서는 before 와 after 가 <b>같은 인스턴스를 공유</b>한다. 그래서 여기서 고정하는 것은
 * "개선 전후로 계산이 달라지지 않았다" 의 근거이기도 하다 — 5번의 before 가 틀린 이유는 계산이
 * 아니다.
 */
class PointExpiryItemProcessorTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final LocalDateTime FIXED_TIME = LocalDateTime.of(2026, 8, 14, 19, 0, 0);
    private static final long AMOUNT = 1_000L;

    private final Clock clock = Clock.fixed(FIXED_TIME.atZone(ZONE).toInstant(), ZONE);
    private final PointExpiryItemProcessor processor = new PointExpiryItemProcessor(AMOUNT, clock);

    @Test
    @DisplayName("정해진 액수만큼 소멸시키고 수정 시각을 남긴다")
    void 소멸() {
        MemberBase member = member(5_000L);

        MemberBase processed = processor.process(member);

        assertThat(processed).isSameAs(member);
        assertThat(processed.getPoint()).isEqualTo(4_000L);
        assertThat(processed.getUpdatedAt())
                .as("주입한 시계의 시각이어야 한다").isEqualTo(FIXED_TIME);
    }

    @Test
    @DisplayName("잔액이 부족해도 예외를 던지지 않는다 - 음수가 된 행이 이중 차감의 물증이다")
    void 잔액_부족() {
        MemberBase member = member(400L);

        assertThatCode(() -> processor.process(member))
                .as("여기서 예외를 던지면 이중 소멸 대신 예외가 나면서 재현하려던 증상이 바뀐다")
                .doesNotThrowAnyException();
        assertThat(member.getPoint()).isEqualTo(-600L);
    }

    @Test
    @DisplayName("null 을 돌려주지 않는다 - 필터가 생기면 READ = WRITE 대사가 깨진다")
    void 필터링하지_않는다() {
        assertThat(processor.process(member(0L))).isNotNull();
    }

    @Test
    @DisplayName("처리 흔적은 남기지 않는다 - 그것은 after 데코레이터의 몫이다")
    void 흔적을_남기지_않는다() {
        MemberBase processed = processor.process(member(5_000L));

        assertThat(processed.isProcessed()).isFalse();
        assertThat(processed.getIdempotencyKey()).isNull();
    }

    private static MemberBase member(long point) {
        return new MemberE(1L, "user1@example.com", "김민준", MemberGrade.BRONZE, point,
                MemberStatus.ACTIVE, null, false, null, FIXED_TIME.minusDays(1), null);
    }
}
