package com.h4ndwoong.batchdemo.restart;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PointBalanceChecksum} 단위 테스트.
 *
 * <p>5번의 대사는 두 지문이 <b>같은가</b>로 판정된다. 그래서 이 record 에서 확인할 것은 값 비교가
 * 네 숫자 전부를 본다는 것과, 총합 차이를 읽는 방향이 뒤집히지 않았다는 것이다.
 */
class PointBalanceChecksumTest {

    @Test
    @DisplayName("네 숫자가 모두 같아야 같은 지문이다")
    void 동등성() {
        PointBalanceChecksum checksum = new PointBalanceChecksum(300_000, 14_962_300_000L, 0, 285_000);

        assertThat(checksum)
                .isEqualTo(new PointBalanceChecksum(300_000, 14_962_300_000L, 0, 285_000))
                .as("포인트 총합이 같아도 음수 행 수가 다르면 다른 상태다")
                .isNotEqualTo(new PointBalanceChecksum(300_000, 14_962_300_000L, 5_700, 285_000))
                .as("처리 흔적의 유무가 before 와 after 를 가른다")
                .isNotEqualTo(new PointBalanceChecksum(300_000, 14_962_300_000L, 0, 0));
    }

    @Test
    @DisplayName("소멸이 일어나면 총합 차이가 음수다")
    void 총합_차이() {
        PointBalanceChecksum before = new PointBalanceChecksum(10, 10_000, 0, 0);
        PointBalanceChecksum after = new PointBalanceChecksum(10, 4_000, 0, 10);

        assertThat(after.pointDelta(before)).isEqualTo(-6_000L);
    }

    @Test
    @DisplayName("빈 지문은 모두 0 이다 - Step 에 들어가지 못한 실행의 보고")
    void 빈_지문() {
        assertThat(PointBalanceChecksum.EMPTY)
                .isEqualTo(new PointBalanceChecksum(0, 0, 0, 0));
        assertThat(PointBalanceChecksum.EMPTY.summary()).contains("pointSum=0");
    }
}
