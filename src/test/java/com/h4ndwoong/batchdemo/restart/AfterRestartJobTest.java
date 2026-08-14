package com.h4ndwoong.batchdemo.restart;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 5번 문제 after 구성({@link AfterRestartJobConfig})을 실제 MariaDB 에 실행해 검증한다.
 *
 * <p>상속받은 계약 시험이 "before 와 같은 일을 한다" 를 보이고, 여기 있는 시험이 <b>몇 번을 돌려도
 * 같은 답을 낸다</b> 를 보인다. 5번의 대사는 프로파일 사이가 아니라 <b>실행과 실행 사이</b>에서
 * 이루어지므로, 같은 지문이 두 번 나오는 것이 이 문제의 결승선이다.
 */
@ActiveProfiles("after")
class AfterRestartJobTest extends RestartJobContract {

    @Test
    @DisplayName("재실행은 읽을 것이 없다 - 0건 처리 COMPLETED 가 정답인 유일한 문제")
    void 재실행은_읽을_것이_없다() throws Exception {
        assertThat(launch(run()).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        PointBalanceChecksum afterFirst = balanceReporter.current();

        JobExecution second = launch(nextRun());

        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(step(second).getReadCount())
                .as("processed = 0 인 활성 회원이 한 명도 없다").isZero();
        assertThat(step(second).getWriteCount()).isZero();
        assertThat(balanceReporter.current())
                .as("두 실행의 지문이 완전히 같아야 멱등이다")
                .isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("실패 후 새 인스턴스로 다시 돌려도 정확하다 - 프레임워크가 아니라 데이터가 기억한다")
    void 실패_후_재실행() throws Exception {
        assertThat(launch(failing()).getStatus()).isEqualTo(BatchStatus.FAILED);

        JobExecution second = launch(nextRun());

        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(step(second).getReadCount())
                .as("새 인스턴스에는 물려받을 실행 컨텍스트가 없다. 남은 것을 아는 것은 데이터뿐이다")
                .isEqualTo(RestartFixture.activeCount() - RestartFixture.FAIL_AFTER);
        assertThat(pointSum())
                .as("before 는 같은 상황에서 앞 %d 건을 두 번 깎는다", RestartFixture.FAIL_AFTER)
                .isEqualTo(RestartFixture.expectedPointSum(1));
    }

    @Test
    @DisplayName("처리한 행에만 흔적이 남고 멱등키는 모두 유일하다")
    void 흔적이_남는다() throws Exception {
        launch(run());

        assertThat(balanceReporter.current().processedRows()).isEqualTo(RestartFixture.activeCount());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT idempotency_key) FROM member_e WHERE idempotency_key IS NOT NULL",
                Long.class))
                .as("여러 행이 한 키로 뭉개지면 처리 이력이 거짓이 된다")
                .isEqualTo(RestartFixture.activeCount());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM member_e WHERE status <> 'ACTIVE' AND processed = 1", Long.class))
                .as("대상이 아닌 행에 흔적이 남으면 다음 실행이 영원히 건너뛴다").isZero();
    }

    @Test
    @DisplayName("음수 잔액이 1회 차감분에서 늘지 않는다 - 재실행해도 개별 행이 망가지지 않는다")
    void 음수_잔액이_늘지_않는다() throws Exception {
        launch(run());
        launch(nextRun());

        assertThat(balanceReporter.current().negativeRows())
                .as("잔액을 검사하지 않으므로 1회 차감에서도 음수는 생긴다. 그 수가 늘지 않는 것이 멱등이다")
                .isEqualTo(RestartFixture.negativeRows(1));
    }

    @Test
    @DisplayName("마킹이 왕복을 늘리지 않는다 - 차감과 표시가 한 문장이다")
    void 마킹은_공짜다() throws Exception {
        launch(run());

        assertThat(workloadListener.lastDelta().get("COM_UPDATE"))
                .as("표시를 별도 문장으로 나눴다면 행당 2회, 즉 %d 회를 넘었을 것이다",
                        2 * RestartFixture.activeCount())
                .isLessThan(RestartFixture.activeCount() * 3 / 2);
    }

    @Test
    @DisplayName("멱등키 UNIQUE 제약이 만들어진다")
    void 제약이_생긴다() throws Exception {
        launch(run());

        assertThat(indexExists()).isTrue();
    }

    @Test
    @DisplayName("서로 다른 행이 같은 키를 갖는 것은 DB 가 거절한다 - UK 가 막는 것은 이것이다")
    void 중복_키는_거절된다() throws Exception {
        launch(run());
        List<Long> ids = processedIds();

        String key = jdbcTemplate.queryForObject(
                "SELECT idempotency_key FROM member_e WHERE id = ?", String.class, ids.get(0));

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE member_e SET idempotency_key = ? WHERE id = ?", key, ids.get(1)))
                .as("키 생성 규칙이 잘못되어 여러 행이 한 키로 뭉개지는 것을 여기서 막는다")
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("NULL 키는 제약을 통과한다 - before 에 UK 를 걸어도 아무것도 막지 못하는 이유")
    void null_키는_통과한다() throws Exception {
        launch(run());
        List<Long> ids = processedIds();

        assertThatCode(() -> jdbcTemplate.update(
                "UPDATE member_e SET idempotency_key = NULL WHERE id IN (?, ?)", ids.get(0), ids.get(1)))
                .as("UNIQUE 제약은 NULL 을 중복으로 보지 않는다. 흔적을 '쓰는' 코드가 있어야 제약이 산다")
                .doesNotThrowAnyException();
    }

    private List<Long> processedIds() {
        return jdbcTemplate.queryForList(
                "SELECT id FROM member_e WHERE processed = 1 ORDER BY id LIMIT 2", Long.class);
    }

    private boolean indexExists() {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'member_e'
                  AND index_name = 'uk_member_e_idem'""", Long.class);
        return count != null && count > 0;
    }
}
