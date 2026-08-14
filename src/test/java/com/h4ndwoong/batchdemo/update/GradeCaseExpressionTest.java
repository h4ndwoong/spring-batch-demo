package com.h4ndwoong.batchdemo.update;

import com.h4ndwoong.batchdemo.domain.MemberGrade;
import com.h4ndwoong.batchdemo.support.GradePolicy;
import com.h4ndwoong.batchdemo.support.TestDatabase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 등급 규칙의 두 표현이 <b>모든 경계에서 같은 답</b>을 내는지 확인한다. 6번 문제에서 가장 중요한 시험이다.
 *
 * <p>after 는 등급을 자바가 아니라 SQL 의 {@code CASE} 식으로 정한다. 규칙이 두 곳에 존재하는 셈이고,
 * 둘이 어긋나면 before 와 after 가 다른 답을 내는데 <b>양쪽 다 {@code COMPLETED} 로 끝난다.</b>
 * 왕복이 수만 배 줄었다는 주장은 이 시험이 서야 의미가 있다.
 *
 * <p><b>실제 MariaDB 에서 식을 평가한다.</b> 자바로 SQL 문자열을 비교하는 시험은 "내가 생각한 문자열이
 * 맞는가" 만 말한다. 여기서 물어야 하는 것은 <b>서버가 그 식을 우리 정책과 같게 해석하는가</b>이며,
 * 그것은 서버에 물어봐야만 알 수 있다 — 비교 연산의 경계, 타입 변환, {@code CASE} 의 평가 순서가
 * 모두 서버의 규칙이기 때문이다.
 */
@SpringBootTest(properties = {
        TestDatabase.URL,
        TestDatabase.BATCH_SCHEMA,
        TestDatabase.DOMAIN_SCHEMA
})
class GradeCaseExpressionTest {

    private static final GradePolicy POLICY = new GradePolicy(1_000, 5_000, 10_000);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("CASE 식이 gradeOf 와 모든 경계에서 일치한다")
    void 두_표현이_일치한다() {
        GradeCaseExpression expression = GradeCaseExpression.of(POLICY);

        for (long point : boundaryPoints()) {
            assertThat(evaluate(expression, point))
                    .as("point=%d 에서 자바와 SQL 의 답이 갈리면 등급만 조용히 틀린다", point)
                    .isEqualTo(POLICY.gradeOf(point));
        }
    }

    @Test
    @DisplayName("사분위 정책에서도 일치한다 - 실행 환경이 실제로 쓰는 임계값")
    void 실제_정책에서도_일치한다() {
        GradePolicy policy = GradePolicy.ofRange(0, 99_999);
        GradeCaseExpression expression = GradeCaseExpression.of(policy);

        for (long point : List.of(0L, 24_998L, 24_999L, 49_998L, 49_999L, 74_998L, 74_999L, 99_999L)) {
            assertThat(evaluate(expression, point)).isEqualTo(policy.gradeOf(point));
        }
    }

    @Test
    @DisplayName("음수 포인트도 같은 답이다 - 2번의 오염 데이터가 여기까지 온다면")
    void 음수도_일치한다() {
        GradeCaseExpression expression = GradeCaseExpression.of(POLICY);

        assertThat(evaluate(expression, -1)).isEqualTo(MemberGrade.BRONZE);
        assertThat(evaluate(expression, -50_000)).isEqualTo(POLICY.gradeOf(-50_000));
    }

    @Test
    @DisplayName("식이 요구하는 파라미터는 임계값 셋뿐이다")
    void 파라미터() {
        assertThat(GradeCaseExpression.of(POLICY).parameters())
                .containsOnlyKeys("fromVIP", "fromGOLD", "fromSILVER")
                .containsEntry("fromVIP", 10_000L);
    }

    /**
     * 임계값 주변의 점들. <b>경계에서 갈리는 것이 가장 흔한 사고</b>이므로 ±1 을 모두 넣는다.
     *
     * @return 검사할 포인트 목록
     */
    private static List<Long> boundaryPoints() {
        List<Long> points = new ArrayList<>(List.of(Long.MIN_VALUE / 2, 0L, Long.MAX_VALUE / 2));
        for (long threshold : List.of(POLICY.silverFrom(), POLICY.goldFrom(), POLICY.vipFrom())) {
            points.add(threshold - 1);
            points.add(threshold);
            points.add(threshold + 1);
        }
        return points;
    }

    private MemberGrade evaluate(GradeCaseExpression expression, long point) {
        Map<String, Object> parameters = new HashMap<>(expression.parameters());
        parameters.put("point", point);

        String grade = new NamedParameterJdbcTemplate(jdbcTemplate).queryForObject(
                "SELECT %s FROM (SELECT :point AS point) AS probe".formatted(expression.sql()),
                new MapSqlParameterSource(parameters), String.class);
        return MemberGrade.valueOf(grade);
    }
}
