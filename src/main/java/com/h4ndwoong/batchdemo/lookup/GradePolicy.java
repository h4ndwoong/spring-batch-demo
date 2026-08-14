package com.h4ndwoong.batchdemo.lookup;

import com.h4ndwoong.batchdemo.domain.MemberGrade;

/**
 * 포인트로 등급을 정하는 정책. <b>순수 함수이며 DB 를 모른다.</b>
 *
 * <p>{@link MemberGrade} 에 이 규칙을 넣지 않은 이유는 그 enum 의 Javadoc 에 적혀 있다 — 정책이
 * 상수에 박혀 있으면 "Step 시작 시 1회 로딩" 이라는 4번 문제의 개선안이 성립하지 않는다.
 * 정책 <em>값</em>이 데이터에서 나오고({@link GradePolicyLoader}) 정책 <em>적용</em>이 여기서 끝나는
 * 구조라야, 정책이 설정 파일이나 정책 테이블로 바뀌어도 프로세서는 그대로다.
 *
 * <p><b>이 정책은 before/after 가 똑같이 쓴다.</b> 4번의 비교 축은 조회 방식 하나뿐이고, 산정
 * 규칙이 조금이라도 다르면 결과 체크섬이 갈라져 "같은 일을 더 적은 왕복으로 했다" 가 성립하지 않는다.
 *
 * @param silverFrom 이 포인트부터 {@code SILVER}
 * @param goldFrom   이 포인트부터 {@code GOLD}
 * @param vipFrom    이 포인트부터 {@code VIP}
 */
public record GradePolicy(long silverFrom, long goldFrom, long vipFrom) {

    /**
     * 정책을 만든다.
     *
     * @throws IllegalArgumentException 임계값이 오름차순이 아닐 때. 순서가 뒤집히면 어떤 등급은
     *                                  도달할 수 없게 되는데, 그것은 정책이 아니라 결함이다
     */
    public GradePolicy {
        if (silverFrom > goldFrom || goldFrom > vipFrom) {
            throw new IllegalArgumentException(
                    "등급 임계값은 오름차순이어야 한다: silver=%d, gold=%d, vip=%d"
                            .formatted(silverFrom, goldFrom, vipFrom));
        }
    }

    /**
     * 포인트 분포의 사분위로 정책을 만든다.
     *
     * <p>임계값을 상수로 박지 않고 <b>데이터에서 산출</b>하는 이유는, 그래야 "Step 시작 시 정책을
     * 로딩한다" 는 동작이 실재하기 때문이다. 상수라면 로딩할 것이 없어 4번의 개선안 한 축이
     * 사라진다.
     *
     * <p>{@code min == max} 인 축퇴 상황(모든 회원의 포인트가 같다)에서는 세 임계값이 같아져
     * 전원이 {@code VIP} 가 된다. 정책으로서는 무의미하지만 <b>결정론적</b>이므로 before/after 비교는
     * 여전히 성립한다. 예외를 던지지 않는 이유가 그것이다.
     *
     * @param minPoint 최소 포인트
     * @param maxPoint 최대 포인트
     * @return 사분위 정책
     * @throws IllegalArgumentException {@code maxPoint} 가 {@code minPoint} 보다 작을 때
     */
    public static GradePolicy ofRange(long minPoint, long maxPoint) {
        if (maxPoint < minPoint) {
            throw new IllegalArgumentException(
                    "최대 포인트가 최소 포인트보다 작다: min=%d, max=%d".formatted(minPoint, maxPoint));
        }
        long span = maxPoint - minPoint;
        return new GradePolicy(minPoint + span / 4, minPoint + span / 2, minPoint + span * 3 / 4);
    }

    /**
     * 포인트에 해당하는 등급.
     *
     * <p>임계값에 <b>정확히 걸치면 상위 등급</b>이다 ({@code point >= vipFrom} → {@code VIP}).
     * 경계가 어느 쪽에 속하는지는 취향의 문제이지만, 한쪽으로 정해 두지 않으면 before/after 가
     * 같은 코드를 쓰고도 다른 결과를 낼 여지가 생긴다.
     *
     * @param point 포인트. 음수도 허용한다 (검증은 2번 문제의 주제다)
     * @return 등급
     */
    public MemberGrade gradeOf(long point) {
        if (point >= vipFrom) {
            return MemberGrade.VIP;
        }
        if (point >= goldFrom) {
            return MemberGrade.GOLD;
        }
        if (point >= silverFrom) {
            return MemberGrade.SILVER;
        }
        return MemberGrade.BRONZE;
    }
}
