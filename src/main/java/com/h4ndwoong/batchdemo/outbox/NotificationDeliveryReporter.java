package com.h4ndwoong.batchdemo.outbox;

import com.h4ndwoong.batchdemo.domain.MemberStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashSet;
import java.util.Set;

/**
 * DB 의 상태와 실제로 나간 알림을 대조해 {@link NotificationDeliveryChecksum} 을 남긴다.
 * <b>양쪽 공통</b>이다.
 *
 * <p><b>왜 Step 리스너가 아니라 Job 리스너인가</b><br>
 * 1~6번의 리포터는 Step 앞뒤에서 쟀다. 7번의 after 는 <b>Step 이 두 개</b>이고 (적재 → 릴레이),
 * 알림은 두 번째 Step 에서 나간다. Step 리스너로 두면 첫 Step 이 끝난 시점에 "발송 0건" 을 재고
 * 끝나 버린다. 재야 할 것은 <b>Job 이 끝난 뒤의 세상</b>이다.
 *
 * <p><b>유령 알림을 세는 방법</b><br>
 * "알림을 받았는데 상태는 그대로인 회원" 은 어느 한쪽만 봐서는 알 수 없다. {@link NotificationRecorder}
 * 가 아는 <em>받은 사람</em>과 DB 가 아는 <em>아직 {@code ACTIVE} 인 사람</em>을 교집합해야 나온다.
 * 7번에서 측정이 어려운 이유가 여기 있다 — <b>증상이 두 시스템의 불일치로만 정의된다.</b>
 *
 * <p>{@code ACTIVE} 인 식별자를 전부 가져와 메모리에서 교집합한다. {@code IN} 절에 수만 개의 값을
 * 넣는 것보다 낫고, 대상 규모가 10만인 실습에서 한 번의 스캔은 감당할 수 있다.
 *
 * <p><b>이 리포터의 조회는 측정 범위 안에 들어간다.</b> {@code DatabaseWorkloadListener} 를 Job
 * 리스너의 <b>맨 앞</b>에 등록하면 {@code afterJob} 이 역순으로 불리므로 여기서 쓰는 {@code SELECT}
 * 도 카운터에 잡힌다. 양쪽에 똑같이 걸리므로 비교 축은 흔들리지 않는다 (6번의
 * {@code GradeRecalcReporter} 와 같은 판단이다).
 */
public class NotificationDeliveryReporter implements JobExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryReporter.class);

    private static final String MEMBER_STATUS_SQL = """
            SELECT status, COUNT(*) AS row_count
            FROM member_g
            GROUP BY status""";

    /** {@code updated_at IS NOT NULL} 이 <b>이 배치가 건드린 행</b>의 정의다 (6번과 같다). */
    private static final String CHANGED_ROWS_SQL =
            "SELECT COUNT(*) FROM member_g WHERE updated_at IS NOT NULL";

    private static final String ACTIVE_IDS_SQL =
            "SELECT id FROM member_g WHERE status = 'ACTIVE'";

    private static final String OUTBOX_STATUS_SQL = """
            SELECT status, COUNT(*) AS row_count
            FROM member_g_outbox
            GROUP BY status""";

    private final JdbcTemplate jdbcTemplate;
    private final NotificationRecorder recorder;

    private NotificationDeliveryChecksum last = NotificationDeliveryChecksum.EMPTY;

    /**
     * 리포터를 만든다.
     *
     * @param jdbcTemplate JDBC 템플릿
     * @param recorder     나간 알림을 아는 유일한 장치
     */
    public NotificationDeliveryReporter(JdbcTemplate jdbcTemplate, NotificationRecorder recorder) {
        this.jdbcTemplate = jdbcTemplate;
        this.recorder = recorder;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>실패한 Job 에서도 남긴다.</b> 7번에서 가장 중요한 측정은 실패한 실행 직후의 것이다 —
     * 유령 알림은 그때만 보인다.
     */
    @Override
    public void afterJob(JobExecution jobExecution) {
        try {
            last = current();
            log.info("==== 알림 전달 지문: {} ({}) ====\n  {}",
                    jobExecution.getJobInstance().getJobName(), jobExecution.getStatus(), last.summary());
        }
        catch (RuntimeException e) {
            log.warn("알림 전달 지문을 읽지 못했다. 이미 나간 알림에는 영향이 없다.", e);
        }
    }

    /**
     * 지금 이 순간의 지문. Job 과 무관하게 언제든 잴 수 있다.
     *
     * @return 지문
     */
    public NotificationDeliveryChecksum current() {
        Set<Long> activeIds = new HashSet<>(jdbcTemplate.queryForList(ACTIVE_IDS_SQL, Long.class));

        long[] members = new long[2];
        jdbcTemplate.query(MEMBER_STATUS_SQL, resultSet -> {
            MemberStatus status = MemberStatus.valueOf(resultSet.getString("status"));
            long rows = resultSet.getLong("row_count");
            if (status == MemberStatus.ACTIVE) {
                members[0] += rows;
            }
            else if (status == MemberStatus.DORMANT) {
                members[1] += rows;
            }
        });

        long[] outbox = new long[3];
        jdbcTemplate.query(OUTBOX_STATUS_SQL, resultSet -> {
            OutboxStatus status = OutboxStatus.valueOf(resultSet.getString("status"));
            long rows = resultSet.getLong("row_count");
            outbox[0] += rows;
            if (status == OutboxStatus.PENDING) {
                outbox[1] += rows;
            }
            else if (status == OutboxStatus.SENT) {
                outbox[2] += rows;
            }
        });

        return new NotificationDeliveryChecksum(
                members[0],
                members[1],
                changedRows(),
                recorder.attemptCount(),
                recorder.distinctKeyCount(),
                recorder.duplicateCount(),
                phantomSends(activeIds),
                outbox[0], outbox[1], outbox[2]);
    }

    /**
     * 마지막으로 끝난 Job 의 지문.
     *
     * @return 지문. Job 이 끝난 적이 없으면 {@link NotificationDeliveryChecksum#EMPTY}
     */
    public NotificationDeliveryChecksum lastChecksum() {
        return last;
    }

    /**
     * 알림은 받았는데 상태는 그대로인 회원 수.
     *
     * @param activeIds 아직 {@code ACTIVE} 인 회원 식별자
     * @return 유령 알림을 받은 회원 수
     */
    private long phantomSends(Set<Long> activeIds) {
        return recorder.memberIds().stream().filter(activeIds::contains).count();
    }

    private long changedRows() {
        Long count = jdbcTemplate.queryForObject(CHANGED_ROWS_SQL, Long.class);
        return count == null ? 0L : count;
    }
}
