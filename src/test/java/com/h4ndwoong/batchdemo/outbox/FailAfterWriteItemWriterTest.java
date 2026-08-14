package com.h4ndwoong.batchdemo.outbox;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.domain.MemberG;
import com.h4ndwoong.batchdemo.domain.MemberGrade;
import com.h4ndwoong.batchdemo.domain.MemberStatus;
import com.h4ndwoong.batchdemo.support.InjectedFailureException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link FailAfterWriteItemWriter} 단위 테스트.
 *
 * <p><b>여기서 고정하는 것은 던지는 줄의 위치</b>다. 5번의 {@code FailAfterCountItemWriter} 는 위임
 * 전에 던져 실패한 청크가 아무 흔적도 남기지 않게 했고, 7번은 반대로 <b>라이터의 일을 전부 마친
 * 뒤</b>에 던진다. 이 순서가 뒤집히면 유령 알림이 생기지 않아 before 의 증상 자체가 사라진다.
 */
class FailAfterWriteItemWriterTest {

    private final List<MemberBase> written = new ArrayList<>();
    private final ItemWriter<MemberBase> delegate = chunk -> written.addAll(chunk.getItems());

    @Test
    @DisplayName("지정한 건수를 커밋할 때까지는 그대로 위임한다")
    void 지정_건수까지_위임() throws Exception {
        FailAfterWriteItemWriter writer = new FailAfterWriteItemWriter(delegate, 4L);

        writer.write(chunk(1L, 2L));
        writer.write(chunk(3L, 4L));

        assertThat(written).hasSize(4);
        assertThat(writer.committedCount()).isEqualTo(4L);
    }

    @Test
    @DisplayName("실패하는 청크도 쓰기를 마친 뒤에 던진다 - 유령 알림이 생기는 자리다")
    void 위임_후에_던진다() throws Exception {
        FailAfterWriteItemWriter writer = new FailAfterWriteItemWriter(delegate, 4L);
        writer.write(chunk(1L, 2L));
        writer.write(chunk(3L, 4L));

        assertThatThrownBy(() -> writer.write(chunk(5L, 6L)))
                .isInstanceOf(InjectedFailureException.class)
                .hasMessageContaining("4");

        assertThat(written)
                .as("5번과 정반대다. 실패한 청크의 쓰기도 끝나 있고, 되돌아오는 것은 DB 뿐이다")
                .hasSize(6);
    }

    @Test
    @DisplayName("커밋 건수는 실패한 청크를 포함하지 않는다 - 대사식의 기준점이다")
    void 커밋_건수는_실패_청크를_빼고_센다() throws Exception {
        FailAfterWriteItemWriter writer = new FailAfterWriteItemWriter(delegate, 4L);
        writer.write(chunk(1L, 2L));
        writer.write(chunk(3L, 4L));

        assertThatThrownBy(() -> writer.write(chunk(5L, 6L)))
                .isInstanceOf(InjectedFailureException.class);

        assertThat(writer.committedCount())
                .as("쓴 것은 6건이지만 커밋되는 것은 4건이다")
                .isEqualTo(4L);
    }

    @Test
    @DisplayName("0 이하면 절대 실패시키지 않는다 - 기본 실행 경로다")
    void 장애_없음() {
        FailAfterWriteItemWriter writer = new FailAfterWriteItemWriter(delegate, 0L);

        assertThatCode(() -> {
            for (long id = 1; id <= 100; id += 2) {
                writer.write(chunk(id, id + 1));
            }
        }).doesNotThrowAnyException();

        assertThat(written).hasSize(100);
    }

    @Test
    @DisplayName("실패는 회복되지 않는다 - 다시 써도 계속 던진다")
    void 회복되지_않는다() throws Exception {
        FailAfterWriteItemWriter writer = new FailAfterWriteItemWriter(delegate, 2L);
        writer.write(chunk(1L, 2L));

        assertThatThrownBy(() -> writer.write(chunk(3L, 4L))).isInstanceOf(InjectedFailureException.class);
        assertThatThrownBy(() -> writer.write(chunk(5L, 6L)))
                .as("재시도로 넘어가면 '실패한 뒤 재실행' 상황이 만들어지지 않는다")
                .isInstanceOf(InjectedFailureException.class);
    }

    private static Chunk<MemberBase> chunk(long... ids) {
        List<MemberBase> items = new ArrayList<>();
        for (long id : ids) {
            items.add(new MemberG(id, "user" + id + "@example.com", "김민준", MemberGrade.BRONZE,
                    1_000L, MemberStatus.ACTIVE, null, false, null,
                    LocalDateTime.of(2026, 1, 1, 0, 0), null));
        }
        return new Chunk<>(items);
    }
}
