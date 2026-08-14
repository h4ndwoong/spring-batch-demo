package com.h4ndwoong.batchdemo.lookup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;

/**
 * 추천인 조회 계측치를 Step 이 끝날 때 보고한다. 4번 문제의 <b>측정 장치</b>다.
 *
 * <p><b>왜 조회기가 직접 보고하지 않는가</b><br>
 * 조회 전략이 바뀌는 이유(SQL 을 어떻게 묶는가)와 보고가 바뀌는 이유(표에 항목을 더한다)는 다르다.
 * 조회기는 "몇 번 요구받아 몇 번 왕복했다" 만 들고 있고, 그 값을 무엇으로 만들지는 여기서 정한다.
 * 덕분에 before 와 after 는 <b>문자 그대로 같은 계측기</b>를 쓴다 — 측정 방식이 프로파일마다 다르면
 * 비교 자체가 성립하지 않는다. 3번의 {@code PageTimingRecorder} 와 같은 판단이다.
 *
 * <p>{@code beforeStep} 에서 조회기를 초기화하는 것도 여기 있다. 상태를 비우는 시점이 여러 곳에
 * 흩어지면, 한 컨텍스트에서 Job 을 두 번 실행하는 테스트에서 두 번째 보고에 첫 실행이 섞인다.
 */
public class ReferrerLookupReporter implements StepExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(ReferrerLookupReporter.class);

    private final ReferrerLookup referrerLookup;
    private final int chunkSize;

    /**
     * 보고자를 만든다.
     *
     * @param referrerLookup 계측치를 들고 있는 조회 전략
     * @param chunkSize      이 Step 의 청크 크기. after 의 "청크당 1회" 를 눈으로 확인하는 데 쓴다
     */
    public ReferrerLookupReporter(ReferrerLookup referrerLookup, int chunkSize) {
        this.referrerLookup = referrerLookup;
        this.chunkSize = chunkSize;
    }

    /**
     * {@inheritDoc}
     *
     * <p>조회기의 계측치와 청크 상태를 비운다.
     */
    @Override
    public void beforeStep(StepExecution stepExecution) {
        referrerLookup.reset();
    }

    /**
     * {@inheritDoc}
     *
     * <p>측정 실패가 이미 끝난 Step 의 결과를 바꾸지 않도록 {@code null} 을 돌려준다.
     */
    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        ReferrerLookupStats stats = referrerLookup.stats();
        log.info("\n{}", format(stepExecution, stats));
        return null;
    }

    private String format(StepExecution stepExecution, ReferrerLookupStats stats) {
        return new StringBuilder()
                .append("==== 추천인 조회: ").append(stepExecution.getStepName()).append(" ====\n")
                .append(String.format("  %-18s %15s%n", "전략", referrerLookup.getClass().getSimpleName()))
                .append(String.format("  %-18s %,15d%n", "읽은 행", stepExecution.getReadCount()))
                .append(String.format("  %-18s %,15d%n", "조회 요구", stats.lookups()))
                .append(String.format("  %-18s %,15d%n", "SELECT 왕복", stats.queries()))
                .append(String.format("  %-18s %15.4f  (2.0 이면 행마다 2회 왕복)%n",
                        "왕복 / 조회", stats.queriesPerLookup()))
                .append(String.format("  %-18s %,15d%n", "청크 크기", chunkSize))
                .append(String.format("  %-18s %,15d  (청크 안에서 아낀 조회)%n",
                        "중복 제거", stats.deduplicated()))
                .toString();
    }
}
