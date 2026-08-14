package com.h4ndwoong.batchdemo.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 키 공간 분할({@link IdSlice#of(long, long, long)})의 경계를 고정한다.
 *
 * <p>DB 를 쓰지 않는다. 분할은 순수 계산이고, <b>그 계산이 틀렸을 때의 증상이 조용하기 때문에</b>
 * 여기서 못 박는다.
 * <ul>
 *   <li>빈틈 — 그 구간의 회원은 등급이 갱신되지 않은 채 배치가 {@code COMPLETED} 로 끝난다</li>
 *   <li>겹침 — 같은 행에 UPDATE 가 두 번 가고, 두 번째는 조건에 걸려 0행이라 흔적도 없다</li>
 * </ul>
 * 어느 쪽도 로그에 나타나지 않는다.
 */
class IdSliceTest {

    @Nested
    @DisplayName("분할")
    class 분할 {

        @Test
        @DisplayName("나누어떨어지면 같은 크기로 잘린다")
        void 균등_분할() {
            List<IdSlice> slices = IdSlice.of(1, 20_000, 5_000);

            assertThat(slices).hasSize(4);
            assertThat(slices.get(0)).isEqualTo(new IdSlice(1, 1, 5_000));
            assertThat(slices.get(3)).isEqualTo(new IdSlice(4, 15_001, 20_000));
        }

        @Test
        @DisplayName("남는 구간은 마지막 슬라이스가 가져간다")
        void 나머지_구간() {
            List<IdSlice> slices = IdSlice.of(1, 10_500, 5_000);

            assertThat(slices).hasSize(3);
            assertThat(slices.get(2)).isEqualTo(new IdSlice(3, 10_001, 10_500));
        }

        @Test
        @DisplayName("빈틈도 겹침도 없이 키 공간 전체를 덮는다")
        void 빈틈도_겹침도_없다() {
            List<IdSlice> slices = IdSlice.of(7, 10_007, 999);

            assertThat(slices.get(0).fromId()).isEqualTo(7);
            assertThat(slices.get(slices.size() - 1).toId()).isEqualTo(10_007);
            assertThat(slices.stream().mapToLong(IdSlice::width).sum())
                    .as("폭의 합이 키 공간과 같으면 빈틈도 겹침도 없다")
                    .isEqualTo(10_001);
            for (int i = 1; i < slices.size(); i++) {
                assertThat(slices.get(i).fromId())
                        .as("앞 슬라이스의 끝 바로 다음에서 이어져야 한다")
                        .isEqualTo(slices.get(i - 1).toId() + 1);
            }
        }

        @Test
        @DisplayName("크기가 0 이하면 나누지 않는다 - 한 문장이 전 구간을 잠그는 극단값")
        void 분할하지_않는다() {
            assertThat(IdSlice.of(1, 1_000_000, 0))
                    .containsExactly(new IdSlice(1, 1, 1_000_000));
            assertThat(IdSlice.of(1, 1_000_000, -1))
                    .containsExactly(new IdSlice(1, 1, 1_000_000));
        }

        @Test
        @DisplayName("크기가 구간보다 크면 슬라이스는 하나다")
        void 구간보다_큰_크기() {
            assertThat(IdSlice.of(1, 100, 5_000)).containsExactly(new IdSlice(1, 1, 100));
        }

        @Test
        @DisplayName("빈 키 공간은 슬라이스도 없다 - 빈 테이블에서 UPDATE 를 보내지 않는다")
        void 빈_구간() {
            assertThat(IdSlice.of(10, 9, 100)).isEmpty();
        }

        @Test
        @DisplayName("id 에 구멍이 있어도 상관없다 - 구간은 행이 아니라 키 공간을 나눈다")
        void 구멍이_있어도_된다() {
            assertThat(IdSlice.of(1, 9, 3)).hasSize(3);
        }
    }

    @Nested
    @DisplayName("불변식")
    class 불변식 {

        @Test
        @DisplayName("뒤집힌 구간은 만들 수 없다")
        void 뒤집힌_구간() {
            assertThatThrownBy(() -> new IdSlice(1, 10, 9))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("뒤집혔다");
        }

        @Test
        @DisplayName("순번은 1부터다")
        void 순번() {
            assertThatThrownBy(() -> new IdSlice(0, 1, 10))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("폭은 양 끝을 포함한다")
        void 폭() {
            assertThat(new IdSlice(1, 1, 5_000).width()).isEqualTo(5_000);
            assertThat(new IdSlice(1, 7, 7).width()).isEqualTo(1);
        }
    }
}
