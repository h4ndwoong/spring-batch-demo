package com.h4ndwoong.batchdemo.skip;

import com.h4ndwoong.batchdemo.domain.MemberB;
import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.domain.MemberGrade;
import com.h4ndwoong.batchdemo.domain.MemberStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.dao.TransientDataAccessException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link FaultInjectingItemWriter} 단위 테스트.
 *
 * <p>재시도 실습이 성립하려면 장애가 <b>결정론적</b>이어야 한다. "가끔 실패하는" 장애로는
 * before/after 를 같은 축에서 비교할 수 없다. 여기서 고정하는 것은 "몇 번째 청크가, 몇 번,
 * 어떤 종류로 실패하는가" 다.
 */
class FaultInjectingItemWriterTest {

    private final List<MemberBase> written = new ArrayList<>();
    private final ItemWriter<MemberBase> delegate = chunk -> written.addAll(chunk.getItems());

    @Test
    @DisplayName("대상 청크가 아니면 그대로 위임한다")
    void 대상_아님() throws Exception {
        FaultInjectingItemWriter writer = writer(501L, 2, FaultKind.TRANSIENT);

        writer.write(chunk(1L, 2L));

        assertThat(written).hasSize(2);
        assertThat(writer.thrownCount()).isZero();
    }

    @Test
    @DisplayName("대상 청크는 지정한 횟수만큼 일시 장애로 실패하고 그 다음 성공한다")
    void 지정_횟수만큼_실패() throws Exception {
        FaultInjectingItemWriter writer = writer(501L, 2, FaultKind.TRANSIENT);

        assertThatThrownBy(() -> writer.write(chunk(501L, 502L)))
                .isInstanceOf(TransientDataAccessException.class);
        assertThatThrownBy(() -> writer.write(chunk(501L, 502L)))
                .isInstanceOf(TransientDataAccessException.class);
        assertThatCode(() -> writer.write(chunk(501L, 502L)))
                .as("retryLimit=3 안에서 회복되는 것이 TRANSIENT 의 정의다").doesNotThrowAnyException();

        assertThat(writer.thrownCount()).isEqualTo(2);
        assertThat(written).as("성공한 시도에서만 위임된다").hasSize(2);
    }

    @Test
    @DisplayName("FATAL 은 재시도 목록에 없는 예외를 던진다")
    void 치명적_장애() {
        FaultInjectingItemWriter writer = writer(501L, 1, FaultKind.FATAL);

        assertThatThrownBy(() -> writer.write(chunk(501L)))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(TransientDataAccessException.class)
                .isNotInstanceOf(MemberValidationException.class);
        assertThat(written).isEmpty();
    }

    @Test
    @DisplayName("실패해도 위임하지 않는다 - DB 에 흔적이 남지 않아야 한다")
    void 실패_시_미위임() {
        FaultInjectingItemWriter writer = writer(501L, 1, FaultKind.TRANSIENT);

        assertThatThrownBy(() -> writer.write(chunk(501L, 502L)));

        assertThat(written).isEmpty();
    }

    @Test
    @DisplayName("청크의 첫 행으로 대상을 판별한다 - 스캔으로 쪼개진 1행 청크도 같은 규칙을 탄다")
    void 첫_행_기준() throws Exception {
        FaultInjectingItemWriter writer = writer(502L, 1, FaultKind.TRANSIENT);

        writer.write(chunk(501L, 502L));

        assertThat(written)
                .as("502 가 청크에 들어 있어도 첫 행이 아니면 대상이 아니다").hasSize(2);
        assertThatThrownBy(() -> writer.write(chunk(502L)))
                .as("행 단위로 쪼개져 502 가 첫 행이 되면 그때 걸린다")
                .isInstanceOf(TransientDataAccessException.class);
    }

    @Test
    @DisplayName("faultAtId 가 0 이면 장애를 심지 않는다 - 평소 실행 경로")
    void 장애_없음() throws Exception {
        FaultInjectingItemWriter writer = writer(0L, 2, FaultKind.TRANSIENT);

        writer.write(chunk(1L));
        writer.write(chunk(501L));

        assertThat(written).hasSize(2);
        assertThat(writer.thrownCount()).isZero();
    }

    private FaultInjectingItemWriter writer(long faultAtId, int faultTimes, FaultKind kind) {
        return new FaultInjectingItemWriter(delegate, faultAtId, faultTimes, kind);
    }

    private Chunk<MemberBase> chunk(Long... ids) {
        List<MemberBase> items = new ArrayList<>();
        for (Long id : ids) {
            items.add(new MemberB(id, "user" + id + "@example.com", "김민준", MemberGrade.GOLD,
                    100L, MemberStatus.ACTIVE, null, false, null,
                    LocalDateTime.of(2026, 1, 1, 0, 0), null));
        }
        return new Chunk<>(items);
    }
}
