package com.h4ndwoong.batchdemo.outbox;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.support.InjectedFailureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;

/**
 * 정해진 건수를 커밋한 뒤, 다음 청크를 <b>쓰고 나서</b> Step 을 끝장내는 라이터 데코레이터.
 * <b>양쪽 공통</b>이다.
 *
 * <p><b>5번의 주입기와 던지는 줄의 위치가 반대다. 그 한 줄이 7번의 전부다.</b>
 * <pre>
 *   5번 FailAfterCountItemWriter        7번 FailAfterWriteItemWriter
 *   ─────────────────────────────       ────────────────────────────
 *   if (도달) throw;                     delegate.write(chunk);   ← 알림이 나간다
 *   delegate.write(chunk);               if (도달) throw;         ← 커밋 직전에 죽는다
 * </pre>
 * 5번은 실제 쓰기 <em>전에</em> 던져 실패한 청크가 DB 에 아무 흔적도 남기지 않게 했다. 트랜잭션이
 * 이미 해결하고 있는 문제를 피해 간 것이다. 7번은 반대로 <b>쓰기가 전부 끝나고 커밋만 남은 순간</b>
 * 을 재현한다. 그 순간 롤백되는 것은 DB 뿐이고, 이미 게이트웨이로 나간 알림은 되돌아오지 않는다.
 *
 * <p><b>이것이 인위적인 상황이 아니다.</b> 커밋은 실패할 수 있다 — 락 대기 시간 초과, 커넥션 끊김,
 * 디스크 가득 참, 배포로 인한 프로세스 종료. 라이터의 코드가 전부 성공한 뒤에도 트랜잭션은 죽는다.
 * "쓰기가 성공했으니 알림을 보내도 된다" 는 판단이 틀리는 자리가 여기다.
 *
 * <p><b>회복되지 않는다.</b> {@link InjectedFailureException} 은 어떤 스킵·재시도 목록에도 없고
 * {@code outboxStep} 은 {@code faultTolerant} 가 아니다. 회복되면 "실패한 뒤 재실행" 이라는 상황이
 * 만들어지지 않는다 (5번과 같은 이유).
 *
 * <p><b>상태를 필드로 들고 있으므로 {@code @StepScope} 로 등록한다.</b> 커밋 건수는 Step 실행 하나
 * 안에서만 유지되어야 하며, 재실행하는 명령에서는 {@code failAfterCount} 를 빼는 것이 정상 경로다.
 *
 * <p>장애를 심지 않으려면 {@code failAfterCount} 를 {@code 0} 이하로 준다. 기본 실행 경로다.
 */
public class FailAfterWriteItemWriter implements ItemWriter<MemberBase> {

    private static final Logger log = LoggerFactory.getLogger(FailAfterWriteItemWriter.class);

    private final ItemWriter<MemberBase> delegate;
    private final long failAfterCount;

    private long committed;

    /**
     * 장애 주입기를 만든다.
     *
     * @param delegate       실제 쓰기를 수행할 라이터
     * @param failAfterCount 이 건수를 커밋한 뒤 다음 청크를 쓰고 나서 실패시킨다.
     *                       {@code 0} 이하면 실패시키지 않는다
     */
    public FailAfterWriteItemWriter(ItemWriter<MemberBase> delegate, long failAfterCount) {
        this.delegate = delegate;
        this.failAfterCount = failAfterCount;
    }

    /**
     * {@inheritDoc}
     *
     * <p>위임이 <b>먼저</b>다. 그래서 실패하는 청크도 라이터의 일을 전부 마친다 — before 라면
     * 알림이 나가고, after 라면 outbox 에 적재된다. 그리고 롤백된다. <b>둘 중 되돌아오는 것은
     * 하나뿐</b>이라는 사실이 7번의 before/after 를 가른다.
     *
     * @param chunk 쓸 항목들
     * @throws InjectedFailureException 커밋된 건수가 {@code failAfterCount} 에 도달했을 때
     * @throws Exception                위임 대상이 던지는 것을 그대로 올린다
     */
    @Override
    public void write(Chunk<? extends MemberBase> chunk) throws Exception {
        delegate.write(chunk);

        if (failAfterCount > 0 && committed >= failAfterCount) {
            InjectedFailureException failure = new InjectedFailureException(committed);
            log.info("장애 주입: {} (이 청크 {}건은 쓰기를 마쳤고 이제 롤백된다)",
                    failure.getMessage(), chunk.size());
            throw failure;
        }
        committed += chunk.size();
    }

    /**
     * 이 Step 실행에서 커밋된 행 수. <b>실패한 청크는 포함하지 않는다.</b>
     *
     * @return 커밋된 행 수
     */
    public long committedCount() {
        return committed;
    }
}
