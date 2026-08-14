package com.h4ndwoong.batchdemo.paging;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.domain.MemberC;
import com.h4ndwoong.batchdemo.domain.MemberGrade;
import com.h4ndwoong.batchdemo.domain.MemberStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.item.Chunk;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TraversalChecksumItemWriter} 가 순회 결과의 지문을 정확히 만드는지 검증한다.
 *
 * <p>이 체크섬은 3번 문제에서 <b>성능 비교의 전제</b>다. 이것이 틀리면 before 와 after 가 다른 일을
 * 하고 있어도 "같은 일을 더 빨리 했다" 로 읽히게 된다.
 */
class TraversalChecksumItemWriterTest {

    private final TraversalChecksumItemWriter writer = new TraversalChecksumItemWriter();

    @Test
    @DisplayName("건수·최소·최대·합을 누적한다")
    void 지문_누적() throws Exception {
        writer.write(chunk(1, 2, 3));
        writer.write(chunk(4, 5));

        assertThat(writer.checksum())
                .isEqualTo(new TraversalChecksum(5, 1L, 5L, 15));
    }

    @Test
    @DisplayName("같은 행을 두 번 읽으면 건수가 같아도 합이 달라진다 - 페이징 사고를 잡는 장치")
    void 중복_순회를_구분한다() throws Exception {
        writer.write(chunk(1, 2, 2));

        assertThat(writer.checksum().count())
                .as("건수만 보면 1,2,3 을 읽은 것과 구분되지 않는다").isEqualTo(3);
        assertThat(writer.checksum().idSum())
                .as("합은 다르다").isEqualTo(5).isNotEqualTo(6);
    }

    @Test
    @DisplayName("빈 청크는 아무것도 바꾸지 않는다 - 마지막 빈 페이지가 지문을 흔들지 않는다")
    void 빈_청크() throws Exception {
        writer.write(chunk(1, 2));
        writer.write(new Chunk<>(List.of()));

        assertThat(writer.checksum()).isEqualTo(new TraversalChecksum(2, 1L, 2L, 3));
    }

    @Test
    @DisplayName("한 행도 쓰지 않았으면 빈 지문이다")
    void 순회_없음() {
        assertThat(writer.checksum()).isEqualTo(TraversalChecksum.EMPTY);
        assertThat(writer.checksum().minId()).isNull();
        assertThat(writer.checksum().maxId()).isNull();
    }

    @Test
    @DisplayName("Step 이 시작되면 이전 실행의 누적을 지운다 - 두 번 돌리면 건수가 두 배로 보인다")
    void 실행마다_초기화() throws Exception {
        writer.write(chunk(1, 2, 3));

        writer.beforeStep(stepExecution());
        writer.write(chunk(1, 2));

        assertThat(writer.checksum()).isEqualTo(new TraversalChecksum(2, 1L, 2L, 3));
    }

    private static Chunk<MemberBase> chunk(long... ids) {
        List<MemberBase> members = new java.util.ArrayList<>();
        for (long id : ids) {
            members.add(member(id));
        }
        return new Chunk<>(members);
    }

    private static MemberBase member(long id) {
        return new MemberC(id, "member" + id + "@example.com", "회원" + id,
                MemberGrade.BRONZE, 0L, MemberStatus.ACTIVE, null, false, null,
                LocalDateTime.of(2026, 1, 1, 0, 0), null);
    }

    private static StepExecution stepExecution() {
        return new StepExecution("pagingStep", new JobExecution(1L));
    }
}
