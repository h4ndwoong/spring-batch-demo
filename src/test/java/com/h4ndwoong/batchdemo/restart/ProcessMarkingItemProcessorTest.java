package com.h4ndwoong.batchdemo.restart;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.domain.MemberE;
import com.h4ndwoong.batchdemo.domain.MemberGrade;
import com.h4ndwoong.batchdemo.domain.MemberStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.ItemProcessor;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ProcessMarkingItemProcessor} 단위 테스트.
 *
 * <p>이 데코레이터가 5번 개선의 본체다. 여기서 고정하는 것은 두 가지다 — <b>가공된 행에는 흔적을
 * 반드시 남긴다</b>, 그리고 <b>걸러진 행에는 절대 남기지 않는다</b>. 후자를 어기면 처리되지 않은
 * 행이 처리된 것으로 표시되어 다음 실행이 영원히 건너뛴다. 배치는 성공으로 끝나고 그 행만 조용히
 * 누락된다.
 */
class ProcessMarkingItemProcessorTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final LocalDateTime FIXED_TIME = LocalDateTime.of(2026, 8, 14, 19, 0, 0);

    private final Clock clock = Clock.fixed(FIXED_TIME.atZone(ZONE).toInstant(), ZONE);

    @Test
    @DisplayName("위임 후 처리 표시와 멱등키를 세운다")
    void 흔적을_남긴다() throws Exception {
        ProcessMarkingItemProcessor processor =
                new ProcessMarkingItemProcessor(new PointExpiryItemProcessor(1_000L, clock), clock);
        MemberBase member = member(5_000L);

        MemberBase processed = processor.process(member);

        assertThat(processed).isSameAs(member);
        assertThat(processed.getPoint())
                .as("계산은 위임 대상이 그대로 한다").isEqualTo(4_000L);
        assertThat(processed.isProcessed()).isTrue();
        assertThat(processed.getIdempotencyKey()).isEqualTo(ExpiryIdempotencyKey.of(1L));
        assertThat(processed.getUpdatedAt()).isEqualTo(FIXED_TIME);
    }

    @Test
    @DisplayName("위임 대상이 걸러낸 행에는 흔적을 남기지 않는다 - 남기면 다음 실행이 영원히 건너뛴다")
    void 걸러진_행() throws Exception {
        ItemProcessor<MemberBase, MemberBase> filtering = item -> null;
        ProcessMarkingItemProcessor processor = new ProcessMarkingItemProcessor(filtering, clock);
        MemberBase member = member(5_000L);

        assertThat(processor.process(member)).isNull();
        assertThat(member.isProcessed()).isFalse();
        assertThat(member.getIdempotencyKey()).isNull();
    }

    @Test
    @DisplayName("위임 대상의 예외는 그대로 올린다 - 실패한 행에 흔적이 남으면 안 된다")
    void 위임_예외() {
        ItemProcessor<MemberBase, MemberBase> failing = item -> {
            throw new IllegalStateException("가공 실패");
        };
        ProcessMarkingItemProcessor processor = new ProcessMarkingItemProcessor(failing, clock);
        MemberBase member = member(5_000L);

        assertThatThrownBy(() -> processor.process(member))
                .isInstanceOf(IllegalStateException.class);
        assertThat(member.isProcessed()).isFalse();
    }

    private static MemberBase member(long point) {
        return new MemberE(1L, "user1@example.com", "김민준", MemberGrade.BRONZE, point,
                MemberStatus.ACTIVE, null, false, null, FIXED_TIME.minusDays(1), null);
    }
}
