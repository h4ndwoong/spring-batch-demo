package com.h4ndwoong.batchdemo.outbox;

import com.h4ndwoong.batchdemo.support.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code uk_member_g_outbox_key} 가 무엇을 막고 무엇을 막지 못하는지 고정한다.
 *
 * <p><b>정상 경로에서 이 제약은 한 번도 걸리지 않는다.</b> 적재와 상태 변경이 같은 트랜잭션이라
 * "Outbox 에 있다" 와 "이미 전이됐다" 가 같은 말이고, 전이된 회원은 다시 읽히지 않기 때문이다.
 * 중복 적재를 실제로 막는 것은 읽기 조건 {@code status = 'ACTIVE'} 와 그 원자성이다.
 *
 * <p>그러면 이 UK 는 왜 있는가. 5번의 결론과 같다 — <b>마지막 그물이지 정문이 아니다.</b> 원자성이
 * 깨졌거나 키 생성 규칙이 틀렸을 때, 이 제약이 없으면 같은 알림이 두 번 적재되고 릴레이는 그것을
 * 성실하게 두 번 보낸다. 그리고 <b>배치는 {@code COMPLETED} 로 끝난다.</b> 여기서 확인하는 것은
 * 그 상황이 조용히 지나가지 않는다는 사실이다.
 */
@SpringBootTest(properties = {
        TestDatabase.URL,
        TestDatabase.BATCH_SCHEMA,
        TestDatabase.DOMAIN_SCHEMA,
        OutboxFixture.SENDER_LOG_LEVEL
})
@ActiveProfiles("after")
class OutboxUniqueKeyTest {

    private static final String INSERT_SQL = """
            INSERT INTO member_g_outbox (member_id, event_type, payload, idempotency_key,
                                         status, retry_count, created_at)
            VALUES (?, ?, ?, ?, 'PENDING', 0, ?)""";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void 비운다() {
        jdbcTemplate.execute("TRUNCATE TABLE " + OutboxJobCommonConfig.OUTBOX_TABLE);
    }

    @Test
    @DisplayName("같은 멱등키를 두 번 적재하면 거부한다 - 조용히 두 번 보내지 않는다")
    void 같은_키_두_번() {
        insert(1L);

        assertThatThrownBy(() -> insert(1L))
                .as("이 제약이 없으면 릴레이가 같은 알림을 성실하게 두 번 보내고 배치는 COMPLETED 로 끝난다")
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("다른 회원의 알림은 함께 적재된다 - 막는 것은 중복이지 발송이 아니다")
    void 다른_키() {
        insert(1L);

        assertThatCode(() -> insert(2L)).doesNotThrowAnyException();
    }

    private void insert(long memberId) {
        NotificationMessage message = new NotificationMessage(memberId,
                StatusChangedNotification.EVENT_TYPE,
                "{\"memberId\":%d}".formatted(memberId),
                NotificationIdempotencyKey.of(memberId),
                LocalDateTime.of(2026, 1, 1, 12, 0));

        jdbcTemplate.update(INSERT_SQL, message.memberId(), message.eventType(), message.payload(),
                message.idempotencyKey(), Timestamp.valueOf(message.createdAt()));
    }
}
