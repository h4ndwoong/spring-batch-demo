package com.h4ndwoong.batchdemo.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DatabaseWorkloadListener} 가 MariaDB 의 상태 카운터를 실제로 읽어 오는지 검증한다.
 *
 * <p>이 테스트가 지키는 것은 "숫자가 맞는가" 가 아니라 "숫자를 읽을 수 있는가" 다. 카운터는
 * 서버 전역이라 다른 세션의 작업이 섞이므로 정확한 값을 단정할 수 없다. 대신 <b>우리가 한 일보다
 * 적게 셀 수는 없다</b>는 하한만 확인한다. 이 하한이 깨지면 지표 자체를 신뢰할 수 없다는 뜻이다.
 */
@SpringBootTest(properties = {
        TestDatabase.URL,
        TestDatabase.BATCH_SCHEMA,
        TestDatabase.DOMAIN_SCHEMA
})
class DatabaseWorkloadListenerTest {

    private static final int ROWS = 10;

    @Autowired
    private DatabaseWorkloadListener listener;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void 정리() {
        jdbcTemplate.execute("TRUNCATE TABLE member_a");
    }

    @Test
    @DisplayName("MariaDB 의 상태 카운터를 읽는다")
    void 카운터를_읽는다() {
        Map<String, Long> counters = listener.readCounters();

        assertThat(counters)
                .as("이 지표들이 없으면 왕복 횟수와 디스크 write 를 잴 방법이 없다")
                .containsKeys(DatabaseWorkloadListener.INSERT_STATEMENTS,
                        DatabaseWorkloadListener.PAGES_WRITTEN,
                        DatabaseWorkloadListener.BYTES_WRITTEN);
        assertThat(counters.values()).allMatch(value -> value >= 0);
    }

    @Test
    @DisplayName("행별 INSERT 는 보낸 문 수만큼 카운터가 늘어난다 - before 의 1:1 왕복")
    void 행별_INSERT_는_행마다_왕복한다() {
        Map<String, Long> before = listener.readCounters();

        for (int i = 1; i <= ROWS; i++) {
            jdbcTemplate.update("""
                    INSERT INTO member_a (email, name, grade, point, status, created_at)
                    VALUES (?, '측정', 'BRONZE', 0, 'ACTIVE', NOW(6))""", "probe" + i + "@example.com");
        }

        Map<String, Long> after = listener.readCounters();

        assertThat(delta(before, after, DatabaseWorkloadListener.INSERT_STATEMENTS))
                .as("INSERT 를 %d번 보냈으므로 문 수가 그만큼은 늘어야 한다", ROWS)
                .isGreaterThanOrEqualTo(ROWS);
        assertThat(delta(before, after, DatabaseWorkloadListener.BYTES_WRITTEN))
                .as("쓰기가 있었으므로 디스크 write 는 줄어들 수 없다")
                .isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("6번 문제가 쓸 갱신·락 카운터가 서버에 존재한다")
    void 갱신_카운터가_있다() {
        Map<String, Long> counters = listener.readCounters();

        assertThat(counters)
                .as("이 지표들이 없으면 '왕복만 줄었고 갱신 행 수는 같다' 를 증명할 수 없다")
                .containsKeys(DatabaseWorkloadListener.UPDATE_STATEMENTS,
                        DatabaseWorkloadListener.ROWS_UPDATED,
                        DatabaseWorkloadListener.LOCK_WAIT_TIME,
                        DatabaseWorkloadListener.LOCK_WAITS);
    }

    @Test
    @DisplayName("행별 UPDATE 는 문 수와 갱신 행 수가 함께 늘어난다 - 6번 before 의 1:1 왕복")
    void 행별_UPDATE_는_행마다_왕복한다() {
        for (int i = 1; i <= ROWS; i++) {
            jdbcTemplate.update("""
                    INSERT INTO member_a (email, name, grade, point, status, created_at)
                    VALUES (?, '측정', 'BRONZE', 0, 'ACTIVE', NOW(6))""", "probe" + i + "@example.com");
        }
        Map<String, Long> before = listener.readCounters();

        for (Long id : jdbcTemplate.queryForList("SELECT id FROM member_a", Long.class)) {
            jdbcTemplate.update("UPDATE member_a SET grade = 'SILVER' WHERE id = ?", id);
        }

        Map<String, Long> after = listener.readCounters();

        assertThat(delta(before, after, DatabaseWorkloadListener.UPDATE_STATEMENTS))
                .as("UPDATE 를 %d번 보냈으므로 문 수가 그만큼은 늘어야 한다", ROWS)
                .isGreaterThanOrEqualTo(ROWS);
        assertThat(delta(before, after, DatabaseWorkloadListener.ROWS_UPDATED))
                .as("갱신된 행도 %d 건이다. 6번의 after 는 이 값을 유지한 채 문 수만 줄인다", ROWS)
                .isGreaterThanOrEqualTo(ROWS);
    }

    private long delta(Map<String, Long> before, Map<String, Long> after, String counter) {
        return after.get(counter) - before.get(counter);
    }
}
