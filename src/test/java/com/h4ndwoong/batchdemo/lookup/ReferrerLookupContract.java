package com.h4ndwoong.batchdemo.lookup;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.domain.MemberD;
import com.h4ndwoong.batchdemo.support.MemberTableSeeder;
import com.h4ndwoong.batchdemo.support.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 두 조회 전략이 <b>똑같이 지켜야 하는 계약</b>. 행별이든 청크 일괄이든 이 시험을 통과해야 한다.
 *
 * <p>4번 문제에서 조회를 묶는 것은 성능 개선이지만, 조회 방식을 바꿀 때 가장 먼저 깨지는 것은
 * 성능이 아니라 <b>정확성</b>이다. {@code IN} 으로 받아온 결과를 잘못 맞추면 등급이 조용히 틀린 채
 * 배치는 성공한다. 그래서 계약을 한곳에 두고 두 구현에 똑같이 적용한다 — 한쪽에만 있는 시험은
 * 비교를 보장하지 못한다.
 *
 * <p>계약은 {@link ReferrerLookup} 에 적힌 넷이다.
 */
@SpringBootTest(properties = {
        TestDatabase.URL,
        TestDatabase.BATCH_SCHEMA,
        TestDatabase.DOMAIN_SCHEMA
})
abstract class ReferrerLookupContract {

    /** 조회 대상 건수. 계약 확인에는 작은 표본으로 충분하다. */
    protected static final long COUNT = 50L;

    /** 어떤 시딩 건수보다도 큰 식별자. "없는 행" 을 확실히 만든다. */
    private static final long MISSING_ID = 999_999L;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    /**
     * 시험할 조회기를 만든다.
     *
     * @param jdbcTemplate JDBC 템플릿
     * @return 조회기
     */
    protected abstract ReferrerLookup lookup(JdbcTemplate jdbcTemplate);

    /**
     * 조회기에게 "이 행들을 곧 물어보겠다" 고 알린다. 청크 전략만 이 정보를 쓴다.
     *
     * @param lookup  조회기
     * @param members 곧 가공될 행들
     */
    protected abstract void announce(ReferrerLookup lookup, List<MemberBase> members);

    @BeforeEach
    void 데이터를_채운다() {
        MemberTableSeeder.seed(jdbcTemplate, "member_d", MemberD::new, COUNT, 0, true);
    }

    @AfterEach
    void 실습_테이블을_비운다() {
        jdbcTemplate.execute("TRUNCATE TABLE member_d");
    }

    @Test
    @DisplayName("존재하는 추천인을 등급까지 정확히 돌려준다")
    void 정상_조회() {
        List<MemberBase> members = members();
        ReferrerLookup lookup = prepared(members);

        for (MemberBase member : members) {
            if (member.getReferrerId() == null) {
                continue;
            }
            Optional<Referrer> referrer = lookup.find(member.getReferrerId());

            assertThat(referrer).as("id=%d 의 추천인", member.getId()).isPresent();
            assertThat(referrer.get().id()).isEqualTo(member.getReferrerId());
            assertThat(referrer.get().grade())
                    .as("조회를 묶다가 다른 행의 등급을 붙이면 여기서 걸린다")
                    .isEqualTo(members.get(member.getReferrerId().intValue() - 1).getGrade());
        }
    }

    @Test
    @DisplayName("추천인이 없으면(null) 빈 값이다 - 오류가 아니다")
    void 추천인_없음() {
        ReferrerLookup lookup = prepared(members());

        assertThat(lookup.find(null)).isEmpty();
        assertThat(lookup.stats().lookups()).as("조회 요구로 세지도 않는다").isZero();
        assertThat(lookup.stats().queries()).as("DB 에 가지 않는다").isZero();
    }

    @Test
    @DisplayName("존재하지 않는 id 는 빈 값이다 - 예외를 던지지 않는다")
    void 없는_추천인() {
        ReferrerLookup lookup = lookup(jdbcTemplate);

        assertThat(lookup.find(MISSING_ID))
                .as("여기서 예외가 나면 4번 문제가 2번 문제(오류 행 처리)로 바뀐다").isEmpty();
    }

    @Test
    @DisplayName("같은 id 를 여러 번 물어도 같은 답을 준다")
    void 반복_조회() {
        ReferrerLookup lookup = prepared(members());

        Optional<Referrer> first = lookup.find(2L);
        Optional<Referrer> second = lookup.find(2L);
        Optional<Referrer> third = lookup.find(2L);

        assertThat(first).isPresent();
        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
    }

    @Test
    @DisplayName("reset 하면 계측치가 0으로 돌아간다")
    void 계측치_초기화() {
        ReferrerLookup lookup = prepared(members());
        lookup.find(2L);
        assertThat(lookup.stats().lookups()).isPositive();

        lookup.reset();

        assertThat(lookup.stats()).isEqualTo(ReferrerLookupStats.EMPTY);
    }

    /**
     * 시딩과 같은 데이터. {@code index - 1} 번째 원소가 {@code id = index} 인 행이다.
     *
     * @return 회원 목록
     */
    protected List<MemberBase> members() {
        return java.util.stream.LongStream.rangeClosed(1, COUNT)
                .mapToObj(MemberTableSeeder.generator(MemberD::new, 0, true)::generate)
                .toList();
    }

    private ReferrerLookup prepared(List<MemberBase> members) {
        ReferrerLookup lookup = lookup(jdbcTemplate);
        announce(lookup, members);
        return lookup;
    }
}
