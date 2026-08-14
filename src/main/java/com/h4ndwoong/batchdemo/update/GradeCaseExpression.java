package com.h4ndwoong.batchdemo.update;

import com.h4ndwoong.batchdemo.support.GradePolicy;
import com.h4ndwoong.batchdemo.support.GradeThreshold;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 등급 정책을 SQL {@code CASE} 식으로 옮긴다. 6번 문제 after 의 <b>규칙 이관 장치</b>다.
 *
 * <p>after 는 회원 행을 애플리케이션으로 가져오지 않는다. 그러면 등급 산정도 자바가 아니라 서버에서
 * 일어나야 하고, 규칙이 <b>두 곳에 존재</b>하게 된다 — {@link GradePolicy#gradeOf(long)} 와 이
 * {@code CASE} 식이다. 둘이 어긋나면 before 와 after 가 다른 답을 내는데, <b>배치는 양쪽 다
 * {@code COMPLETED} 로 끝나고 등급만 조용히 틀린다.</b> 4번 문제에서 가장 경계했던 실패 모양이다.
 *
 * <p>그래서 식을 손으로 쓰지 않고 {@link GradePolicy#thresholdsDescending()} 에서 <b>생성</b>한다.
 * 정책의 임계값이 바뀌면 자바와 SQL 이 함께 움직이고, 두 표현이 모든 경계에서 일치한다는 사실은
 * {@code GradeCaseExpressionTest} 가 못 박는다.
 *
 * <pre>{@code
 * CASE WHEN point >= :fromVIP THEN 'VIP'
 *      WHEN point >= :fromGOLD THEN 'GOLD'
 *      WHEN point >= :fromSILVER THEN 'SILVER'
 *      ELSE 'BRONZE' END
 * }</pre>
 *
 * <p><b>임계값을 식에 박지 않고 이름 있는 파라미터로 두는 이유</b><br>
 * 정책은 실행 시점의 데이터에서 나오므로({@code MIN/MAX(point)}) SQL 은 매 실행 같은 문자열이어야
 * 값이 달라져도 서버의 문장 캐시를 재사용한다. 무엇보다 값이 문장에 섞이면 "정책이 SQL 안에 있다" 는
 * 인상을 주는데, 실제로는 여전히 {@link GradePolicy} 하나가 규칙의 출처다.
 *
 * <p><b>등급 이름은 리터럴로 들어간다.</b> {@code MemberGrade} 의 enum 상수 이름이라 외부 입력이
 * 아니며, {@code @Enumerated(STRING)} 이 컬럼에 넣는 값과 같아야 하므로 여기서 만드는 것이 맞다.
 */
public final class GradeCaseExpression {

    private final String sql;
    private final Map<String, Object> parameters;

    private GradeCaseExpression(String sql, Map<String, Object> parameters) {
        this.sql = sql;
        this.parameters = parameters;
    }

    /**
     * 정책을 {@code CASE} 식으로 옮긴다.
     *
     * @param policy 등급 정책
     * @return 식과 그 식이 요구하는 파라미터
     */
    public static GradeCaseExpression of(GradePolicy policy) {
        StringBuilder expression = new StringBuilder("CASE");
        Map<String, Object> parameters = new LinkedHashMap<>();

        for (GradeThreshold threshold : policy.thresholdsDescending()) {
            String name = parameterName(threshold);
            expression.append(" WHEN point >= :").append(name)
                    .append(" THEN '").append(threshold.grade().name()).append('\'');
            parameters.put(name, threshold.fromInclusive());
        }
        expression.append(" ELSE '").append(GradePolicy.BASE_GRADE.name()).append("' END");

        return new GradeCaseExpression(expression.toString(), Map.copyOf(parameters));
    }

    /**
     * SQL 식. {@code SET} 절과 {@code WHERE} 절에 <b>같은 문자열</b>이 들어간다.
     *
     * <p>같은 식을 두 번 쓰는 것이 핵심이다. {@code SET grade = <식>} 만 있으면 등급이 그대로인
     * 행까지 전부 갱신되어 before 의 "바뀐 행만 쓴다" 와 일이 달라진다. {@code AND grade <> <식>} 이
     * before 의 프로세서 필터에 정확히 대응하며, 그 덕분에 재실행이 0행을 갱신한다.
     *
     * @return {@code CASE ... END} 식
     */
    public String sql() {
        return sql;
    }

    /**
     * 식이 요구하는 임계값 파라미터.
     *
     * @return 파라미터 이름과 값. 불변이다
     */
    public Map<String, Object> parameters() {
        return parameters;
    }

    private static String parameterName(GradeThreshold threshold) {
        return "from" + threshold.grade().name();
    }
}
