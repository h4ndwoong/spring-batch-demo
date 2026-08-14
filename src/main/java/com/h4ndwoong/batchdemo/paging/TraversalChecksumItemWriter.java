package com.h4ndwoong.batchdemo.paging;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;

/**
 * 순회한 행을 세기만 하고 <b>DB 에는 아무것도 쓰지 않는</b> 라이터.
 *
 * <p><b>왜 쓰지 않는가</b><br>
 * 3번 문제의 측정 대상은 <em>읽기 경로</em>다. 여기서 행마다 UPDATE 를 하면 그 비용이 페이지 획득
 * 시간의 차이를 덮어 버린다. 2번 문제에서 실측된 대로 UPDATE 는 행당 1회 왕복하므로, 200만 건이면
 * 200만 번의 왕복이 얹힌다 — 그건 <b>6번 문제(대량 UPDATE 쓰기 경로)</b>의 주제이지 3번의 것이 아니다.
 *
 * <p>대신 {@code WRITE_COUNT} 와 커밋 횟수는 그대로 남는다. Spring Batch 는 라이터에 넘긴 항목 수를
 * 세므로, 쓰지 않아도 Step 통계는 정상적으로 채워진다.
 *
 * <p><b>세는 것이 곧 검증이다.</b> 이 라이터가 만드는 {@link TraversalChecksum} 은 before 와 after 가
 * 같은 행 집합을 같은 범위로 순회했다는 증거다. 이것이 맞지 않으면 시간 비교는 무의미하다.
 *
 * <p><b>{@code @StepScope} 가 아니다.</b> 상태를 들고 있지만 {@link #beforeStep} 에서 비우므로
 * 싱글턴으로 충분하고, 싱글턴이어야 Step 이 끝난 뒤 테스트가 같은 인스턴스에서 결과를 읽을 수 있다.
 * Step 빌더는 라이터가 {@link StepExecutionListener} 이면 <b>자동으로 리스너로 등록</b>하므로
 * ({@code SimpleStepBuilder.registerAsStreamsAndListeners}) 구성에서 따로 등록하지 않는다 —
 * 등록하면 두 번 불린다.
 */
public class TraversalChecksumItemWriter implements ItemWriter<MemberBase>, StepExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(TraversalChecksumItemWriter.class);

    private TraversalChecksum checksum = TraversalChecksum.EMPTY;

    /**
     * {@inheritDoc}
     *
     * <p>식별자만 본다. 나머지 컬럼은 읽어 오는 비용(= 측정 대상)에는 포함되지만 여기서 할 일은 없다.
     *
     * @param chunk 순회한 항목들
     */
    @Override
    public void write(Chunk<? extends MemberBase> chunk) {
        for (MemberBase member : chunk) {
            checksum = checksum.accumulate(member.getId());
        }
    }

    /**
     * 지금까지 순회한 행의 지문.
     *
     * @return 체크섬. 한 행도 순회하지 않았으면 {@link TraversalChecksum#EMPTY}
     */
    public TraversalChecksum checksum() {
        return checksum;
    }

    /**
     * {@inheritDoc}
     *
     * <p>이전 실행의 누적을 지운다. 비우지 않으면 두 번째 실행의 건수가 두 배로 보인다.
     */
    @Override
    public void beforeStep(StepExecution stepExecution) {
        checksum = TraversalChecksum.EMPTY;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code null} 을 돌려주어 {@code ExitStatus} 를 그대로 둔다.
     */
    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        log.info("순회 체크섬: count={}, minId={}, maxId={}, idSum={}",
                checksum.count(), checksum.minId(), checksum.maxId(), checksum.idSum());
        return null;
    }
}
