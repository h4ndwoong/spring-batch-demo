package com.h4ndwoong.batchdemo.restart;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.domain.MemberE;
import com.h4ndwoong.batchdemo.domain.MemberGrade;
import com.h4ndwoong.batchdemo.domain.MemberStatus;
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
 * {@link FailAfterCountItemWriter} 단위 테스트.
 *
 * <p>5번의 대사식은 "실패 직전까지 정확히 N 건이 커밋되어 있다" 를 전제로 한다. 그 N 이 실행마다
 * 흔들리면 before 의 피해액도, after 의 남은 건수도 계산으로 예측할 수 없다. 여기서 고정하는 것은
 * <b>실패 지점의 정확성</b>이다.
 */
class FailAfterCountItemWriterTest {

    private final List<MemberBase> written = new ArrayList<>();
    private final ItemWriter<MemberBase> delegate = chunk -> written.addAll(chunk.getItems());

    @Test
    @DisplayName("지정한 건수를 쓸 때까지는 그대로 위임한다")
    void 지정_건수까지_위임() throws Exception {
        FailAfterCountItemWriter writer = new FailAfterCountItemWriter(delegate, 4L);

        writer.write(chunk(1L, 2L));
        writer.write(chunk(3L, 4L));

        assertThat(written).hasSize(4);
        assertThat(writer.writtenCount()).isEqualTo(4L);
    }

    @Test
    @DisplayName("건수에 도달하면 그 다음 청크에서 실패한다 - 위임 전에 던지므로 DB 에 흔적이 없다")
    void 도달하면_실패() throws Exception {
        FailAfterCountItemWriter writer = new FailAfterCountItemWriter(delegate, 4L);
        writer.write(chunk(1L, 2L));
        writer.write(chunk(3L, 4L));

        assertThatThrownBy(() -> writer.write(chunk(5L, 6L)))
                .isInstanceOf(InjectedFailureException.class)
                .hasMessageContaining("4");

        assertThat(written)
                .as("실패한 청크는 한 행도 쓰지 않는다").hasSize(4);
        assertThat(writer.writtenCount()).isEqualTo(4L);
    }

    @Test
    @DisplayName("청크 경계를 넘어선 건수여도 다음 청크에서 실패한다 - 부분 청크를 쓰지 않는다")
    void 경계를_넘는_건수() throws Exception {
        FailAfterCountItemWriter writer = new FailAfterCountItemWriter(delegate, 3L);

        writer.write(chunk(1L, 2L));
        assertThatCode(() -> writer.write(chunk(3L, 4L)))
                .as("2건 시점에는 아직 3에 도달하지 않았다").doesNotThrowAnyException();
        assertThatThrownBy(() -> writer.write(chunk(5L, 6L)))
                .isInstanceOf(InjectedFailureException.class);

        assertThat(written).hasSize(4);
    }

    @Test
    @DisplayName("0 이하면 절대 실패시키지 않는다 - 기본 실행 경로다")
    void 장애_없음() {
        FailAfterCountItemWriter writer = new FailAfterCountItemWriter(delegate, 0L);

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
        FailAfterCountItemWriter writer = new FailAfterCountItemWriter(delegate, 2L);
        writer.write(chunk(1L, 2L));

        assertThatThrownBy(() -> writer.write(chunk(3L, 4L))).isInstanceOf(InjectedFailureException.class);
        assertThatThrownBy(() -> writer.write(chunk(3L, 4L)))
                .as("재시도로 넘어가면 '실패한 뒤 재실행' 상황이 만들어지지 않는다")
                .isInstanceOf(InjectedFailureException.class);
    }

    private static Chunk<MemberBase> chunk(long... ids) {
        List<MemberBase> items = new ArrayList<>();
        for (long id : ids) {
            items.add(new MemberE(id, "user" + id + "@example.com", "김민준", MemberGrade.BRONZE,
                    1_000L, MemberStatus.ACTIVE, null, false, null,
                    LocalDateTime.of(2026, 1, 1, 0, 0), null));
        }
        return new Chunk<>(items);
    }
}
