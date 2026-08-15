package com.h4ndwoong.batchdemo.outbox;

import com.h4ndwoong.batchdemo.support.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Outbox 릴레이가 <b>exactly-once 가 아니라 at-least-once</b> 라는 사실을 실행으로 확인한다.
 *
 * <p>{@link AfterOutboxJobTest} 는 after 가 이기는 항목들을 고정한다. 이 시험은 <b>지는 항목</b>을
 * 고정한다. 발송과 {@code SENT} 표시는 원자적으로 묶을 수 없다 — 하나는 외부에서, 하나는 DB 에서
 * 일어나기 때문이다. 그 사이에서 죽으면 그 청크는 통째로 재발송된다.
 *
 * <blockquote>
 * 개선안이 무엇을 해결하지 <em>못하는가</em> 를 말할 수 없으면 그것은 개선안이 아니다.
 * </blockquote>
 *
 * <p><b>중복의 상한은 릴레이 청크 크기다.</b> 여기서는 청크를
 * {@value OutboxJobCommonConfig#CHUNK_SIZE} 로 두고 그 절반을 보낸 뒤 실패시킨다. 재실행하면 그
 * 청크가 처음부터 다시 나가므로 앞의 절반이 중복이 된다 — <b>실패 지점이 곧 중복 건수</b>다.
 *
 * <p><b>테스트 메서드가 하나뿐인 이유</b><br>
 * 발송 장애 주입기는 싱글턴이고 정해진 횟수만 던진다 (그래야 재실행에서 회복되어 재발송을 관찰할
 * 수 있다). 같은 컨텍스트에서 두 번째 메서드가 실행되면 장애는 이미 소진되어 있다.
 */
@SpringBootTest(properties = {
        TestDatabase.URL,
        TestDatabase.BATCH_SCHEMA,
        TestDatabase.DOMAIN_SCHEMA,
        OutboxFixture.SENDER_LOG_LEVEL,
        "outbox.send-fail-after=" + OutboxRelayFailureTest.SENDS_BEFORE_FAILURE,
        "outbox.send-fail-times=1"
})
@ActiveProfiles("after")
class OutboxRelayFailureTest {

    /** 이 건수를 보낸 뒤 발송이 실패한다. 청크 하나의 절반이라 재발송에서 그만큼이 중복이 된다. */
    static final long SENDS_BEFORE_FAILURE = OutboxJobCommonConfig.CHUNK_SIZE / 2;

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private Job outboxJob;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private NotificationRecorder recorder;

    @Autowired
    private NotificationDeliveryReporter reporter;

    private final long runId = System.nanoTime();

    @BeforeEach
    void 데이터를_채운다() {
        OutboxFixture.seed(jdbcTemplate);
        recorder.reset();
    }

    @AfterEach
    void 실습_테이블을_비운다() {
        jdbcTemplate.execute("TRUNCATE TABLE " + OutboxJobCommonConfig.TABLE);
        jdbcTemplate.execute("TRUNCATE TABLE " + OutboxJobCommonConfig.OUTBOX_TABLE);
    }

    @Test
    @DisplayName("발송 도중 죽으면 그 청크는 통째로 재발송된다 - after 도 exactly-once 는 아니다")
    void 릴레이는_at_least_once_다() throws Exception {
        JobExecution first = jobLauncher.run(outboxJob,
                new JobParametersBuilder().addLong("run.id", runId).toJobParameters());

        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        NotificationDeliveryChecksum afterFailure = reporter.current();
        assertThat(afterFailure.sendAttempts())
                .as("실패 직전까지 나간 것은 되돌아오지 않는다")
                .isEqualTo(SENDS_BEFORE_FAILURE);
        assertThat(afterFailure.outboxSent())
                .as("SENT 표시는 청크 커밋과 함께 롤백되었다")
                .isZero();
        assertThat(afterFailure.outboxPending())
                .as("이미 나간 것까지 PENDING 으로 남는다 - 여기가 중복의 씨앗이다")
                .isEqualTo(OutboxFixture.targetCount());

        JobExecution second = jobLauncher.run(outboxJob,
                new JobParametersBuilder().addLong("run.id", runId + 1).toJobParameters());

        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        NotificationDeliveryChecksum afterRetry = reporter.current();
        assertThat(afterRetry.distinctKeys())
                .as("아무도 알림을 못 받지는 않았다 - 유실은 없다")
                .isEqualTo(OutboxFixture.targetCount());
        assertThat(afterRetry.duplicateSends())
                .as("중복의 상한은 릴레이 청크 크기이며, 실제 중복은 실패 지점까지다")
                .isEqualTo(SENDS_BEFORE_FAILURE);
        assertThat(afterRetry.outboxPending()).isZero();
    }
}
