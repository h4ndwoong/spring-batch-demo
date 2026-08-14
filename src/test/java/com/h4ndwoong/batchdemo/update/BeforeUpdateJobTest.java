package com.h4ndwoong.batchdemo.update;

import com.h4ndwoong.batchdemo.support.DatabaseWorkloadListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.StepExecution;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 6번 문제 before 구성({@link BeforeUpdateJobConfig})을 실제 MariaDB 에 실행해 <b>증상을 확인</b>한다.
 *
 * <p>상속받은 계약 시험이 "after 와 같은 결과를 낸다" 를 보이고, 여기 있는 시험이 <b>그 결과를 얻기
 * 위해 몇 번 왕복했는가</b> 를 보인다. before 의 증상은 틀린 답이 아니라 <b>맞는 답을 얻는 방식</b>
 * 이므로, 결과를 보는 시험만으로는 아무것도 드러나지 않는다.
 */
@ActiveProfiles("before")
class BeforeUpdateJobTest extends UpdateJobContract {

    @Test
    @DisplayName("READ = WRITE + FILTER - 등급이 바뀐 행만 쓴다")
    void 바뀐_행만_쓴다() throws Exception {
        StepExecution step = step(launch(run()));

        assertThat(step.getReadCount())
                .as("전량을 애플리케이션으로 끌어올린다. after 의 READ_COUNT 는 슬라이스 수다")
                .isEqualTo(UpdateFixture.COUNT);
        assertThat(step.getWriteCount()).isEqualTo(UpdateFixture.changedCount());
        assertThat(step.getFilterCount())
                .isEqualTo(UpdateFixture.COUNT - UpdateFixture.changedCount());
    }

    @Test
    @DisplayName("UPDATE 는 행마다 한 문장씩 나간다 - JdbcBatchItemWriter 를 써도 묶이지 않는다")
    void 행마다_한_문장이다() throws Exception {
        launch(run());

        long statements = DatabaseWorkloadListener.updateRoundTrips(workloadListener.lastDelta());

        assertThat(statements)
                .as("갱신 %d 행에 대해 문장도 그만큼 나간다. 이름에 Batch 가 들어 있어도 그렇다",
                        UpdateFixture.changedCount())
                .isGreaterThanOrEqualTo(UpdateFixture.changedCount());
        assertThat(statements)
                .as("청크 커밋의 메타데이터를 빼면 갱신 행 수와 거의 같다")
                .isLessThan(UpdateFixture.changedCount() * 2);
    }

    @Test
    @DisplayName("갱신한 행 수와 왕복 횟수의 비가 1에 가깝다 - 6번의 before 지표")
    void 왕복_비율이_1이다() throws Exception {
        launch(run());

        long roundTrips = DatabaseWorkloadListener.updateRoundTrips(workloadListener.lastDelta());
        long updatedRows = workloadListener.lastDelta().get(DatabaseWorkloadListener.ROWS_UPDATED);

        assertThat((double) roundTrips / updatedRows)
                .as("after 는 같은 갱신 행 수를 유지한 채 이 비율만 0 에 가깝게 만든다")
                .isBetween(0.9, 1.1);
    }
}
