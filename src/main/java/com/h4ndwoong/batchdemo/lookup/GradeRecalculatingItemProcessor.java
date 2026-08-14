package com.h4ndwoong.batchdemo.lookup;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.support.GradePolicy;
import org.springframework.batch.item.ItemProcessor;

/**
 * 추천인 보너스를 반영해 등급을 재산정한다. <b>before 와 after 가 문자 그대로 공유하는</b> 가공이다.
 *
 * <p>이 클래스가 4번 문제의 핵심을 보여준다 — <b>N+1 은 여기 있지 않다.</b> 코드는 조회를 한 번
 * 요청할 뿐이고, 그 요청이 SELECT 두 번이 될지({@link PerItemReferrerLookup}) 청크당 한 번이
 * 될지({@link ChunkedReferrerLookup}) 는 주입된 전략이 정한다. 그래서 프로파일을 바꿔도 이
 * 파일은 바뀌지 않고, 성능 차이의 원인을 조회 전략 하나로 귀속시킬 수 있다.
 *
 * <p><b>{@code null} 을 반환하지 않는다.</b> 2번 문제에서 확립한 규칙이다 — {@code null} 은 필터로
 * 집계되어 {@code FILTER_COUNT} 로 가고, 그러면 {@code READ = WRITE} 가 깨져 "양쪽이 같은 건수를
 * 처리했다" 를 확인할 수 없다.
 *
 * <p><b>{@link MemberBase} 를 변형하지 않는다.</b> 산정 결과는 {@link GradeDecision} 이라는 값으로
 * 나가고 DB 에는 아무것도 쓰지 않는다. 쓰기 경로는 6번 문제의 주제이며, 여기에 행당 1회 UPDATE 가
 * 얹히면 조회 왕복의 차이(1,000배)가 그 비용에 묻힌다.
 */
public class GradeRecalculatingItemProcessor implements ItemProcessor<MemberBase, GradeDecision> {

    private final ReferrerLookup referrerLookup;
    private final GradePolicy gradePolicy;

    /**
     * 프로세서를 만든다.
     *
     * @param referrerLookup 추천인 조회 전략. <b>이것이 before/after 의 차이 전부</b>다
     * @param gradePolicy    등급 정책. Step 시작 시 1회 로딩된 값이며 양쪽이 같다
     */
    public GradeRecalculatingItemProcessor(ReferrerLookup referrerLookup, GradePolicy gradePolicy) {
        this.referrerLookup = referrerLookup;
        this.gradePolicy = gradePolicy;
    }

    /**
     * {@inheritDoc}
     *
     * <p>추천인이 없거나({@code id=1}) 조회되지 않으면 보너스 0으로 계속한다. 예외를 던지지 않는
     * 이유는 {@link ReferrerLookup} 의 계약에 적었다.
     *
     * @param item 읽어들인 회원
     * @return 등급 재산정 결과. 절대 {@code null} 이 아니다
     */
    @Override
    public GradeDecision process(MemberBase item) {
        long bonus = referrerLookup.find(item.getReferrerId())
                .map(referrer -> ReferrerBonus.of(referrer.grade()))
                .orElseGet(ReferrerBonus::none);

        long effectivePoint = item.getPoint() + bonus;
        return new GradeDecision(item.getId(), item.getGrade(),
                gradePolicy.gradeOf(effectivePoint), effectivePoint);
    }
}
