package com.h4ndwoong.batchdemo.restart;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 5번 문제 before 구성({@link BeforeRestartJobConfig})을 실제 MariaDB 에 실행해 검증한다.
 *
 * <p>상속받은 계약 시험이 "after 와 같은 일을 하고, <b>재시작에서는 정확하다</b>" 를 보인다. 여기
 * 있는 시험은 <b>그 정확함이 새 JobInstance 를 만나면 끝난다</b> 를 고정한다 — 배치의 결과가
 * 실행 횟수의 함수가 되는 지점이다.
 *
 * <p>이 시험들이 통과한다는 것은 <b>버그가 재현된다</b>는 뜻이다. 깨진다면 before 가 어쩌다
 * 멱등해진 것이고, 그러면 5번 문제의 비교 축이 사라진다.
 */
@ActiveProfiles("before")
class BeforeRestartJobTest extends RestartJobContract {

    @Test
    @DisplayName("재실행하면 전량이 또 소멸한다 - 결과가 실행 횟수의 함수다")
    void 재실행하면_또_차감된다() throws Exception {
        assertThat(launch(run()).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(pointSum()).isEqualTo(RestartFixture.expectedPointSum(1));

        JobExecution second = launch(nextRun());

        assertThat(second.getStatus())
                .as("아무 예외도 나지 않는다. 배치는 초록불로 끝난다").isEqualTo(BatchStatus.COMPLETED);
        assertThat(step(second).getReadCount())
                .as("이미 처리한 행을 걸러 낼 조건이 없으므로 전량을 다시 읽는다")
                .isEqualTo(RestartFixture.activeCount());
        assertThat(pointSum())
                .as("멱등하다면 %d 여야 한다", RestartFixture.expectedPointSum(1))
                .isEqualTo(RestartFixture.expectedPointSum(2));
    }

    @Test
    @DisplayName("실패 후 새 인스턴스로 다시 돌리면 앞 구간이 두 번 차감된다 - README 가 말한 이중 소멸")
    void 실패_후_재실행() throws Exception {
        assertThat(launch(failing()).getStatus()).isEqualTo(BatchStatus.FAILED);

        launch(nextRun());

        assertThat(pointSum())
                .as("전량 1회(%d) + 실패 전 커밋분 %d 건의 두 번째 차감",
                        RestartFixture.expectedPointSum(1), RestartFixture.FAIL_AFTER)
                .isEqualTo(RestartFixture.expectedPointSum(1)
                        - RestartFixture.FAIL_AFTER * RestartJobCommonConfig.EXPIRE_AMOUNT);
    }

    @Test
    @DisplayName("두 번 차감된 만큼 음수 잔액이 늘어난다 - 총합을 되돌려도 복구되지 않는 피해")
    void 음수_잔액() throws Exception {
        launch(run());
        assertThat(negativeRows())
                .as("잔액을 검사하지 않으므로 1회 차감에서도 음수는 생긴다. 가르는 것은 그 개수다")
                .isEqualTo(RestartFixture.negativeRows(1));

        launch(nextRun());

        assertThat(negativeRows())
                .as("'음수 잔액이 있는가' 로는 이 사고를 잡지 못한다. 개수를 세어야 보인다")
                .isEqualTo(RestartFixture.negativeRows(2))
                .isGreaterThan(RestartFixture.negativeRows(1));
    }

    @Test
    @DisplayName("처리 흔적을 한 줄도 남기지 않는다 - 다음 실행이 참고할 것이 없다")
    void 흔적을_남기지_않는다() throws Exception {
        launch(run());

        assertThat(balanceReporter.current().processedRows()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM member_e WHERE idempotency_key IS NOT NULL", Long.class))
                .as("멱등키를 쓰지 않으므로 UNIQUE 제약을 걸어도 NULL 은 전부 통과한다").isZero();
    }

    @Test
    @DisplayName("멱등키 UNIQUE 제약이 없는 상태로 시작한다 - after 를 돌린 뒤에도")
    void 제약_없이_시작한다() throws Exception {
        jdbcTemplate.execute("ALTER TABLE member_e ADD UNIQUE KEY IF NOT EXISTS uk_member_e_idem (idempotency_key)");

        launch(run());

        assertThat(indexExists())
                .as("시작 상태를 사람의 기억이 아니라 Job 이 보장한다").isFalse();
    }

    private long negativeRows() {
        return balanceReporter.current().negativeRows();
    }

    private boolean indexExists() {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'member_e'
                  AND index_name = 'uk_member_e_idem'""", Long.class);
        return count != null && count > 0;
    }
}
