package com.h4ndwoong.batchdemo.restart;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ExpiryIdempotencyKey} 단위 테스트.
 *
 * <p>키가 갖춰야 할 성질은 둘뿐이다 — <b>결정론</b>과 <b>유일성</b>. 결정론이 깨지면 재실행마다 새
 * 키가 생겨 UNIQUE 제약이 아무것도 막지 못하고, 유일성이 깨지면 여러 행이 한 키로 뭉개져 처리 이력이
 * 거짓이 된다. 후자는 배치를 실패시키지도 않고 조용히 통과하므로 여기서 잡아야 한다.
 */
class ExpiryIdempotencyKeyTest {

    /** {@code idempotency_key} 컬럼 길이. 이 값을 넘으면 저장 시점에 잘리거나 실패한다. */
    private static final int COLUMN_LENGTH = 64;

    @Test
    @DisplayName("같은 회원에게는 언제나 같은 키를 준다 - 결정론")
    void 결정론() {
        assertThat(ExpiryIdempotencyKey.of(12345L))
                .isEqualTo(ExpiryIdempotencyKey.of(12345L));
    }

    @Test
    @DisplayName("회원이 다르면 키도 다르다 - 여러 행이 한 키로 뭉개지면 UNIQUE 제약이 배치를 실패시킨다")
    void 유일성() {
        Set<String> keys = new HashSet<>();
        for (long id = 1; id <= 1_000; id++) {
            keys.add(ExpiryIdempotencyKey.of(id));
        }

        assertThat(keys).hasSize(1_000);
    }

    @Test
    @DisplayName("키가 컬럼 길이를 넘지 않는다 - long 최대값에서도")
    void 길이_한계() {
        assertThat(ExpiryIdempotencyKey.of(Long.MAX_VALUE)).hasSizeLessThanOrEqualTo(COLUMN_LENGTH);
    }

    @Test
    @DisplayName("식별자가 없으면 키를 만들지 않는다 - 흔적을 남길 자리가 없다는 뜻이다")
    void 식별자_없음() {
        assertThatThrownBy(() -> ExpiryIdempotencyKey.of(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
