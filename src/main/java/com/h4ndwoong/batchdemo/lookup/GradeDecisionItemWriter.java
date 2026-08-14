package com.h4ndwoong.batchdemo.lookup;

import com.h4ndwoong.batchdemo.domain.MemberGrade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;

import java.util.EnumMap;
import java.util.Map;

/**
 * 등급 산정 결과를 세기만 하고 <b>DB 에는 아무것도 쓰지 않는</b> 라이터.
 *
 * <p><b>왜 쓰지 않는가</b><br>
 * 4번 문제의 측정 대상은 <em>조회 경로</em>다. 행마다 UPDATE 를 하면 2번 문제에서 실측된 대로
 * 행당 1회씩 왕복이 얹히는데(50만 회), 그러면 before 100만 회 대 after 500회였던 조회 왕복의
 * 차이가 <b>150만 대 50만, 즉 3배로 희석</b>된다. 쓰기 경로는 6번 문제의 주제다. 3번에서
 * {@code TraversalChecksumItemWriter} 가 같은 판단을 했다.
 *
 * <p><b>누적을 불변 값으로 하지 않는 이유</b><br>
 * 3번의 체크섬은 {@code long} 네 개라 행마다 새 인스턴스를 만들어도 쌌다. 여기는 등급 분포까지
 * 들고 있어 50만 번의 맵 복사가 되는데, 그 할당이 측정 노이즈가 된다. 누적은 여기서 가변으로 하고,
 * 불변 지문({@link GradeDecisionChecksum})은 요청받을 때 만든다.
 *
 * <p><b>{@code @StepScope} 가 아니다.</b> {@link #beforeStep} 에서 비우므로 싱글턴으로 충분하고,
 * 싱글턴이어야 Step 이 끝난 뒤 테스트가 같은 인스턴스에서 결과를 읽을 수 있다. Step 빌더가
 * 라이터를 {@link StepExecutionListener} 로 <b>자동 등록</b>하므로 구성에서 따로 등록하지 않는다.
 */
public class GradeDecisionItemWriter implements ItemWriter<GradeDecision>, StepExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(GradeDecisionItemWriter.class);

    private final Map<MemberGrade, Long> distribution = new EnumMap<>(MemberGrade.class);

    private long count;
    private long changed;
    private long effectivePointSum;

    /**
     * {@inheritDoc}
     *
     * @param chunk 산정 결과들
     */
    @Override
    public void write(Chunk<? extends GradeDecision> chunk) {
        for (GradeDecision decision : chunk) {
            count++;
            if (decision.changed()) {
                changed++;
            }
            effectivePointSum += decision.effectivePoint();
            distribution.merge(decision.newGrade(), 1L, Long::sum);
        }
    }

    /**
     * 지금까지의 산정 결과 지문.
     *
     * @return 지문. 한 행도 산정하지 않았으면 {@link GradeDecisionChecksum#EMPTY}
     */
    public GradeDecisionChecksum checksum() {
        if (count == 0) {
            return GradeDecisionChecksum.EMPTY;
        }
        return new GradeDecisionChecksum(count, changed, effectivePointSum, distribution);
    }

    /**
     * {@inheritDoc}
     *
     * <p>이전 실행의 누적을 지운다. 비우지 않으면 두 번째 실행의 건수가 두 배로 보인다.
     */
    @Override
    public void beforeStep(StepExecution stepExecution) {
        distribution.clear();
        count = 0;
        changed = 0;
        effectivePointSum = 0;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code null} 을 돌려주어 {@code ExitStatus} 를 그대로 둔다.
     */
    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        log.info("등급 산정 체크섬: {}", checksum().summary());
        return null;
    }
}
