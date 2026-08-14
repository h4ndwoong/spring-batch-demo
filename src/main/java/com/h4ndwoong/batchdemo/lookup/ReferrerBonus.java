package com.h4ndwoong.batchdemo.lookup;

import com.h4ndwoong.batchdemo.domain.MemberGrade;

/**
 * 추천인 등급이 주는 포인트 보너스. <b>추천인을 조회하는 유일한 이유</b>다.
 *
 * <p>이 표가 있어야 "왜 굳이 추천인을 읽어야 하는가" 가 성립한다. 값이 무엇인지는 4번 문제에서
 * 중요하지 않지만, <b>추천인의 등급 없이는 새 등급을 정할 수 없다</b>는 사실은 중요하다. 그것이
 * 50만 번의 조회를 강제하는 요구사항이고, before/after 는 그 요구를 각각 100만 번의 왕복과
 * 500번의 왕복으로 충족한다.
 *
 * <p>{@link com.h4ndwoong.batchdemo.support.GradePolicy} 와 달리 데이터에서 산출하지 않고 상수로 둔다. 정책 로딩은 이미 한 축을
 * 보여주고 있고, 여기까지 데이터 의존으로 만들면 결과 체크섬이 시드 데이터에 두 겹으로 묶여
 * 무엇이 무엇을 바꿨는지 읽기 어려워진다.
 */
public final class ReferrerBonus {

    private ReferrerBonus() {
    }

    /**
     * 추천인 등급에 해당하는 보너스 포인트.
     *
     * @param grade 추천인 등급
     * @return 보너스. 추천인이 없으면 {@link #none()} 을 쓴다
     */
    public static long of(MemberGrade grade) {
        return switch (grade) {
            case VIP -> 10_000L;
            case GOLD -> 5_000L;
            case SILVER -> 2_000L;
            case BRONZE -> 0L;
        };
    }

    /**
     * 추천인이 없는 행의 보너스.
     *
     * @return 항상 0
     */
    public static long none() {
        return 0L;
    }
}
