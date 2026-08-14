package com.h4ndwoong.batchdemo.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 정해진 건수를 보낸 뒤 발송을 실패시키는 데코레이터. <b>양쪽 공통</b>이며 기본은 아무 일도 하지 않는다.
 *
 * <p>2번의 {@code FaultInjectingItemWriter}, 5번의 {@code FailAfterCountItemWriter} 와 같은 계열의
 * 장치다. 다른 점은 <b>실패하는 자리가 DB 가 아니라 외부</b>라는 것이고, 그래서 이 장치가 만드는
 * 상황은 두 프로파일에서 서로 다른 이름을 갖는다.
 * <ul>
 *   <li>before — 청크의 앞부분은 이미 나갔는데 상태 변경은 롤백된다. <b>유령 알림</b>이 실패 하나에
 *       여러 건 생긴다.</li>
 *   <li>after (릴레이) — {@code SENT} 표시가 롤백되어 그 청크가 {@code PENDING} 으로 남는다. 다음
 *       실행이 <b>이미 보낸 것까지 다시 보낸다</b>. Outbox 가 exactly-once 가 아니라 at-least-once
 *       라는 사실이 여기서 숫자로 드러난다.</li>
 * </ul>
 * <b>after 의 한계를 감추지 않기 위해 있는 장치</b>다. "개선안이 무엇을 해결하지 <em>못하는가</em>"
 * 를 말할 수 없으면 그것은 개선안이 아니다 (6번의 락 유지 시간과 같은 자리).
 *
 * <p>{@code outbox.send-fail-after} 를 {@code 0} 이하로 두면 그냥 위임한다. 기본 실행 경로다.
 *
 * <p><b>싱글턴이며 카운터를 필드로 든다.</b> 2·5번의 주입기가 {@code @StepScope} 였던 것과 다르다.
 * 그쪽은 "한 Step 실행 안에서 N 건" 이 기준이었지만, 여기서는 <b>실패한 실행과 그 다음 실행을
 * 가로질러</b> 딱 한 번만 실패해야 재발송 중복을 측정할 수 있다. Step 마다 카운터가 초기화되면
 * 다음 실행도 같은 자리에서 또 실패해서 영원히 끝나지 않는다.
 */
public class FaultInjectingNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(FaultInjectingNotificationSender.class);

    private final NotificationSender delegate;
    private final long failAfterSends;
    private final int failTimes;

    private long sent;
    private int thrown;

    /**
     * 주입기를 만든다.
     *
     * @param delegate       실제 발송을 수행할 발송기
     * @param failAfterSends 이 건수를 보낸 뒤 다음 발송을 실패시킨다. {@code 0} 이하면 실패시키지 않는다
     * @param failTimes      실패시킬 횟수. 다 채우면 그 뒤로는 정상 발송한다
     */
    public FaultInjectingNotificationSender(NotificationSender delegate,
                                            long failAfterSends,
                                            int failTimes) {
        this.delegate = delegate;
        this.failAfterSends = failAfterSends;
        this.failTimes = failTimes;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>위임하지 않고 던진다.</b> 실패한 발송은 나가지 않은 발송이다. 이 장치가 만드는 피해는
     * "실패한 그 건" 이 아니라 <b>같은 청크에서 그보다 먼저 나간 건들</b>이다.
     */
    @Override
    public void send(NotificationMessage message) {
        if (shouldFail()) {
            thrown++;
            NotificationException failure = new NotificationException(
                    "주입된 발송 장애: %d 건을 보낸 뒤 실패한다 (%s)".formatted(sent, message.idempotencyKey()));
            log.info("장애 주입: {}", failure.getMessage());
            throw failure;
        }
        delegate.send(message);
        sent++;
    }

    private boolean shouldFail() {
        return failAfterSends > 0 && thrown < failTimes && sent >= failAfterSends;
    }

    /**
     * 지금까지 실제로 던진 장애의 수. 테스트가 "장애가 실제로 심겼는가" 를 확인하는 데 쓴다.
     *
     * @return 던진 횟수
     */
    public int thrownCount() {
        return thrown;
    }
}
