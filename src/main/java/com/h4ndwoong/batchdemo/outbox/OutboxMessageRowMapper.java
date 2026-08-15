package com.h4ndwoong.batchdemo.outbox;

import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * {@code member_g_outbox} 의 한 행을 {@link OutboxMessage} 로 복원한다. 릴레이의 리더가 쓴다.
 *
 * <p>{@code status} 와 {@code sent_at} 을 읽지 않는다. 릴레이가 집는 것은 언제나
 * {@link OutboxStatus#PENDING} 이고, {@code sent_at} 은 이 행을 <em>보낸 뒤에</em> 쓰는 값이라
 * 읽을 시점에는 언제나 {@code NULL} 이다. <b>읽어서 쓰지 않을 컬럼은 읽지 않는다.</b>
 */
public class OutboxMessageRowMapper implements RowMapper<OutboxMessage> {

    /** {@inheritDoc} */
    @Override
    public OutboxMessage mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new OutboxMessage(
                rs.getLong("id"),
                rs.getLong("member_id"),
                rs.getString("event_type"),
                rs.getString("payload"),
                rs.getString("idempotency_key"),
                toLocalDateTime(rs.getTimestamp("created_at")));
    }

    private static LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
