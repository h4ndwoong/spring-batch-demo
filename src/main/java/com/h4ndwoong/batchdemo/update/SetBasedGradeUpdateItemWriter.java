package com.h4ndwoong.batchdemo.update;

import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 슬라이스 하나를 <b>UPDATE 문 한 번</b>으로 끝낸다. 6번 문제 after 의 쓰기 경로다.
 *
 * <pre>{@code
 * UPDATE member_f
 * SET grade = CASE WHEN point >= :fromVIP THEN 'VIP' ... ELSE 'BRONZE' END,
 *     updated_at = :now
 * WHERE id BETWEEN :fromId AND :toId
 *   AND grade <> CASE WHEN point >= :fromVIP THEN 'VIP' ... ELSE 'BRONZE' END
 * }</pre>
 *
 * <p><b>{@code CASE} 식이 두 번 나오는 것이 이 문장의 전부다.</b> {@code SET} 절만 있으면 구간의
 * 모든 행이 갱신되어 before(등급이 바뀐 행만 쓴다)와 <em>일의 양</em>이 달라진다. {@code WHERE} 절의
 * 같은 식이 before 의 프로세서 필터에 정확히 대응하며, 덕분에
 * <ul>
 *   <li>갱신 행 수가 before 와 같아진다 — "왕복만 줄었다" 가 성립하는 전제</li>
 *   <li>{@code updated_at} 이 실제로 바뀐 행에만 채워진다 — 체크섬의 {@code changedRows} 가 같아진다</li>
 *   <li>재실행이 0행을 갱신한다 — 이 배치가 자연 멱등인 이유</li>
 * </ul>
 *
 * <p><b>왜 등급 전이별로 문장을 나누지 않는가</b><br>
 * {@code WHERE grade = 'BRONZE' AND point >= :fromVIP} 처럼 (원등급 → 새등급) 쌍마다 문장을 만들면
 * {@code (grade, point)} 인덱스를 확실히 타지만, 문장이 12개가 되고 <b>슬라이스마다 그 인덱스
 * 구간을 12번 훑는다.</b> {@code CASE} 한 문장이면 PK 범위를 <b>슬라이스당 정확히 한 번</b> 지나가며
 * 끝난다. 인덱스로 대상을 좁히는 것이 이득인 경우는 갱신 대상이 희소할 때이고, 6번은 전체의 75%가
 * 바뀌는 <b>전량 재계산</b>이라 그 반대다.
 *
 * <p><b>한 슬라이스 = 한 문장 = 한 트랜잭션이다.</b> 청크 크기를 1로 두는 이유이며, 그래야
 * 슬라이스 크기가 곧 락 유지 시간의 다이얼이 된다.
 *
 * <p><b>{@code updated_at} 은 슬라이스 안에서 하나의 값이다.</b> before 는 행마다 프로세서가 찍은
 * 시각을 쓰므로 미세하게 다르다. 체크섬은 {@code updated_at} 의 <em>값</em>이 아니라
 * {@code NULL} 여부만 세므로 비교 축에는 영향이 없다.
 */
public class SetBasedGradeUpdateItemWriter implements ItemWriter<IdSlice> {

    private static final String UPDATE_SQL_TEMPLATE = """
            UPDATE %s
            SET grade = %s,
                updated_at = :now
            WHERE id BETWEEN :fromId AND :toId
              AND grade <> %s""";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final String sql;
    private final Map<String, Object> policyParameters;
    private final Clock clock;
    private final SliceUpdateRecorder recorder;

    /**
     * 라이터를 만든다.
     *
     * @param jdbcTemplate 이름 있는 파라미터를 쓰는 JDBC 템플릿
     * @param table        대상 테이블 이름. <b>코드에 적힌 상수만</b> 넘긴다. 외부 입력은 안 된다
     * @param gradeCase    등급 정책을 옮긴 {@code CASE} 식
     * @param clock        {@code updated_at} 의 출처
     * @param recorder     슬라이스별 측정치를 받는 기록기
     */
    public SetBasedGradeUpdateItemWriter(NamedParameterJdbcTemplate jdbcTemplate,
                                         String table,
                                         GradeCaseExpression gradeCase,
                                         Clock clock,
                                         SliceUpdateRecorder recorder) {
        this.jdbcTemplate = jdbcTemplate;
        this.sql = UPDATE_SQL_TEMPLATE.formatted(table, gradeCase.sql(), gradeCase.sql());
        this.policyParameters = gradeCase.parameters();
        this.clock = clock;
        this.recorder = recorder;
    }

    /**
     * {@inheritDoc}
     *
     * <p>청크 크기가 1이므로 이 메서드는 슬라이스 하나만 받는다. 그래도 반복문인 이유는 청크 크기를
     * 키워 "트랜잭션 하나에 슬라이스 여러 개" 를 재 보는 실험이 가능해야 하기 때문이다 (락 유지
     * 시간이 어떻게 자라는지가 그대로 드러난다).
     *
     * @param chunk 처리할 슬라이스
     */
    @Override
    public void write(Chunk<? extends IdSlice> chunk) {
        LocalDateTime now = LocalDateTime.now(clock);

        for (IdSlice slice : chunk) {
            long startedAt = System.nanoTime();
            int updated = jdbcTemplate.update(sql, parameters(slice, now));
            recorder.record(slice, updated, System.nanoTime() - startedAt);
        }
    }

    /**
     * 실행할 SQL. 테스트와 {@code EXPLAIN} 을 위해 열어 둔다.
     *
     * @return UPDATE 문
     */
    public String sql() {
        return sql;
    }

    private MapSqlParameterSource parameters(IdSlice slice, LocalDateTime now) {
        Map<String, Object> values = new HashMap<>(policyParameters);
        values.put("fromId", slice.fromId());
        values.put("toId", slice.toId());
        values.put("now", Timestamp.valueOf(now));
        return new MapSqlParameterSource(values);
    }
}
