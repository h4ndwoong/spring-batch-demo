package com.h4ndwoong.batchdemo.lookup;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.domain.MemberD;
import com.h4ndwoong.batchdemo.domain.MemberGrade;
import com.h4ndwoong.batchdemo.domain.MemberStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 가공({@link GradeRecalculatingItemProcessor})이 조회 전략과 무관하게 같은 답을 내는지 확인한다.
 *
 * <p>DB 를 쓰지 않는다. 조회 전략을 <b>가짜로 갈아 끼워도 결과가 같다</b>는 것이 4번 문제의 구조적
 * 주장이고, 그 주장을 확인하는 데 실제 DB 는 필요 없다.
 */
class GradeRecalculatingItemProcessorTest {

    private static final GradePolicy POLICY = new GradePolicy(1_000, 5_000, 10_000);

    @Test
    @DisplayName("추천인 등급 보너스를 더해 등급을 재산정한다")
    void 보너스를_반영한다() {
        GradeRecalculatingItemProcessor processor = processor(Map.of(7L, MemberGrade.VIP));

        GradeDecision decision = processor.process(member(1L, 7L, 500, MemberGrade.BRONZE));

        assertThat(decision.effectivePoint())
                .as("보유 500 + VIP 추천인 보너스 %d", ReferrerBonus.of(MemberGrade.VIP))
                .isEqualTo(500 + ReferrerBonus.of(MemberGrade.VIP));
        assertThat(decision.newGrade()).isEqualTo(MemberGrade.VIP);
        assertThat(decision.changed()).isTrue();
    }

    @Test
    @DisplayName("추천인이 없으면 보너스 없이 산정한다 - 예외가 아니다")
    void 추천인_없음() {
        GradeRecalculatingItemProcessor processor = processor(Map.of());

        GradeDecision decision = processor.process(member(1L, null, 500, MemberGrade.BRONZE));

        assertThat(decision.effectivePoint()).isEqualTo(500);
        assertThat(decision.newGrade()).isEqualTo(MemberGrade.BRONZE);
        assertThat(decision.changed()).isFalse();
    }

    @Test
    @DisplayName("추천인이 조회되지 않아도 보너스 없이 계속한다 - 오류 처리는 2번 문제의 주제다")
    void 조회되지_않는_추천인() {
        GradeRecalculatingItemProcessor processor = processor(Map.of());

        GradeDecision decision = processor.process(member(1L, 999L, 500, MemberGrade.BRONZE));

        assertThat(decision.effectivePoint()).isEqualTo(500);
    }

    @Test
    @DisplayName("null 을 돌려주지 않는다 - 필터로 새면 READ = WRITE 대사가 깨진다")
    void null_을_돌려주지_않는다() {
        GradeRecalculatingItemProcessor processor = processor(Map.of());

        assertThat(processor.process(member(1L, null, 0, MemberGrade.BRONZE))).isNotNull();
    }

    @Test
    @DisplayName("읽어들인 회원을 변형하지 않는다 - 이 Job 은 아무것도 쓰지 않는다")
    void 원본을_건드리지_않는다() {
        GradeRecalculatingItemProcessor processor = processor(Map.of(7L, MemberGrade.VIP));
        MemberBase member = member(1L, 7L, 500, MemberGrade.BRONZE);

        processor.process(member);

        assertThat(member.getGrade()).isEqualTo(MemberGrade.BRONZE);
        assertThat(member.getPoint()).isEqualTo(500);
        assertThat(member.getUpdatedAt()).isNull();
        assertThat(member.isProcessed()).isFalse();
    }

    @Test
    @DisplayName("조회 전략이 달라도 같은 답을 낸다 - 개선의 원인을 조회 방식 하나로 귀속시킬 수 있는 근거")
    void 전략과_무관하게_같은_답() {
        MemberBase member = member(1L, 7L, 500, MemberGrade.BRONZE);
        Map<Long, MemberGrade> referrers = Map.of(7L, MemberGrade.GOLD);

        GradeDecision one = processor(referrers).process(member);
        GradeDecision another = new GradeRecalculatingItemProcessor(
                new CountingFakeLookup(referrers), POLICY).process(member);

        assertThat(one).isEqualTo(another);
    }

    private static GradeRecalculatingItemProcessor processor(Map<Long, MemberGrade> referrers) {
        return new GradeRecalculatingItemProcessor(new FakeLookup(referrers), POLICY);
    }

    private static MemberBase member(long id, Long referrerId, long point, MemberGrade grade) {
        return new MemberD(id, "user" + id + "@example.com", "이름", grade, point,
                MemberStatus.ACTIVE, referrerId, false, null, LocalDateTime.now(), null);
    }

    /** 요구받은 대로 답하는 가짜 조회기. */
    private static class FakeLookup implements ReferrerLookup {

        protected final Map<Long, MemberGrade> referrers;

        private long lookups;

        FakeLookup(Map<Long, MemberGrade> referrers) {
            this.referrers = new HashMap<>(referrers);
        }

        @Override
        public Optional<Referrer> find(Long referrerId) {
            if (referrerId == null) {
                return Optional.empty();
            }
            lookups++;
            return Optional.ofNullable(referrers.get(referrerId))
                    .map(grade -> new Referrer(referrerId, grade));
        }

        @Override
        public ReferrerLookupStats stats() {
            return new ReferrerLookupStats(lookups, lookups, 0);
        }

        @Override
        public void reset() {
            lookups = 0;
        }
    }

    /** 답은 같고 왕복만 다른 또 하나의 가짜. "묶는 방식은 답을 바꾸지 않는다" 를 시험하기 위한 것이다. */
    private static class CountingFakeLookup extends FakeLookup {

        CountingFakeLookup(Map<Long, MemberGrade> referrers) {
            super(referrers);
        }

        @Override
        public ReferrerLookupStats stats() {
            return new ReferrerLookupStats(referrers.size(), 1, 0);
        }
    }
}
