package com.h4ndwoong.batchdemo.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link MemberBase} 의 상태 전이와 "검증하지 않음" 계약을 검증한다.
 *
 * <p>대표 구현체로 {@link MemberA} 를 쓴다. 7개 엔티티는 동일한 상태 전이를 물려받으므로
 * 전이 로직을 7번 반복 검증하지 않는다. 테이블 매핑은
 * {@code MemberEntityMappingTest} 가 7개 전부 검증한다.
 */
class MemberBaseTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 8, 14, 12, 30);

    private static MemberA newMember(long point) {
        return new MemberA("user@example.com", "홍길동", MemberGrade.BRONZE, point,
                MemberStatus.ACTIVE, null, CREATED_AT);
    }

    @Nested
    @DisplayName("신규 생성")
    class Creation {

        @Test
        @DisplayName("processed 는 false, 멱등키와 수정 시각은 null 로 시작한다")
        void 신규_회원의_초기_상태() {
            MemberA member = newMember(1_000L);

            assertThat(member.getId()).isNull();
            assertThat(member.isProcessed()).isFalse();
            assertThat(member.getIdempotencyKey()).isNull();
            assertThat(member.getUpdatedAt()).isNull();
            assertThat(member.getCreatedAt()).isEqualTo(CREATED_AT);
        }

        @Test
        @DisplayName("잘못된 이메일 형식과 음수 포인트를 거부하지 않는다 - 2번 문제의 오염 데이터 전제")
        void 엔티티는_검증하지_않는다() {
            assertThatCode(() -> new MemberB("not-an-email", "오염행", MemberGrade.BRONZE, -500L,
                    MemberStatus.ACTIVE, null, CREATED_AT))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("DB 행 복원 생성자는 모든 컬럼 값을 그대로 싣는다")
        void 복원_생성자() {
            MemberC restored = new MemberC(42L, "user@example.com", "홍길동", MemberGrade.GOLD, 700L,
                    MemberStatus.DORMANT, 7L, true, "key-42", CREATED_AT, UPDATED_AT);

            assertThat(restored.getId()).isEqualTo(42L);
            assertThat(restored.getGrade()).isEqualTo(MemberGrade.GOLD);
            assertThat(restored.getStatus()).isEqualTo(MemberStatus.DORMANT);
            assertThat(restored.getReferrerId()).isEqualTo(7L);
            assertThat(restored.isProcessed()).isTrue();
            assertThat(restored.getIdempotencyKey()).isEqualTo("key-42");
            assertThat(restored.getUpdatedAt()).isEqualTo(UPDATED_AT);
        }
    }

    @Nested
    @DisplayName("상태 전이")
    class Transition {

        @Test
        @DisplayName("포인트를 차감하고 수정 시각을 인자로 받은 시각으로 갱신한다")
        void 포인트_차감() {
            MemberA member = newMember(1_000L);

            member.deductPoint(300L, UPDATED_AT);

            assertThat(member.getPoint()).isEqualTo(700L);
            assertThat(member.getUpdatedAt()).isEqualTo(UPDATED_AT);
        }

        @Test
        @DisplayName("잔액이 부족해도 예외 없이 음수가 된다 - 5번 문제의 이중 차감 재현 전제")
        void 포인트_차감은_잔액을_검사하지_않는다() {
            MemberA member = newMember(100L);

            member.deductPoint(100L, UPDATED_AT);
            member.deductPoint(100L, UPDATED_AT);

            assertThat(member.getPoint()).isEqualTo(-100L);
        }

        @Test
        @DisplayName("처리 완료 마킹은 멱등키를 함께 기록한다")
        void 처리_완료_마킹() {
            MemberE member = new MemberE("user@example.com", "홍길동", MemberGrade.BRONZE, 1_000L,
                    MemberStatus.ACTIVE, null, CREATED_AT);

            member.markProcessed("restartJob-2026-08-14-1", UPDATED_AT);

            assertThat(member.isProcessed()).isTrue();
            assertThat(member.getIdempotencyKey()).isEqualTo("restartJob-2026-08-14-1");
            assertThat(member.getUpdatedAt()).isEqualTo(UPDATED_AT);
        }

        @Test
        @DisplayName("등급을 변경하고 수정 시각을 갱신한다")
        void 등급_변경() {
            MemberF member = new MemberF("user@example.com", "홍길동", MemberGrade.BRONZE, 5_000L,
                    MemberStatus.ACTIVE, null, CREATED_AT);

            member.changeGrade(MemberGrade.VIP, UPDATED_AT);

            assertThat(member.getGrade()).isEqualTo(MemberGrade.VIP);
            assertThat(member.getUpdatedAt()).isEqualTo(UPDATED_AT);
        }

        @Test
        @DisplayName("상태를 변경하고 수정 시각을 갱신한다")
        void 상태_변경() {
            MemberG member = new MemberG("user@example.com", "홍길동", MemberGrade.BRONZE, 0L,
                    MemberStatus.ACTIVE, null, CREATED_AT);

            member.changeStatus(MemberStatus.WITHDRAWN, UPDATED_AT);

            assertThat(member.getStatus()).isEqualTo(MemberStatus.WITHDRAWN);
            assertThat(member.getUpdatedAt()).isEqualTo(UPDATED_AT);
        }

        @Test
        @DisplayName("상태 전이는 시각을 직접 조회하지 않으므로 같은 시각을 여러 행에 동일하게 찍을 수 있다")
        void 수정_시각은_호출자가_통제한다() {
            MemberF first = new MemberF("a@example.com", "A", MemberGrade.BRONZE, 0L,
                    MemberStatus.ACTIVE, null, CREATED_AT);
            MemberF second = new MemberF("b@example.com", "B", MemberGrade.BRONZE, 0L,
                    MemberStatus.ACTIVE, null, CREATED_AT);

            first.changeGrade(MemberGrade.SILVER, UPDATED_AT);
            second.changeGrade(MemberGrade.SILVER, UPDATED_AT);

            assertThat(first.getUpdatedAt()).isEqualTo(second.getUpdatedAt());
        }
    }
}
