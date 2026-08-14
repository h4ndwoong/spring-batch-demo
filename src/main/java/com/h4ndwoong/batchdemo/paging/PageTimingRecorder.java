package com.h4ndwoong.batchdemo.paging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;

import java.util.ArrayList;
import java.util.List;

/**
 * 페이지별 소요 시간을 모아 Step 이 끝날 때 보고한다. 3번 문제의 <b>측정 장치</b>다.
 *
 * <p><b>왜 리더가 직접 보고하지 않는가</b><br>
 * 리더가 바뀌는 이유(SQL 을 offset 에서 키셋으로)와 보고가 바뀌는 이유(표 대신 CSV, 구간 크기 조정)는
 * 서로 다르다. 리더는 "몇 번째 페이지가 몇 나노초 걸렸다" 만 넘기고, 그 값을 무엇으로 만들지는
 * 여기서 정한다. 덕분에 before 와 after 는 <b>문자 그대로 같은 계측기</b>를 쓴다 — 측정 방식이
 * 프로파일마다 다르면 비교 자체가 성립하지 않는다.
 *
 * <p><b>{@code PAGE_TIMING} 접두 로그</b><br>
 * 표는 사람이 읽고, {@code PAGE_TIMING} 줄은 그래프를 그리기 위한 것이다. 3번 문제의 측정 지표가
 * "페이지 번호별 소요 시간 그래프" 라서 값이 로그 안에만 있으면 쓸모가 없다.
 * <pre>{@code
 * ./gradlew bootRun ... | grep -o 'PAGE_TIMING,.*' > data/before.csv
 * }</pre>
 *
 * <p><b>상태를 필드로 들고 있다.</b> {@link #beforeStep} 에서 비우므로 한 Step 실행의 기록만 남는다.
 * 이 실습은 Job 을 하나씩 순차 실행하고 Step 도 하나뿐이라는 전제 위에 있다
 * ({@code DatabaseWorkloadListener} 와 같은 전제다). 병렬 Step 이 생기면 이 가정은 깨진다.
 */
public class PageTimingRecorder implements StepExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(PageTimingRecorder.class);

    /**
     * 앞·뒤 구간에 넣을 페이지 수.
     *
     * <p>10인 이유는 한 장짜리 비교가 버퍼 풀 상태나 백그라운드 flush 에 흔들리기 때문이고,
     * 100이면 2000페이지 실행에서는 괜찮아도 {@code pages=20} 같은 짧은 실행에서 앞뒤 구간이
     * 겹쳐 버리기 때문이다.
     */
    public static final int WINDOW = 10;

    private static final String CSV_MARKER = "PAGE_TIMING";

    private final List<PageTiming> pages = new ArrayList<>();

    private String stepName = "";

    /**
     * 페이지 한 장의 측정치를 받는다. {@link MeasuredPagingItemReader} 가 SQL 발행 직후 호출한다.
     *
     * @param page         페이지 번호. 1부터 센다
     * @param rows         그 페이지가 돌려준 행 수
     * @param elapsedNanos 걸린 시간(나노초)
     */
    public void record(int page, int rows, long elapsedNanos) {
        pages.add(new PageTiming(page, rows, elapsedNanos));
    }

    /**
     * 지금까지의 측정 결과.
     *
     * <p>로그로만 남기면 테스트가 "뒤 페이지가 느려졌는가" 를 확인할 수 없다.
     *
     * @return 보고. 측정치가 없으면 {@link PageTimingReport#EMPTY}
     */
    public PageTimingReport report() {
        if (pages.isEmpty()) {
            return PageTimingReport.EMPTY;
        }
        return new PageTimingReport(pages, WINDOW);
    }

    /**
     * {@inheritDoc}
     *
     * <p>이전 실행의 기록을 지운다. 테스트가 한 컨텍스트에서 Job 을 여러 번 실행하므로, 비우지 않으면
     * 두 번째 실행의 보고에 첫 실행의 페이지가 섞여 "앞 구간" 이 엉뚱한 값이 된다.
     */
    @Override
    public void beforeStep(StepExecution stepExecution) {
        pages.clear();
        stepName = stepExecution.getStepName();
    }

    /**
     * {@inheritDoc}
     *
     * <p>측정 실패가 이미 끝난 Step 의 결과를 바꾸지 않도록 {@code null} 을 돌려준다
     * ({@code ExitStatus} 를 그대로 둔다는 뜻이다).
     */
    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        PageTimingReport report = report();
        if (report.pageCount() > 0) {
            log.info("\n{}", format(report));
        }
        return null;
    }

    private String format(PageTimingReport report) {
        StringBuilder text = new StringBuilder()
                .append("==== 페이지별 소요 시간: ").append(stepName).append(" ====\n")
                .append(String.format("  %6s %8s %14s %16s%n", "page", "rows", "elapsed(ms)", "cumulative(ms)"));

        double cumulative = 0;
        for (PageTiming page : report.pages()) {
            cumulative += page.elapsedMillis();
            text.append(String.format("  %6d %8d %14.1f %16.1f%n",
                    page.page(), page.rows(), page.elapsedMillis(), cumulative));
        }

        text.append(String.format("  요약: 페이지 %,d장, 총 읽기 %,.1f ms%n",
                report.pageCount(), report.totalMillis()));
        text.append(String.format("        첫 %d페이지 평균 %,.1f ms / 마지막 %d페이지 평균 %,.1f ms → %.1f배%n",
                report.window(), report.firstWindowAverageMillis(),
                report.window(), report.lastWindowAverageMillis(), report.growthRatio()));

        for (PageTiming page : report.pages()) {
            text.append(String.format("%s,%d,%d,%.3f%n",
                    CSV_MARKER, page.page(), page.rows(), page.elapsedMillis()));
        }
        return text.toString();
    }
}
