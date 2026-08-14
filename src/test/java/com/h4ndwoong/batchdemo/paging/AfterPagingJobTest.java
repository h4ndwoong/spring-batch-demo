package com.h4ndwoong.batchdemo.paging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 3번 문제 after 구성({@link AfterPagingJobConfig})을 실제 MariaDB 에 실행해 검증한다.
 *
 * <p>상속받은 계약 시험이 "before 와 같은 일을 한다" 를 보이고, 여기 있는 시험이 <b>읽은 행 수가
 * 건수를 넘지 않는다</b> 를 보인다. 순회하려는 행이 2만 건이면 2만 행만 읽는 것이 당연해 보이지만,
 * 그 당연한 일을 하지 않는 것이 before 다.
 *
 * <p>재실행 시험이 있는 이유는 키셋 리더가 <b>마지막으로 읽은 {@code id}</b> 를 상태로 들고 있기
 * 때문이다. 이 상태가 되돌려지지 않으면 두 번째 실행이 0건을 읽고 조용히 {@code COMPLETED} 로
 * 끝난다 — 3번 문제에서 가장 그럴듯하게 틀릴 수 있는 실패 방식이다.
 */
@ActiveProfiles("after")
class AfterPagingJobTest extends PagingJobContract {

    @Test
    @DisplayName("건수만큼만 읽는다 - 키셋은 앞 레코드를 버리지 않는다")
    void 버리지_않는다() throws Exception {
        launch(parameters());

        assertThat(rowsScanned())
                .as("순회할 행이 %d건이면 그만큼만 읽는다 (배치 메타데이터 몫을 감안해 2배로 단언한다)",
                        PagingFixture.COUNT)
                .isLessThan(PagingFixture.COUNT * 2);
    }

    @Test
    @DisplayName("재실행해도 같은 결과다 - 리더가 들고 있는 위치가 되돌려진다")
    void 재실행_동일() throws Exception {
        launch(parameters());

        launch(parameters());

        assertThat(checksumWriter.checksum())
                .as("되돌리지 않으면 두 번째 실행이 0건을 읽고 조용히 COMPLETED 로 끝난다")
                .isEqualTo(PagingFixture.CHECKSUM);
        assertThat(pageTimingRecorder.report().pageCount()).isEqualTo(PagingFixture.PAGE_COUNT);
    }
}
