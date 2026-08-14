package com.h4ndwoong.batchdemo.update;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;

import java.util.ArrayList;
import java.util.List;

/**
 * 슬라이스별 측정치를 모아 Step 이 끝날 때 보고한다. 6번 문제 after 의 <b>측정 장치</b>다.
 *
 * <p><b>왜 라이터가 직접 보고하지 않는가</b><br>
 * 라이터가 바뀌는 이유(SQL 을 바꾼다, 조건을 더한다)와 보고가 바뀌는 이유(표 대신 CSV, 요약 항목
 * 추가)는 다르다. 라이터는 "몇 번째 슬라이스가 몇 행을 몇 나노초에 갱신했다" 만 넘긴다. 3번의
 * {@code PageTimingRecorder} 와 같은 분리다.
 *
 * <p><b>{@code SLICE_UPDATE} 접두 로그</b><br>
 * 6번의 관심사 하나가 "슬라이스 크기를 키우면 락 유지 시간이 어떻게 자라는가" 이고, 그것은 표가
 * 아니라 그래프로 봐야 한다.
 * <pre>{@code
 * ./gradlew bootRun ... | grep -o 'SLICE_UPDATE,.*' > data/update-after.csv
 * }</pre>
 *
 * <p><b>before 에는 이 리스너가 없다.</b> before 의 락 단위는 청크이고, 그 시간은 Step 통계의
 * 커밋 횟수와 총 시간으로 이미 나눠진다. 측정 장치를 억지로 양쪽에 맞추기보다, <b>after 에만 있는
 * 위험(한 문장이 오래 잠근다)을 after 에서 재는 편</b>이 정직하다.
 */
public class SliceUpdateRecorder implements StepExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(SliceUpdateRecorder.class);

    private static final String CSV_MARKER = "SLICE_UPDATE";

    private final List<SliceUpdate> slices = new ArrayList<>();

    private String stepName = "";

    /**
     * 슬라이스 하나의 측정치를 받는다. {@link SetBasedGradeUpdateItemWriter} 가 문장 실행 직후 호출한다.
     *
     * @param slice        처리한 구간
     * @param updatedRows  갱신된 행 수
     * @param elapsedNanos 걸린 시간(나노초)
     */
    public void record(IdSlice slice, long updatedRows, long elapsedNanos) {
        slices.add(new SliceUpdate(slice, updatedRows, elapsedNanos));
    }

    /**
     * 지금까지의 측정 결과.
     *
     * <p>로그로만 남기면 테스트가 "왕복이 슬라이스 수와 같은가", "갱신 행 수가 before 와 같은가" 를
     * 확인할 수 없다.
     *
     * @return 보고. 측정치가 없으면 {@link SliceUpdateReport#EMPTY}
     */
    public SliceUpdateReport report() {
        return slices.isEmpty() ? SliceUpdateReport.EMPTY : new SliceUpdateReport(slices);
    }

    /**
     * {@inheritDoc}
     *
     * <p>이전 실행의 기록을 지운다. 테스트가 한 컨텍스트에서 Job 을 여러 번 실행하므로, 비우지 않으면
     * 두 번째 실행의 보고에 첫 실행의 슬라이스가 섞인다.
     */
    @Override
    public void beforeStep(StepExecution stepExecution) {
        slices.clear();
        stepName = stepExecution.getStepName();
    }

    /**
     * {@inheritDoc}
     *
     * <p>측정 실패가 이미 끝난 Step 의 결과를 바꾸지 않도록 {@code null} 을 돌려준다.
     */
    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        SliceUpdateReport report = report();
        if (report.sliceCount() > 0) {
            log.info("\n{}", format(report));
        }
        return null;
    }

    private String format(SliceUpdateReport report) {
        StringBuilder text = new StringBuilder()
                .append("==== 슬라이스별 집합 UPDATE: ").append(stepName).append(" ====\n")
                .append(String.format("  %6s %14s %14s %12s %14s%n",
                        "slice", "from_id", "to_id", "rows", "elapsed(ms)"));

        for (SliceUpdate slice : report.slices()) {
            text.append(String.format("  %6d %14d %14d %,12d %14.1f%n",
                    slice.slice().index(), slice.slice().fromId(), slice.slice().toId(),
                    slice.updatedRows(), slice.elapsedMillis()));
        }

        text.append(String.format("  요약: 슬라이스 %,d개 = UPDATE 왕복 %,d회, 갱신 %,d행, 총 %,.1f ms%n",
                report.sliceCount(), report.sliceCount(), report.totalUpdatedRows(), report.totalMillis()));
        text.append(String.format("        문장당 평균 %,.1f ms / 최대 %,.1f ms (= 락 유지 시간 상한)%n",
                report.averageMillis(), report.maxElapsedMillis()));

        for (SliceUpdate slice : report.slices()) {
            text.append(String.format("%s,%d,%d,%d,%d,%.3f%n", CSV_MARKER,
                    slice.slice().index(), slice.slice().fromId(), slice.slice().toId(),
                    slice.updatedRows(), slice.elapsedMillis()));
        }
        return text.toString();
    }
}
