package com.h4ndwoong.batchdemo.lookup;

import com.h4ndwoong.batchdemo.domain.MemberGrade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 등급 정책({@link GradePolicy})의 경계를 고정한다.
 *
 * <p>정책이 흔들리면 before/after 의 산정 결과가 갈라진다. 이 시험은 성능과 무관하지만
 * <b>비교의 전제</b>를 지킨다.
 */
class GradePolicyTest {

    @Nested
    @DisplayName("등급 산정")
    class Grading {

        private final GradePolicy policy = new GradePolicy(100, 200, 300);

        @Test
        @DisplayName("임계값에 정확히 걸치면 상위 등급이다")
        void 경계값() {
            assertThat(policy.gradeOf(100)).isEqualTo(MemberGrade.SILVER);
            assertThat(policy.gradeOf(200)).isEqualTo(MemberGrade.GOLD);
            assertThat(policy.gradeOf(300)).isEqualTo(MemberGrade.VIP);
        }

        @Test
        @DisplayName("임계값 바로 아래는 하위 등급이다")
        void 경계_직전() {
            assertThat(policy.gradeOf(99)).isEqualTo(MemberGrade.BRONZE);
            assertThat(policy.gradeOf(199)).isEqualTo(MemberGrade.SILVER);
            assertThat(policy.gradeOf(299)).isEqualTo(MemberGrade.GOLD);
        }

        @Test
        @DisplayName("음수 포인트도 산정한다 - 검증은 2번 문제의 주제다")
        void 음수() {
            assertThat(policy.gradeOf(-1)).isEqualTo(MemberGrade.BRONZE);
        }
    }

    @Nested
    @DisplayName("분포에서 산출")
    class FromRange {

        @Test
        @DisplayName("사분위로 임계값을 잡는다")
        void 사분위() {
            GradePolicy policy = GradePolicy.ofRange(0, 100_000);

            assertThat(policy).isEqualTo(new GradePolicy(25_000, 50_000, 75_000));
        }

        @Test
        @DisplayName("모두 같은 포인트면 전원이 VIP 다 - 무의미하지만 결정론적이다")
        void 축퇴() {
            GradePolicy policy = GradePolicy.ofRange(500, 500);

            assertThat(policy.gradeOf(500))
                    .as("정책으로는 쓸모없지만 before/after 가 같은 답을 내므로 비교는 성립한다")
                    .isEqualTo(MemberGrade.VIP);
        }

        @Test
        @DisplayName("최대가 최소보다 작으면 만들 수 없다")
        void 뒤집힌_범위() {
            assertThatThrownBy(() -> GradePolicy.ofRange(100, 99))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("임계값이 오름차순이 아니면 만들 수 없다 - 도달할 수 없는 등급이 생긴다")
    void 뒤집힌_임계값() {
        assertThatThrownBy(() -> new GradePolicy(300, 200, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("오름차순");
    }
}
