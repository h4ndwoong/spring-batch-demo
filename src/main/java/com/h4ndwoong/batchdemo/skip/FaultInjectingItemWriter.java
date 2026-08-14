package com.h4ndwoong.batchdemo.skip;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;

/**
 * 지정한 청크의 쓰기를 정해진 횟수만큼 실패시키는 라이터 데코레이터. <b>재시도 실습용 장애 주입기</b>다.
 *
 * <p><b>왜 진짜 장애를 쓰지 않는가</b><br>
 * 2번 문제는 "일시 오류는 재시도하고 데이터 오류는 스킵한다" 는 <em>구분</em>을 실습한다. 진짜 락
 * 타임아웃을 만들려면 다른 세션이 같은 행을 잡고 있어야 하는데, 그 타이밍은 실행마다 달라져서
 * before/after 를 같은 축에서 비교할 수 없다. 여기서는 {@code (faultAtId, faultTimes)} 로 장애를
 * <b>결정론적으로</b> 심는다. 던지는 예외가 Spring Batch 의 재시도 경로에서 실제 락 타임아웃과
 * 똑같이 취급되므로({@link FaultKind#TRANSIENT} 참고) 실습의 목적에는 충분하다.
 *
 * <p><b>왜 위임 전에 던지는가</b><br>
 * 실제 UPDATE 를 보내기 <em>전에</em> 던지므로 DB 에는 아무 흔적이 남지 않는다. 부분 적용된 청크를
 * 되돌리는 문제는 트랜잭션이 이미 해결하고 있고, 여기서 재현하려는 것은 그것이 아니라
 * "재시도가 몇 번 일어났고 그 대가가 얼마인가" 이기 때문이다.
 *
 * <p><b>상태를 필드로 들고 있다.</b> 시도 횟수를 세야 재시도의 끝을 정할 수 있다. 그래서 이 라이터는
 * {@code @StepScope} 로 등록한다. 한 Step 실행 안에서만 카운터가 유지되어야, 테스트가 여러 Job 을
 * 연달아 실행해도 앞 실행의 흔적이 남지 않는다.
 *
 * <p>장애를 심지 않으려면 {@code faultAtId} 를 {@code 0} 이하로 준다. 기본 실행 경로다.
 */
public class FaultInjectingItemWriter implements ItemWriter<MemberBase> {

    private static final Logger log = LoggerFactory.getLogger(FaultInjectingItemWriter.class);

    private final ItemWriter<MemberBase> delegate;
    private final long faultAtId;
    private final int faultTimes;
    private final FaultKind kind;

    private int thrown;

    /**
     * 장애 주입기를 만든다.
     *
     * @param delegate   실제 쓰기를 수행할 라이터
     * @param faultAtId  장애를 심을 청크의 첫 행 식별자. {@code 0} 이하면 장애를 심지 않는다
     * @param faultTimes 실패시킬 횟수. after 의 {@code retryLimit} 보다 작으면 재시도로 회복되고,
     *                   크거나 같으면 재시도가 소진되어 Step 이 실패한다
     * @param kind       장애의 종류
     */
    public FaultInjectingItemWriter(ItemWriter<MemberBase> delegate,
                                    long faultAtId,
                                    int faultTimes,
                                    FaultKind kind) {
        this.delegate = delegate;
        this.faultAtId = faultAtId;
        this.faultTimes = faultTimes;
        this.kind = kind;
    }

    /**
     * {@inheritDoc}
     *
     * <p>청크의 <b>첫 행</b> 식별자로 대상을 판별한다. 청크 전체를 뒤지지 않는 이유는, 스킵이
     * 발생한 청크가 행 단위로 재처리될 때(scanning) 청크 하나에 행이 하나만 담기기 때문이다.
     * 첫 행 기준이면 "1000행짜리 청크"와 "그 청크가 쪼개진 1행짜리 청크" 를 같은 규칙으로 다룰 수 있다.
     *
     * @param chunk 쓸 항목들
     * @throws RuntimeException {@link FaultKind} 가 만든 예외. 정해진 횟수를 채우면 더 던지지 않는다
     */
    @Override
    public void write(Chunk<? extends MemberBase> chunk) throws Exception {
        if (shouldFail(chunk)) {
            thrown++;
            RuntimeException fault = kind.create(faultAtId, thrown);
            log.info("장애 주입: {}", fault.getMessage());
            throw fault;
        }
        delegate.write(chunk);
    }

    private boolean shouldFail(Chunk<? extends MemberBase> chunk) {
        if (faultAtId <= 0 || thrown >= faultTimes || chunk.isEmpty()) {
            return false;
        }
        Long firstId = chunk.getItems().get(0).getId();
        return firstId != null && firstId == faultAtId;
    }

    /**
     * 지금까지 실제로 던진 장애의 수. 테스트가 "재시도가 몇 번 일어났는가" 를 확인하는 데 쓴다.
     *
     * @return 던진 횟수
     */
    public int thrownCount() {
        return thrown;
    }
}
