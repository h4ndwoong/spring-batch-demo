package com.h4ndwoong.batchdemo.lookup;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 행별 조회기({@link PerItemReferrerLookup})가 계약을 지키는지, 그리고 <b>행마다 두 번 왕복하는지</b>
 * 확인한다.
 *
 * <p>계약 시험은 상속받고, 여기에는 before 의 증상만 남긴다.
 */
class PerItemReferrerLookupTest extends ReferrerLookupContract {

    @Override
    protected ReferrerLookup lookup(JdbcTemplate jdbcTemplate) {
        return new PerItemReferrerLookup(jdbcTemplate);
    }

    /**
     * {@inheritDoc}
     *
     * <p>행별 조회기는 <b>앞으로 무엇을 물을지 알 필요가 없다.</b> 그것이 N+1 의 원인이며, 이
     * 메서드가 비어 있다는 사실이 before/after 의 구조적 차이를 그대로 보여준다.
     */
    @Override
    protected void announce(ReferrerLookup lookup, List<MemberBase> members) {
    }

    @Test
    @DisplayName("조회 한 건에 SELECT 두 번을 보낸다 - N+1 의 정의")
    void 조회당_두_번_왕복() {
        ReferrerLookup lookup = lookup(jdbcTemplate);

        lookup.find(2L);

        assertThat(lookup.stats().lookups()).isEqualTo(1);
        assertThat(lookup.stats().queries())
                .as("추천인 조회 1회 + 등급 확인 1회. 두 조회가 겹친다는 것이 증상이다").isEqualTo(2);
    }

    @Test
    @DisplayName("같은 추천인을 여러 행이 가리켜도 매번 다시 조회한다 - 청크를 모른다")
    void 중복을_아끼지_못한다() {
        ReferrerLookup lookup = lookup(jdbcTemplate);

        lookup.find(2L);
        lookup.find(2L);
        lookup.find(2L);

        assertThat(lookup.stats().queries()).isEqualTo(6);
        assertThat(lookup.stats().deduplicated()).isZero();
    }

    @Test
    @DisplayName("없는 추천인은 첫 조회에서 끝난다 - 두 번째 왕복은 보내지 않는다")
    void 없는_추천인은_한_번만_묻는다() {
        ReferrerLookup lookup = lookup(jdbcTemplate);

        lookup.find(999_999L);

        assertThat(lookup.stats().queries()).isEqualTo(1);
    }
}
