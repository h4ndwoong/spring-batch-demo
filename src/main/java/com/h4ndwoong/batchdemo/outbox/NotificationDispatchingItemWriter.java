package com.h4ndwoong.batchdemo.outbox;

import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox 에 적재된 발송 요청을 실제로 내보내고 {@code SENT} 로 표시한다. <b>after 의 릴레이</b>다.
 *
 * <p><b>순서가 계약이다.</b> 먼저 보내고 나중에 표시한다. 반대로 하면 — 표시부터 하고 보내면 —
 * 표시 뒤 발송 전에 죽었을 때 <b>영원히 나가지 않는 알림</b>이 생긴다. 유실과 중복 중 하나를 골라야
 * 하고, 알림은 <b>두 번 가는 편이 안 가는 편보다 낫다.</b> 그 선택의 이름이 at-least-once 다.
 *
 * <p><b>그래서 after 도 exactly-once 가 아니다.</b> 발송과 표시는 원자적으로 묶을 수 없다 — 하나는
 * 외부에서, 하나는 DB 에서 일어나기 때문이다. 이 라이터가 청크의 절반을 보낸 뒤 죽으면 표시는
 * 롤백되고, 다음 실행이 그 청크를 <b>통째로 다시 보낸다.</b>
 * <pre>
 *   중복의 상한 = 릴레이 청크 크기
 * </pre>
 * 6번의 슬라이스 크기가 락 유지 시간의 다이얼이었듯, 여기서 청크 크기는 <b>중복의 다이얼</b>이다
 * ({@code outbox.relay-chunk-size}). 최종 방어선은 결국 수신자이고, 그래서 메시지에 멱등키가
 * 실려 있어야 한다.
 *
 * <p><b>표시는 한 문장으로 한다.</b> 6번에서 배운 것을 그대로 적용한다 — 행마다
 * {@code UPDATE ... WHERE id = ?} 를 보내면 릴레이가 before 의 증상을 그대로 재현한다 (발송 요청
 * 수만큼의 UPDATE 문). {@code WHERE id IN (...)} 은 청크당 한 문장이다. Outbox 패턴이 추가하는
 * 쓰기 비용을 여기서 되받는다.
 */
public class NotificationDispatchingItemWriter implements ItemWriter<OutboxMessage> {

    private static final String MARK_SENT_SQL = """
            UPDATE member_g_outbox
            SET status = '%s', sent_at = :sentAt
            WHERE id IN (:ids)""".formatted(OutboxStatus.SENT);

    private final NotificationSender sender;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final Clock clock;

    /**
     * 릴레이 라이터를 만든다.
     *
     * @param sender       알림 발송기. before 의 라이터와 <b>같은 빈</b>이다
     * @param jdbcTemplate 이름 있는 파라미터를 쓰는 JDBC 템플릿
     * @param clock        {@code sent_at} 의 출처
     */
    public NotificationDispatchingItemWriter(NotificationSender sender,
                                             NamedParameterJdbcTemplate jdbcTemplate,
                                             Clock clock) {
        this.sender = sender;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /**
     * {@inheritDoc}
     *
     * <p>발송이 중간에 실패하면 표시에 도달하지 못하므로 청크 전체가 {@code PENDING} 으로 남는다.
     * <b>이미 나간 것까지</b> 남는다는 것이 이 설계가 감수하는 대가다.
     *
     * @param chunk 보낼 발송 요청들
     * @throws NotificationException 발송에 실패했을 때
     */
    @Override
    public void write(Chunk<? extends OutboxMessage> chunk) {
        if (chunk.isEmpty()) {
            return;
        }

        for (OutboxMessage message : chunk) {
            sender.send(message.toNotification());
        }

        markSent(chunk.getItems().stream().map(OutboxMessage::id).toList());
    }

    private void markSent(List<Long> ids) {
        jdbcTemplate.update(MARK_SENT_SQL, new MapSqlParameterSource()
                .addValue("sentAt", Timestamp.valueOf(LocalDateTime.now(clock)), Types.TIMESTAMP)
                .addValue("ids", ids));
    }
}
