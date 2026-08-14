package com.h4ndwoong.batchdemo.skip;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Types;

/**
 * 스킵된 행을 {@code member_b_error} 에 적재한다. 격리의 <b>저장</b> 책임만 진다.
 *
 * <p><b>트랜잭션을 따로 열지 않는다.</b> Spring Batch 는 {@code SkipListener} 를 청크 트랜잭션이
 * <em>커밋되기 직전</em>에 호출한다. 그러므로 이 INSERT 는 "그 행이 스킵되었다" 는 사실과 같은
 * 트랜잭션에서 커밋된다. {@code REQUIRES_NEW} 로 분리하면 반대 위험이 생긴다 — 청크 커밋이
 * 나중에 실패했을 때 격리 기록만 남아, 실제로는 처리되지 않은 행이 "격리됨" 으로 보인다.
 * 원자성이 있는 쪽을 택했고, 이 성질은 {@code AfterSkipJobTest} 가 실제 실행으로 확인한다.
 *
 * <p><b>길이를 여기서 자른다.</b> 격리 기록이 컬럼 길이 초과로 실패하면 격리 자체가 무너지고,
 * 그 실패는 청크 트랜잭션을 타고 올라가 Step 을 죽인다. 오염 행 하나 때문에 Step 이 죽는 것은
 * 정확히 before 의 증상이므로, after 에서 그 일이 다시 일어나면 안 된다.
 */
public class ErrorRowRecorder {

    /** {@code raw_item} 과 {@code message} 컬럼의 길이. */
    static final int TEXT_LIMIT = 1_000;

    /** {@code exception_type} 컬럼의 길이. */
    static final int TYPE_LIMIT = 255;

    private static final String INSERT_SQL = """
            INSERT INTO member_b_error (member_id, phase, raw_item, exception_type, message,
                                        step_execution_id, skipped_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)""";

    private final JdbcTemplate jdbcTemplate;

    public ErrorRowRecorder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 격리 기록 한 건을 적재한다.
     *
     * @param row 격리 기록. 길이를 넘는 문자열은 잘려서 저장된다
     */
    public void record(SkippedRow row) {
        jdbcTemplate.update(INSERT_SQL,
                new Object[]{
                        row.memberId(),
                        row.phase().name(),
                        truncate(row.rawItem(), TEXT_LIMIT),
                        truncate(row.exceptionType(), TYPE_LIMIT),
                        truncate(row.message(), TEXT_LIMIT),
                        row.stepExecutionId(),
                        row.skippedAt()
                },
                new int[]{
                        Types.BIGINT, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR,
                        Types.VARCHAR, Types.BIGINT, Types.TIMESTAMP
                });
    }

    private static String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit);
    }
}
