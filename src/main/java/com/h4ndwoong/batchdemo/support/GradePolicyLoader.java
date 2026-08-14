package com.h4ndwoong.batchdemo.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 대상 테이블의 포인트 분포에서 등급 정책을 산출한다. <b>Step 당 한 번만 부른다.</b>
 *
 * <p>집계 한 번({@code SELECT MIN(point), MAX(point)})이며 테이블 전체를 훑으므로 공짜는 아니다.
 * 이 비용이 <b>행당 1회가 아니라 Step 당 1회</b>라는 것이 정책 로딩의 요점이다. 실무에서 정책을
 * 행마다 다시 읽는 코드가 드물지 않은데, 그 형태를 4번의 before 로 삼지 않은 이유는 §설계에 적었다 —
 * 50만 행 × 전체 스캔은 실행이 불가능해서 <b>재현조차 되지 않기</b> 때문이다. 그래서 정책 로딩은
 * before/after 가 똑같이 1회 하고, 비교 축은 문제마다 하나로 좁혔다 (4번은 추천인 조회, 6번은
 * 쓰기 경로).
 *
 * <p><b>테이블 이름을 받는 이유</b><br>
 * 4번({@code member_d})과 6번({@code member_f})이 같은 방식으로 정책을 얻는다. 테이블 이름이
 * SQL 에 문자열로 들어가므로 <b>코드에 적힌 상수만</b> 넘긴다 — Job 파라미터 같은 외부 입력은
 * 안 된다 ({@link TableSeededValidator} 와 같은 규칙이다).
 *
 * <p><b>인터페이스가 아닌 이유</b><br>
 * 구현이 하나뿐이고 선택 로직도 없다. 정책의 출처가 정책 테이블이나 설정으로 바뀌는 날 추상화하면
 * 된다 ({@code MemberAEmptyValidator} 가 남긴 판단과 같다 — "구현이 하나뿐인 지금은 그 추상화가
 * 코드보다 크다").
 */
public class GradePolicyLoader {

    private static final String SQL_TEMPLATE =
            "SELECT MIN(point) AS min_point, MAX(point) AS max_point FROM %s";

    private final JdbcTemplate jdbcTemplate;
    private final String table;

    /**
     * 로더를 만든다.
     *
     * @param jdbcTemplate JDBC 템플릿
     * @param table        대상 테이블 이름. <b>코드에 적힌 상수만</b> 넘긴다. 외부 입력은 안 된다
     */
    public GradePolicyLoader(JdbcTemplate jdbcTemplate, String table) {
        this.jdbcTemplate = jdbcTemplate;
        this.table = table;
    }

    /**
     * 정책을 산출한다.
     *
     * <p>빈 테이블에서는 {@code MIN}/{@code MAX} 가 {@code NULL} 이라 정책을 만들 수 없다. 다만
     * 여기까지 오기 전에 {@link TableSeededValidator} 가 Job 을 세우므로, 이 예외는 실제로는
     * 그 방어가 뚫렸을 때만 보인다.
     *
     * @return 등급 정책
     * @throws IllegalStateException 대상 테이블이 비어 있을 때
     */
    public GradePolicy load() {
        return jdbcTemplate.query(SQL_TEMPLATE.formatted(table), resultSet -> {
            if (!resultSet.next() || resultSet.getObject("min_point") == null) {
                throw new IllegalStateException(
                        table + " 가 비어 있어 등급 정책을 만들 수 없다. 먼저 시딩한다.");
            }
            return GradePolicy.ofRange(resultSet.getLong("min_point"), resultSet.getLong("max_point"));
        });
    }
}
