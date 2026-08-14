package com.h4ndwoong.batchdemo.skip;

import com.h4ndwoong.batchdemo.domain.MemberB;
import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.domain.MemberGrade;
import com.h4ndwoong.batchdemo.domain.MemberStatus;
import com.h4ndwoong.batchdemo.support.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link ErrorRowRecorder} 를 실제 {@code member_b_error} 테이블에 실행해 검증한다.
 *
 * <p>격리 기록의 저장이 실패하면 그 예외는 청크 트랜잭션을 타고 올라가 <b>Step 을 죽인다.</b>
 * 오염 행 하나 때문에 Step 이 죽는 것은 정확히 before 의 증상이므로, 격리 장치가 그 증상을 다시
 * 만들어 내면 after 는 개선이 아니다. 그래서 길이 초과·{@code null} 같은 "기록의 가장자리" 를
 * 여기서 고정한다.
 */
@SpringBootTest(properties = {
        TestDatabase.URL,
        TestDatabase.BATCH_SCHEMA,
        TestDatabase.DOMAIN_SCHEMA
})
class ErrorRowRecorderTest {

    private static final LocalDateTime SKIPPED_AT = LocalDateTime.of(2026, 8, 14, 19, 0, 0);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ErrorRowRecorder recorder;

    @BeforeEach
    void 격리_테이블을_비운다() {
        jdbcTemplate.execute("TRUNCATE TABLE member_b_error");
        recorder = new ErrorRowRecorder(jdbcTemplate);
    }

    /** 다른 테스트가 격리 건수를 세므로 기록을 남기고 끝내지 않는다. */
    @AfterEach
    void 격리_테이블을_정리한다() {
        jdbcTemplate.execute("TRUNCATE TABLE member_b_error");
    }

    @Test
    @DisplayName("스킵된 행이 원인·단계·원문과 함께 적재된다")
    void 격리_적재() {
        MemberBase member = member(200L, "invalid-email-200");
        MemberValidationException cause =
                new MemberValidationException(200L, ValidationRule.EMAIL_FORMAT, "invalid-email-200");

        recorder.record(SkippedRow.of(SkipPhase.PROCESS, member, cause, 41L, SKIPPED_AT));

        Map<String, Object> row = onlyRow();
        assertThat(row.get("member_id")).isEqualTo(200L);
        assertThat(row.get("phase")).isEqualTo("PROCESS");
        assertThat((String) row.get("raw_item"))
                .as("원본이 나중에 수정되어도 당시 값이 남아야 한다")
                .contains("id=200", "email=invalid-email-200", "MemberB");
        assertThat(row.get("exception_type")).isEqualTo(MemberValidationException.class.getName());
        assertThat((String) row.get("message")).contains("EMAIL_FORMAT");
        assertThat(row.get("step_execution_id")).isEqualTo(41L);
        assertThat(((java.sql.Timestamp) row.get("skipped_at")).toLocalDateTime()).isEqualTo(SKIPPED_AT);
    }

    @Test
    @DisplayName("긴 원문과 메시지는 잘려서 저장된다 - 격리가 길이 때문에 실패하면 안 된다")
    void 길이_절단() {
        SkippedRow row = new SkippedRow(200L, SkipPhase.PROCESS,
                "가".repeat(2_000), "e".repeat(500), "m".repeat(2_000), 41L, SKIPPED_AT);

        assertThatCode(() -> recorder.record(row)).doesNotThrowAnyException();

        Map<String, Object> stored = onlyRow();
        assertThat((String) stored.get("raw_item")).hasSize(ErrorRowRecorder.TEXT_LIMIT);
        assertThat((String) stored.get("message")).hasSize(ErrorRowRecorder.TEXT_LIMIT);
        assertThat((String) stored.get("exception_type")).hasSize(ErrorRowRecorder.TYPE_LIMIT);
    }

    @Test
    @DisplayName("읽기 단계 스킵은 항목이 없어도 기록된다")
    void 읽기_스킵() {
        recorder.record(SkippedRow.of(SkipPhase.READ, null,
                new IllegalStateException("커서가 죽었다"), 41L, SKIPPED_AT));

        Map<String, Object> row = onlyRow();
        assertThat(row.get("member_id")).as("어느 행인지 알 수 없는 것이 정상이다").isNull();
        assertThat(row.get("raw_item")).isNull();
        assertThat(row.get("phase")).isEqualTo("READ");
        assertThat(row.get("exception_type")).isEqualTo(IllegalStateException.class.getName());
    }

    @Test
    @DisplayName("항목이 없어도 예외가 식별자를 알고 있으면 남는다")
    void 예외에서_식별자_복구() {
        recorder.record(SkippedRow.of(SkipPhase.READ, null,
                new MemberValidationException(777L, ValidationRule.EMAIL_FORMAT, "bad"), 41L, SKIPPED_AT));

        assertThat(onlyRow().get("member_id")).isEqualTo(777L);
    }

    private Map<String, Object> onlyRow() {
        var rows = jdbcTemplate.queryForList("SELECT * FROM member_b_error");
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private MemberBase member(long id, String email) {
        return new MemberB(id, email, "김민준", MemberGrade.GOLD, 100L, MemberStatus.ACTIVE,
                null, false, null, LocalDateTime.of(2026, 1, 1, 0, 0), null);
    }
}
