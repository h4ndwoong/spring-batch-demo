package com.h4ndwoong.batchdemo.update;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.domain.MemberF;
import com.h4ndwoong.batchdemo.domain.MemberGrade;
import com.h4ndwoong.batchdemo.domain.MemberStatus;
import com.h4ndwoong.batchdemo.support.GradePolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 6번 before 의 가공({@link GradeAssigningItemProcessor})이 <b>무엇을 쓰고 무엇을 버리는지</b> 고정한다.
 *
 * <p>DB 를 쓰지 않는다. 여기서 정한 "바뀐 행만 쓴다" 가 after 의 {@code AND grade <> CASE ...} 와
 * 같은 규칙이어야 두 프로파일의 갱신 행 수가 같아지고, 그래야 왕복 비교가 성립한다.
 */
class GradeAssigningItemProcessorTest {

    private static final GradePolicy POLICY = new GradePolicy(1_000, 5_000, 10_000);

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 15, 10, 0);

    private final GradeAssigningItemProcessor processor = new GradeAssigningItemProcessor(
            POLICY, Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault()));

    @Test
    @DisplayName("등급이 바뀌면 새 등급과 수정 시각을 담아 돌려준다")
    void 바뀐_행() {
        MemberBase member = member(7_000, MemberGrade.BRONZE);

        MemberBase processed = processor.process(member);

        assertThat(processed).isNotNull();
        assertThat(processed.getGrade()).isEqualTo(MemberGrade.GOLD);
        assertThat(processed.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("등급이 그대로면 null 을 돌려준다 - 보낼 UPDATE 가 없다")
    void 바뀌지_않은_행() {
        MemberBase member = member(7_000, MemberGrade.GOLD);

        assertThat(processor.process(member))
                .as("2번의 '가공은 null 을 돌려주지 않는다' 와 다른 상황이다. 여기서는 감추는 것이 없다")
                .isNull();
        assertThat(member.getUpdatedAt())
                .as("버릴 행의 수정 시각을 건드리면 체크섬이 프로파일마다 달라진다")
                .isNull();
    }

    @Test
    @DisplayName("임계값에 정확히 걸치면 상위 등급이다 - after 의 CASE 식과 같은 경계")
    void 경계값() {
        assertThat(processor.process(member(10_000, MemberGrade.BRONZE)).getGrade())
                .isEqualTo(MemberGrade.VIP);
        assertThat(processor.process(member(9_999, MemberGrade.BRONZE)).getGrade())
                .isEqualTo(MemberGrade.GOLD);
    }

    @Test
    @DisplayName("등급 외에는 아무것도 바꾸지 않는다 - 6번이 파괴하는 컬럼은 grade 하나다")
    void 다른_컬럼은_그대로() {
        MemberBase member = member(7_000, MemberGrade.BRONZE);

        MemberBase processed = processor.process(member);

        assertThat(processed.getPoint()).isEqualTo(7_000);
        assertThat(processed.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(processed.isProcessed()).isFalse();
        assertThat(processed.getIdempotencyKey()).isNull();
    }

    private static MemberBase member(long point, MemberGrade grade) {
        return new MemberF(1L, "user1@example.com", "김민준", grade, point, MemberStatus.ACTIVE,
                null, false, null, LocalDateTime.of(2026, 1, 1, 0, 0), null);
    }
}
