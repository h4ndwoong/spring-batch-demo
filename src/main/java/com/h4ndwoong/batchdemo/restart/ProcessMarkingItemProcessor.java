package com.h4ndwoong.batchdemo.restart;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import org.springframework.batch.item.ItemProcessor;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 가공이 끝난 행에 <b>처리 흔적</b>을 세우는 데코레이터. <b>after 에만 있다.</b>
 *
 * <p><b>이것이 5번 문제 개선의 본체다.</b> 계산은 위임 대상({@link PointExpiryItemProcessor})이
 * 그대로 하고, 여기서는 "이 행은 처리했다" 를 {@code processed} 와 {@code idempotency_key} 에
 * 세운다. 그 표시가 <b>차감과 같은 UPDATE, 같은 트랜잭션</b>으로 커밋되므로 (
 * {@link AfterRestartJobConfig} 의 UPDATE 문 참고) "차감은 됐는데 처리 표시는 안 된" 중간 상태가
 * 존재할 수 없다. 표시를 별도 문장으로 나누면 바로 그 틈에서 이중 차감이 다시 살아난다.
 *
 * <p><b>왜 {@link PointExpiryItemProcessor} 에 필드 하나를 더하지 않는가</b><br>
 * 그러면 그 클래스가 두 이유로 바뀐다 — 소멸 정책이 바뀔 때와 흔적 정책이 바뀔 때. 무엇보다
 * before 와 after 가 <b>같은 계산 코드를 쓴다</b>는 사실이 코드에서 사라진다. 5번의 before 가 틀린
 * 이유는 계산을 잘못해서가 아니므로, 계산이 양쪽에서 문자 그대로 같다는 것이 눈에 보여야 한다.
 *
 * <p>2번의 {@code FaultInjectingItemWriter} 가 라이터를 감쌌던 것과 같은 방식이다.
 */
public class ProcessMarkingItemProcessor implements ItemProcessor<MemberBase, MemberBase> {

    private final ItemProcessor<MemberBase, MemberBase> delegate;
    private final Clock clock;

    /**
     * 데코레이터를 만든다.
     *
     * @param delegate 실제 가공을 수행할 프로세서
     * @param clock    {@code updated_at} 의 출처. 위임 대상과 같은 시계를 쓴다
     */
    public ProcessMarkingItemProcessor(ItemProcessor<MemberBase, MemberBase> delegate, Clock clock) {
        this.delegate = delegate;
        this.clock = clock;
    }

    /**
     * {@inheritDoc}
     *
     * <p>위임 결과가 {@code null} 이면 표시하지 않는다. 걸러진 행은 처리된 적이 없으므로 흔적을
     * 남기면 안 되고, 남기면 다음 실행이 그 행을 영원히 건너뛴다. 현재 위임 대상은 {@code null} 을
     * 돌려주지 않지만, <b>데코레이터가 위임 대상의 구현을 전제해서는 안 된다.</b>
     *
     * @param item 읽어 온 회원
     * @return 가공되고 처리 표시가 선 회원. 위임 대상이 걸렀으면 {@code null}
     * @throws Exception 위임 대상이 던지는 것을 그대로 올린다
     */
    @Override
    public MemberBase process(MemberBase item) throws Exception {
        MemberBase processed = delegate.process(item);
        if (processed == null) {
            return null;
        }
        processed.markProcessed(ExpiryIdempotencyKey.of(processed.getId()), LocalDateTime.now(clock));
        return processed;
    }
}
