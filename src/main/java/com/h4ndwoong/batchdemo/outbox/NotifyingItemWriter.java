package com.h4ndwoong.batchdemo.outbox;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;

/**
 * 알림을 보내고 DB 쓰기를 위임하는 라이터. <b>7번 before 가 재현하는 증상 그 자체</b>다.
 *
 * <p><b>두 책임을 일부러 한 자리에 둔다.</b> SRP 위반이 아니라 재현 대상이다. 실무에서 이 코드는
 * 이상해 보이지 않는다 — 오히려 자연스럽다. "상태를 바꾸고 알림을 보낸다" 는 요구사항을 그대로
 * 옮기면 이렇게 되고, 리뷰에서 지적당할 구석도 없다. 문제는 <b>두 줄이 같은 메서드 안에 있다는
 * 사실이 두 줄이 같은 트랜잭션 안에 있다는 뜻은 아니라는 것</b>이다.
 *
 * <pre>
 *   sender.send(...)        ← 커밋도 롤백도 이것을 되돌리지 못한다
 *   delegate.write(chunk)   ← 이것만 트랜잭션 안에 있다
 * </pre>
 *
 * <p><b>순서를 바꿔도 낫지 않다.</b> UPDATE 를 먼저 보내고 발송을 나중에 해도, 커밋이 실패하면
 * UPDATE 는 롤백되고 알림은 남는다. 발송을 청크 커밋 <em>이후</em>로 미룰 방법이 라이터 안에는
 * 없다 — 라이터는 커밋 시점을 알지 못한다. <b>문제는 코드의 순서가 아니라 경계이고, 경계는 라이터가
 * 바꿀 수 있는 것이 아니다.</b> 그래서 after 는 라이터를 고치는 대신 Step 을 하나 더 둔다.
 *
 * <p><b>발송이 중간에 실패하면 앞의 것은 이미 나가 있다.</b> 청크의 몇 번째에서 실패하든 그 앞은
 * 되돌릴 수 없고, 상태 변경은 전부 롤백된다. 실패 한 번이 유령 알림 여러 건을 만드는 두 번째
 * 경로다 ({@link FaultInjectingNotificationSender} 로 재현할 수 있다).
 */
public class NotifyingItemWriter implements ItemWriter<MemberBase> {

    private final NotificationSender sender;
    private final ItemWriter<MemberBase> delegate;

    /**
     * 라이터를 만든다.
     *
     * @param sender   알림 발송기
     * @param delegate 상태 변경을 수행할 라이터
     */
    public NotifyingItemWriter(NotificationSender sender, ItemWriter<MemberBase> delegate) {
        this.sender = sender;
        this.delegate = delegate;
    }

    /**
     * {@inheritDoc}
     *
     * <p>알림을 먼저 보내고 DB 에 쓴다. 실무에서 흔한 순서이며, 앞서 적은 대로 순서를 뒤집어도
     * 결과는 같다.
     *
     * @param chunk 쓸 항목들
     * @throws NotificationException 발송에 실패했을 때. <b>이미 보낸 것은 되돌아오지 않는다</b>
     * @throws Exception             위임 대상이 던지는 것을 그대로 올린다
     */
    @Override
    public void write(Chunk<? extends MemberBase> chunk) throws Exception {
        for (MemberBase member : chunk) {
            sender.send(StatusChangedNotification.of(member));
        }
        delegate.write(chunk);
    }
}
