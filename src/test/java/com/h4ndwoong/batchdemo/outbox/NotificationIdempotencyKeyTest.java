package com.h4ndwoong.batchdemo.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link NotificationIdempotencyKey} 단위 테스트.
 *
 * <p>키가 <b>같은 회원에게 언제나 같고 다른 회원에게 언제나 다르다</b>는 성질 위에 7번의 중복
 * 측정과 Outbox 의 UNIQUE 제약이 함께 서 있다. 이 성질이 깨지면 배치는 {@code COMPLETED} 로
 * 끝나고 중복 집계만 조용히 거짓이 된다.
 */
class NotificationIdempotencyKeyTest {

    @Test
    @DisplayName("같은 회원에게는 언제나 같은 키다 - 중복 판정의 근거")
    void 같은_회원_같은_키() {
        assertThat(NotificationIdempotencyKey.of(12_345L))
                .isEqualTo(NotificationIdempotencyKey.of(12_345L));
    }

    @Test
    @DisplayName("다른 회원에게는 다른 키다 - 충돌하면 남의 알림이 내 중복으로 잡힌다")
    void 다른_회원_다른_키() {
        assertThat(NotificationIdempotencyKey.of(1L))
                .isNotEqualTo(NotificationIdempotencyKey.of(2L));
    }

    @Test
    @DisplayName("키에 테이블과 사건이 드러난다 - 다른 Job 의 키와 섞이지 않는다")
    void 접두사() {
        assertThat(NotificationIdempotencyKey.of(7L)).isEqualTo("member_g:status-changed:7");
    }

    @Test
    @DisplayName("최악의 식별자에서도 컬럼 길이 안에 들어온다")
    void 컬럼_길이() {
        assertThat(NotificationIdempotencyKey.of(Long.MAX_VALUE).length())
                .as("잘린 키는 다른 회원의 키와 충돌한다")
                .isLessThanOrEqualTo(NotificationMessage.KEY_LIMIT);
    }

    @Test
    @DisplayName("식별자가 없으면 키를 만들지 않는다 - 어느 알림인지 말할 수 없다")
    void 식별자_없음() {
        assertThatThrownBy(() -> NotificationIdempotencyKey.of(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
