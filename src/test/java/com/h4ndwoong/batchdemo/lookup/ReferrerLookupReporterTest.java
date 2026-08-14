package com.h4ndwoong.batchdemo.lookup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 측정 장치({@link ReferrerLookupReporter})가 실행 경계에서 계측치를 비우는지 확인한다.
 *
 * <p>사소해 보이지만 이 초기화가 없으면 <b>한 컨텍스트에서 Job 을 두 번 돌리는 테스트</b>에서
 * 두 번째 실행의 왕복이 첫 실행과 합쳐져 배율이 두 배로 보인다. 측정 장치가 조용히 거짓말을 하는
 * 형태라 눈치채기 어렵다.
 */
class ReferrerLookupReporterTest {

    @Test
    @DisplayName("Step 시작 시 조회기의 계측치를 비운다")
    void 실행마다_초기화() {
        RecordingLookup lookup = new RecordingLookup();
        ReferrerLookupReporter reporter = new ReferrerLookupReporter(lookup, 1_000);

        reporter.beforeStep(stepExecution());

        assertThat(lookup.resetCalls).isEqualTo(1);
    }

    @Test
    @DisplayName("Step 결과를 바꾸지 않는다 - 측정이 실행의 성패를 뒤집으면 안 된다")
    void 종료_상태를_건드리지_않는다() {
        ReferrerLookupReporter reporter = new ReferrerLookupReporter(new RecordingLookup(), 1_000);

        assertThat(reporter.afterStep(stepExecution())).isNull();
    }

    private static StepExecution stepExecution() {
        return new StepExecution("lookupStep", new JobExecution(1L));
    }

    /** {@code reset} 호출 횟수만 기억하는 가짜 조회기. */
    private static class RecordingLookup implements ReferrerLookup {

        private int resetCalls;

        @Override
        public Optional<Referrer> find(Long referrerId) {
            return Optional.empty();
        }

        @Override
        public ReferrerLookupStats stats() {
            return ReferrerLookupStats.EMPTY;
        }

        @Override
        public void reset() {
            resetCalls++;
        }
    }
}
