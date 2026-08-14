package com.h4ndwoong.batchdemo.update;

import com.h4ndwoong.batchdemo.support.DatabaseWorkloadListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.StepExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 6번 문제 after 구성({@link AfterUpdateJobConfig})을 실제 MariaDB 에 실행해 검증한다.
 *
 * <p>상속받은 계약 시험이 "before 와 같은 결과를 낸다" 를 보이고, 여기 있는 시험이 <b>그 결과를
 * 몇 번의 왕복으로 얻었는가</b> 를 보인다. 6번의 주장은 "덜 갱신했다" 가 아니라 <b>"같은 행을 갱신하되
 * 문장 수만 줄였다"</b> 이므로, 두 숫자를 반드시 나란히 확인한다.
 */
@ActiveProfiles("after")
class AfterUpdateJobTest extends UpdateJobContract {

    @Autowired
    private SliceUpdateRecorder sliceRecorder;

    @Test
    @DisplayName("Step 이 읽는 것은 회원이 아니라 id 구간이다")
    void 구간을_읽는다() throws Exception {
        StepExecution step = step(launch(run()));

        assertThat(step.getReadCount())
                .as("before 의 READ_COUNT 는 %d 다. 6번은 Step 통계로 비교할 수 없는 첫 문제다",
                        UpdateFixture.COUNT)
                .isEqualTo(UpdateFixture.sliceCount());
        assertThat(step.getWriteCount()).isEqualTo(UpdateFixture.sliceCount());
        assertThat(step.getCommitCount())
                .as("한 슬라이스 = 한 문장 = 한 트랜잭션이다")
                .isGreaterThanOrEqualTo(UpdateFixture.sliceCount());
    }

    @Test
    @DisplayName("갱신 행 수는 before 와 같고 왕복만 줄어든다 - 6번의 결승선")
    void 왕복만_줄었다() throws Exception {
        launch(run());

        SliceUpdateReport report = sliceRecorder.report();
        long roundTrips = DatabaseWorkloadListener.updateRoundTrips(workloadListener.lastDelta());

        assertThat(report.totalUpdatedRows())
                .as("before 의 WRITE_COUNT 와 같아야 한다. 다르면 개선이 아니라 일을 빠뜨린 것이다")
                .isEqualTo(UpdateFixture.changedCount());
        assertThat(report.sliceCount()).isEqualTo(UpdateFixture.sliceCount());
        assertThat(roundTrips)
                .as("갱신 %d 행을 %d 문장으로 끝낸다 (배치 메타데이터가 조금 섞인다)",
                        UpdateFixture.changedCount(), UpdateFixture.sliceCount())
                .isLessThan(UpdateFixture.changedCount() / 100);
    }

    @Test
    @DisplayName("슬라이스는 키 공간을 빈틈없이 덮는다 - 빠진 구간은 조용히 갱신되지 않는다")
    void 구간에_빈틈이_없다() throws Exception {
        launch(run());

        SliceUpdateReport report = sliceRecorder.report();
        long from = report.slices().get(0).slice().fromId();
        long to = report.slices().get(report.sliceCount() - 1).slice().toId();

        assertThat(from).isEqualTo(1);
        assertThat(to).isEqualTo(UpdateFixture.COUNT);
        assertThat(report.slices().stream().mapToLong(slice -> slice.slice().width()).sum())
                .as("구간 폭의 합이 키 공간과 같아야 겹침도 빈틈도 없다")
                .isEqualTo(UpdateFixture.COUNT);
    }

    @Test
    @DisplayName("재실행은 한 행도 갱신하지 않는다 - WHERE 절의 CASE 식이 프로세서 필터를 대신한다")
    void 재실행은_0행을_갱신한다() throws Exception {
        launch(run());

        launch(nextRun());

        assertThat(sliceRecorder.report().totalUpdatedRows())
                .as("문장은 그대로 %d 번 나가지만 갱신할 행이 없다", UpdateFixture.sliceCount())
                .isZero();
        assertThat(sliceRecorder.report().sliceCount()).isEqualTo(UpdateFixture.sliceCount());
    }

    @Test
    @DisplayName("문장 시간이 기록된다 - 락 유지 시간의 상한을 아는 유일한 방법")
    void 슬라이스_시간이_남는다() throws Exception {
        launch(run());

        SliceUpdateReport report = sliceRecorder.report();

        assertThat(report.maxElapsedMillis())
                .as("전역 카운터의 락 시간은 '기다린' 시간이라 경합이 없으면 0 이다. 잡고 있던 시간은 여기서만 안다")
                .isPositive();
        assertThat(report.maxElapsedMillis()).isGreaterThanOrEqualTo(report.averageMillis());
    }
}
