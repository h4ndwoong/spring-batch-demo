package com.h4ndwoong.batchdemo.update;

import com.h4ndwoong.batchdemo.domain.MemberGrade;

import java.util.EnumMap;
import java.util.Map;

/**
 * {@code member_f} 전체의 지문. <b>6번 문제에서 "같아야 하는 것" 은 이 값이다.</b>
 *
 * <p>6번의 before/after 는 쓰기 <em>경로</em>만 다르고 쓰기 <em>결과</em>는 같아야 한다. 왕복을
 * 수만 배 줄였다는 주장은 이 지문이 같을 때만 의미가 있다 — 집합 UPDATE 로 바꿀 때 가장 흔한
 * 사고는 느려지는 것이 아니라 <b>조건을 잘못 써서 일부 행을 빠뜨리거나 엉뚱한 등급을 주는 것</b>이고,
 * 그러면 배치는 {@code COMPLETED} 로 끝난다.
 *
 * <p>네 값이 각각 다른 사고를 잡는다.
 * <ul>
 *   <li>{@code distribution} — 주 지표. 등급을 규칙대로 매겼는가</li>
 *   <li>{@code changedRows} — <b>일의 양</b>. {@code updated_at} 이 채워진 행 수이며 before 의
 *       {@code WRITE_COUNT}, after 의 갱신 행 합계와 같아야 한다</li>
 *   <li>{@code pointSum} — <b>건드리지 말아야 할 것</b>. 6번은 {@code grade} 만 바꾸므로 이 값은
 *       변하지 않는다 (5번이 파괴한 컬럼이 바로 이것이라 대조가 된다)</li>
 *   <li>{@code rowCount} — 대상 자체가 변하지 않았다는 확인</li>
 * </ul>
 *
 * @param rowCount     {@code member_f} 의 전체 행 수
 * @param changedRows  {@code updated_at} 이 {@code NULL} 이 아닌 행 수
 * @param pointSum     포인트 총합. 이 배치는 건드리지 않는다
 * @param distribution 등급 분포. 네 등급 모두 키로 존재한다 (없으면 0)
 */
public record GradeRecalcChecksum(long rowCount,
                                  long changedRows,
                                  long pointSum,
                                  Map<MemberGrade, Long> distribution) {

    /** 한 행도 없는 상태. */
    public static final GradeRecalcChecksum EMPTY = new GradeRecalcChecksum(0, 0, 0, Map.of());

    /**
     * 지문을 만든다. 분포는 <b>네 등급을 모두 채운</b> 불변 맵으로 정규화된다.
     *
     * <p>정규화하지 않으면 "{@code VIP} 가 0건" 인 실행과 "{@code VIP} 키가 없는" 실행이 서로 다른
     * 값이 되어, 같은 결과인데도 before/after 비교가 깨진다 (4번의 {@code GradeDecisionChecksum} 과
     * 같은 이유다).
     */
    public GradeRecalcChecksum {
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
                .append("rowCount=").append(rowCount)
                .append(", changedRows=").append(changedRows)
                .append(", pointSum=").append(pointSum);
        for (MemberGrade grade : MemberGrade.values()) {
            text.append(", ").append(grade).append('=').append(distribution.get(grade));
        }
        return text.toString();
    }
}
