package com.h4ndwoong.batchdemo.update;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code member_f} 의 {@code (grade, point)} 인덱스를 만들고 지운다. <b>6번 문제의 부록 측정 축</b>이다.
 *
 * <p><b>설계 중에 이 인덱스의 역할이 바뀌었다.</b> {@code schema.sql} 하단 주석은 이것을 "after 의
 * 집합 UPDATE 조건 경로" 로 예고했는데, 실제로 after 를 {@code id} 슬라이스 + {@code CASE} 한 문장으로
 * 짜고 나니 <b>이 인덱스를 타지 않는 것이 더 빠르다.</b> 슬라이스는 PK 범위를 한 번 지나가면 끝나고,
 * 인덱스로 대상을 좁히는 것이 이득인 경우는 갱신 대상이 희소할 때인데 6번은 전체의 75%가 바뀌는
 * 전량 재계산이기 때문이다 ({@link SetBasedGradeUpdateItemWriter} 에 적었다).
 *
 * <p>그래서 이 인덱스는 <b>조건 경로가 아니라 비용의 측정 대상</b>으로 남는다.
 * <b>갱신하는 컬럼 위에 인덱스가 있으면 대량 UPDATE 는 얼마나 비싸지는가</b> — 1번 문제의
 * "인덱스 유지 비용" 을 UPDATE 로 옮긴 질문이다. {@code grade} 가 바뀌면 인덱스 상의 위치도
 * 옮겨져야 하므로, 갱신 75만 행마다 인덱스 항목의 삭제와 삽입이 따라붙는다.
 *
 * <p><b>100만 건 실측이 두 가지를 확인해 주었다.</b>
 * <ul>
 *   <li>비용은 실재한다 — after 가 8.1s/229.3 MiB 에서 <b>14.0s/1,291.6 MiB</b> 로, before 가
 *       37.8s/297.3 MiB 에서 <b>45.1s/1,830.7 MiB</b> 로 간다. write IO 가 5~6배다</li>
 *   <li>그런데 조회를 돕지는 않는다 — {@code EXPLAIN} 이 인덱스 유무와 무관하게
 *       {@code possible_keys: PRIMARY}, {@code key: PRIMARY}, {@code type: range} 를 돌려준다.
 *       {@code grade <> CASE ...} 는 인덱스로 좁힐 수 있는 조건이 아니고 {@code id BETWEEN} 은
 *       PK 가 이미 최적이다</li>
 * </ul>
 * <b>"조회를 위해" 인덱스를 거는 판단은, 그 조회가 실제로 그것을 타는지 확인하기 전까지 추측이다.</b>
 *
 * <p><b>프로파일이 아니라 프로퍼티로 켠다</b> ({@code --update.grade-point-index=true}). before 와
 * after 중 한쪽에만 걸면 그 인덱스 비용이 프로파일 차이에 섞여 비교 축이 오염된다. 이 축은
 * <b>양쪽에 독립적으로</b> 걸어 보는 것이라 프로파일과 직교해야 한다 (4번의 {@code lookup.chunk-size}
 * 와 같은 성격의 축이다).
 *
 * <p>DDL 은 {@code IF NOT EXISTS} / {@code IF EXISTS} 다. Job 은 여러 번 실행되고, 그때 이미 인덱스가
 * 있다고 해서 실패해서는 안 된다.
 */
public class MemberFGradePointIndex {

    /** {@code schema.sql} 하단 주석이 예고한 인덱스. */
    private static final String CREATE_STATEMENT =
            "ALTER TABLE member_f ADD KEY IF NOT EXISTS idx_member_f_grade_point (grade, point)";

    private static final String DROP_STATEMENT =
            "ALTER TABLE member_f DROP INDEX IF EXISTS idx_member_f_grade_point";

    private static final String EXISTS_SQL = """
            SELECT COUNT(*) FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'member_f'
              AND INDEX_NAME = 'idx_member_f_grade_point'""";

    private final JdbcTemplate jdbcTemplate;

    /**
     * DDL 실행기를 만든다.
     *
     * @param jdbcTemplate JDBC 템플릿
     */
    public MemberFGradePointIndex(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 인덱스를 만든다. 이미 있으면 아무 일도 하지 않는다.
     *
     * <p>DDL 은 MariaDB 에서 암묵적 커밋을 일으키므로 배치 트랜잭션 밖에서 호출해야 한다. Step 안이
     * 아니라 Job 리스너에서 부르는 이유이며, 100만 행에 인덱스를 만드는 비용도 Job 측정 범위에
     * 들어가야 공정하다 (1번 문제에서 확립한 원칙이다).
     */
    public void create() {
        jdbcTemplate.execute(CREATE_STATEMENT);
    }

    /**
     * 인덱스를 지운다. 없으면 아무 일도 하지 않는다.
     *
     * <p>지우는 쪽도 Job 이 보장해야 한다. 직전 실행이 만들어 둔 인덱스가 남아 있으면 "없는 상태" 를
     * 재려던 다음 실행이 조용히 다른 것을 잰다 — 시작 상태를 사람의 기억에 맡기지 않는다.
     */
    public void drop() {
        jdbcTemplate.execute(DROP_STATEMENT);
    }

    /**
     * 인덱스가 존재하는지 여부.
     *
     * @return 있으면 {@code true}
     */
    public boolean exists() {
        Long count = jdbcTemplate.queryForObject(EXISTS_SQL, Long.class);
        return count != null && count > 0;
    }
}
