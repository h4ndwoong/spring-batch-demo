package com.h4ndwoong.batchdemo.restart;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.support.InjectedFailureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;

/**
 * 정해진 건수를 커밋한 뒤 다음 청크에서 Step 을 끝장내는 라이터 데코레이터. <b>양쪽 공통</b>이다.
 *
 * <p><b>왜 건수 기준인가</b><br>
 * 2번 문제의 장애 주입기는 "청크의 첫 행 {@code id}" 를 기준으로 삼았다. 거기서는 리더가 조건 없이
 * 전 행을 읽어 {@code id} 와 청크 경계가 맞아떨어졌기 때문이다. 5번의 리더는 {@code WHERE} 절로
 * 행을 걸러 내고 (after 는 실행마다 결과셋이 달라지기까지 한다) <b>{@code id} 와 청크 경계가
 * 맞지 않는다.</b> 커밋된 건수를 기준으로 하면 어떤 조건, 어떤 프로파일에서도 "정확히 N 건을
 * 커밋한 상태" 를 만들 수 있다 — 대사식이 정확해지려면 이 지점이 정확해야 한다.
 *
 * <p><b>왜 위임 전에 던지는가</b><br>
 * 실제 UPDATE 를 보내기 전에 던지므로 실패한 청크는 DB 에 아무 흔적도 남기지 않는다. 남은 것은
 * 오직 <b>그 앞까지 커밋된 것</b>이고, 그 상태에서 다시 실행했을 때 무슨 일이 일어나는가가 5번의
 * 전부다.
 *
 * <p>7번의 {@link com.h4ndwoong.batchdemo.outbox.FailAfterWriteItemWriter} 는 <b>정확히 반대로</b>
 * 위임한 뒤에 던진다. 5번은 트랜잭션이 이미 해결한 문제를 피해 가고, 7번은 트랜잭션이 해결하지
 * 못하는 것 — 이미 밖으로 나간 알림 — 을 드러낸다. 같은 예외를 던지는 두 장치의 유일한 차이가
 * <b>던지는 줄의 위치</b>이고, 그 한 줄이 두 문제를 가른다.
 *
 * <p><b>왜 재시도·스킵으로 회복되지 않는가</b><br>
 * {@link InjectedFailureException} 은 어떤 분류 목록에도 없고 {@code restartStep} 은
 * {@code faultTolerant} 가 아니다. 회복되면 "실패한 뒤 재실행" 이라는 상황이 만들어지지 않는다.
 *
 * <p><b>상태를 필드로 들고 있으므로 {@code @StepScope} 로 등록한다.</b> 커밋 건수는 Step 실행 하나
 * 안에서만 유지되어야 한다. 재시작된 실행은 0 부터 다시 세므로, 재시작할 때 장애 파라미터를 그대로
 * 두면 <b>또 실패한다</b> — 그래서 {@code failAfterCount} 는 비식별 파라미터이고, 재시작 명령에서는
 * 빼는 것이 정상 경로다 ({@link RestartJobCommonConfig} 참고).
 *
 * <p>장애를 심지 않으려면 {@code failAfterCount} 를 {@code 0} 이하로 준다. 기본 실행 경로다.
 */
public class FailAfterCountItemWriter implements ItemWriter<MemberBase> {

    private static final Logger log = LoggerFactory.getLogger(FailAfterCountItemWriter.class);

    private final ItemWriter<MemberBase> delegate;
    private final long failAfterCount;

    private long written;

    /**
     * 장애 주입기를 만든다.
     *
     * @param delegate       실제 쓰기를 수행할 라이터
     * @param failAfterCount 이 건수를 쓴 뒤 다음 청크에서 실패시킨다. {@code 0} 이하면 실패시키지 않는다
     */
    public FailAfterCountItemWriter(ItemWriter<MemberBase> delegate, long failAfterCount) {
        this.delegate = delegate;
        this.failAfterCount = failAfterCount;
    }

    /**
     * {@inheritDoc}
     *
     * @param chunk 쓸 항목들
     * @throws InjectedFailureException 커밋된 건수가 {@code failAfterCount} 에 도달했을 때
     * @throws Exception                위임 대상이 던지는 것을 그대로 올린다
     */
    @Override
    public void write(Chunk<? extends MemberBase> chunk) throws Exception {
        if (failAfterCount > 0 && written >= failAfterCount) {
            InjectedFailureException failure = new InjectedFailureException(written);
            log.info("장애 주입: {}", failure.getMessage());
            throw failure;
        }
        delegate.write(chunk);
        written += chunk.size();
    }

    /**
     * 이 Step 실행에서 실제로 쓴 행 수. 테스트가 "어디까지 커밋되었는가" 를 확인하는 데 쓴다.
     *
     * @return 쓴 행 수
     */
    public long writtenCount() {
        return written;
    }
}
