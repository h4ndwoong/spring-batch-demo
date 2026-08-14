package com.h4ndwoong.batchdemo.lookup;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.domain.MemberD;
import com.h4ndwoong.batchdemo.domain.MemberGrade;
import com.h4ndwoong.batchdemo.domain.MemberStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 청크 일괄 조회기({@link ChunkedReferrerLookup})가 계약을 지키는지, 그리고 <b>청크당 한 번만
 * 왕복하는지</b> 확인한다.
 *
 * <p>계약 시험은 상속받고, 여기에는 after 의 성질과 그 <b>전제가 깨졌을 때의 행동</b>을 남긴다.
 * 후자가 중요하다 — 일괄 조회는 빨라지는 대신 "읽기가 끝난 뒤에 가공한다" 는 프레임워크의 순서에
 * 기대는데, 그 기대가 깨졌을 때 조용히 틀린 답을 주면 개선이 아니라 사고다.
 */
class ChunkedReferrerLookupTest extends ReferrerLookupContract {

    @Override
    protected ReferrerLookup lookup(JdbcTemplate jdbcTemplate) {
        return new ChunkedReferrerLookup(jdbcTemplate);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Step 이 청크를 읽는 동안 일어나는 일을 흉내 낸다. 실행 환경에서는
     * {@code ItemReadListener.afterRead} 가 프레임워크에 의해 불린다.
     */
    @Override
    protected void announce(ReferrerLookup lookup, List<MemberBase> members) {
        ChunkedReferrerLookup chunked = (ChunkedReferrerLookup) lookup;
        chunked.beforeChunk(null);
        members.forEach(chunked::afterRead);
    }

    @Test
    @DisplayName("청크 전체를 SELECT 한 번으로 가져온다")
    void 청크당_한_번_왕복() {
        List<MemberBase> members = members();
        ChunkedReferrerLookup lookup = (ChunkedReferrerLookup) lookup(jdbcTemplate);
        announce(lookup, members);

        members.forEach(member -> lookup.find(member.getReferrerId()));

        assertThat(lookup.stats().lookups()).isEqualTo(COUNT - 1);
        assertThat(lookup.stats().queries())
                .as("%d번의 조회 요구를 한 번의 왕복으로 답했다", COUNT - 1).isEqualTo(1);
    }

    @Test
    @DisplayName("청크 안에서 같은 추천인은 한 번만 조회한다")
    void 중복을_아낀다() {
        ChunkedReferrerLookup lookup = (ChunkedReferrerLookup) lookup(jdbcTemplate);
        List<MemberBase> sameReferrer = List.of(member(10L, 2L), member(11L, 2L), member(12L, 3L));
        announce(lookup, sameReferrer);

        sameReferrer.forEach(member -> lookup.find(member.getReferrerId()));

        assertThat(lookup.stats().queries()).isEqualTo(1);
        assertThat(lookup.stats().deduplicated())
                .as("추천인 2번을 두 행이 가리켰으므로 한 번을 아꼈다").isEqualTo(1);
    }

    @Test
    @DisplayName("추천인이 하나도 없는 청크에서는 쿼리를 보내지 않는다")
    void 조회할_것이_없으면_가지_않는다() {
        ChunkedReferrerLookup lookup = (ChunkedReferrerLookup) lookup(jdbcTemplate);
        announce(lookup, List.of(member(10L, null), member(11L, null)));

        assertThat(lookup.find(null)).isEmpty();
        assertThat(lookup.stats().queries()).isZero();
    }

    @Test
    @DisplayName("청크가 바뀌면 이전 캐시를 쓰지 않는다 - 청크당 1회를 유지한다")
    void 청크_경계에서_캐시를_버린다() {
        ChunkedReferrerLookup lookup = (ChunkedReferrerLookup) lookup(jdbcTemplate);

        announce(lookup, List.of(member(10L, 2L)));
        lookup.find(2L);
        announce(lookup, List.of(member(20L, 2L)));
        lookup.find(2L);

        assertThat(lookup.stats().queries())
                .as("청크 2개 × 1회. 캐시를 청크 너머로 들고 가면 '청크당 1회' 라는 주장이 흐려진다")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("모아둔 적 없는 id 를 물으면 그 자리에서 조회한다 - 조용히 틀리지 않는다")
    void 계약이_깨져도_틀린_답을_주지_않는다() {
        ChunkedReferrerLookup lookup = (ChunkedReferrerLookup) lookup(jdbcTemplate);
        lookup.beforeChunk(null);

        assertThat(lookup.find(2L))
                .as("afterRead 를 거치지 않은 조회. 빈 값을 돌려주면 등급이 조용히 달라진다")
                .isPresent();
        assertThat(lookup.stats().queries()).isEqualTo(1);
    }

    private static MemberBase member(long id, Long referrerId) {
        return new MemberD(id, "user" + id + "@example.com", "이름", MemberGrade.BRONZE, 0,
                MemberStatus.ACTIVE, referrerId, false, null, LocalDateTime.now(), null);
    }
}
