package com.h4ndwoong.batchdemo.lookup;

import com.h4ndwoong.batchdemo.domain.MemberGrade;

import java.util.EnumMap;
import java.util.Map;

/**
 * 등급 재산정 결과 전체의 지문. <b>before 와 after 가 같은 일을 했다는 증거</b>다.
 *
 * <p>3번의 {@code TraversalChecksum} 과 같은 역할이지만 세는 것이 다르다. 3번은 "같은 행을 같은
 * 순서로 <em>읽었는가</em>" 였고, 4번은 "같은 <em>답</em>을 냈는가" 다. 조회를 묶으면서 가장 흔한
 * 사고가 느려지는 것이 아니라 <b>엉뚱한 추천인을 붙이는 것</b>이기 때문이다 — {@code IN} 조회로
 * 받은 결과를 잘못 맞추면 등급이 조용히 틀린 채로 배치는 성공한다. 왕복이 2,000분의 1이 되었다는
 * 주장은 이 지문이 같을 때만 의미가 있다.
 *
 * @param count            산정한 행 수
 * @param changed          등급이 바뀐 행 수
 * @param effectivePointSum {@code effectivePoint} 의 합. 추천인 보너스가 제대로 붙었는지를 한 숫자로 본다
 * @param distribution     새 등급의 분포. 네 등급 모두 키로 존재한다 (없으면 0)
 */
public record GradeDecisionChecksum(long count,
                                    long changed,
                                    long effectivePointSum,
                                    Map<MemberGrade, Long> distribution) {

    /** 한 행도 산정하지 않은 상태. */
    public static final GradeDecisionChecksum EMPTY = new GradeDecisionChecksum(0, 0, 0, Map.of());

    /**
     * 지문을 만든다. 분포는 <b>네 등급을 모두 채운</b> 불변 맵으로 정규화된다.
     *
     * <p>정규화하지 않으면 "{@code VIP} 가 0건" 인 실행과 "{@code VIP} 키가 없는" 실행이 서로 다른
     * 값이 되어, 같은 결과인데도 before/after 비교가 깨진다.
     */
    public GradeDecisionChecksum {
        Map<MemberGrade, Long> normalized = new EnumMap<>(MemberGrade.class);
        for (MemberGrade grade : MemberGrade.values()) {
            normalized.put(grade, distribution.getOrDefault(grade, 0L));
        }
        distribution = Map.copyOf(normalized);
    }

    /**
     * 사람이 읽는 한 줄 요약.
     *
     * @return 요약 문자열
     */
    public String summary() {
        StringBuilder text = new StringBuilder()
                .append("count=").append(count)
                .append(", changed=").append(changed)
                .append(", effectivePointSum=").append(effectivePointSum);
        for (MemberGrade grade : MemberGrade.values()) {
            text.append(", ").append(grade).append('=').append(distribution.get(grade));
        }
        return text.toString();
    }
}
