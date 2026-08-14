package com.h4ndwoong.batchdemo.lookup;

import com.h4ndwoong.batchdemo.domain.MemberGrade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.Chunk;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 결과 지문({@link GradeDecisionItemWriter})이 before/after 비교의 근거로 쓸 만한지 확인한다.
 *
 * <p>이 지문이 틀리면 "양쪽이 같은 답을 냈다" 는 확인이 통째로 무의미해진다.
 */
class GradeDecisionItemWriterTest {

    @Test
    @DisplayName("건수·변경 건수·포인트 합·등급 분포를 누적한다")
    void 누적() {
        GradeDecisionItemWriter writer = new GradeDecisionItemWriter();

        writer.write(Chunk.of(
                new GradeDecision(1L, MemberGrade.BRONZE, MemberGrade.GOLD, 5_000),
                new GradeDecision(2L, MemberGrade.GOLD, MemberGrade.GOLD, 6_000),
                new GradeDecision(3L, MemberGrade.VIP, MemberGrade.SILVER, 1_000)));

        assertThat(writer.checksum()).isEqualTo(new GradeDecisionChecksum(3, 2, 12_000,
                Map.of(MemberGrade.GOLD, 2L, MemberGrade.SILVER, 1L)));
    }

    @Test
    @DisplayName("여러 청크에 걸쳐 이어서 누적한다")
    void 여러_청크() {
        GradeDecisionItemWriter writer = new GradeDecisionItemWriter();

        writer.write(Chunk.of(new GradeDecision(1L, MemberGrade.BRONZE, MemberGrade.GOLD, 5_000)));
        writer.write(Chunk.of(new GradeDecision(2L, MemberGrade.BRONZE, MemberGrade.GOLD, 5_000)));

        assertThat(writer.checksum().count()).isEqualTo(2);
        assertThat(writer.checksum().distribution().get(MemberGrade.GOLD)).isEqualTo(2);
    }

    @Test
    @DisplayName("beforeStep 에서 이전 실행의 누적을 지운다 - 두 번째 실행이 두 배로 보이면 안 된다")
    void 실행마다_초기화() {
        GradeDecisionItemWriter writer = new GradeDecisionItemWriter();
        writer.write(Chunk.of(new GradeDecision(1L, MemberGrade.BRONZE, MemberGrade.GOLD, 5_000)));

        writer.beforeStep(null);

        assertThat(writer.checksum()).isEqualTo(GradeDecisionChecksum.EMPTY);
    }

    @Test
    @DisplayName("등급 분포는 네 등급을 모두 채운다 - 0건과 '키 없음' 이 달라 보이면 비교가 깨진다")
    void 분포_정규화() {
        GradeDecisionItemWriter writer = new GradeDecisionItemWriter();
        writer.write(Chunk.of(new GradeDecision(1L, MemberGrade.BRONZE, MemberGrade.GOLD, 5_000)));

        assertThat(writer.checksum().distribution())
                .containsOnlyKeys(MemberGrade.values())
                .containsEntry(MemberGrade.VIP, 0L);
    }
}
