package com.h4ndwoong.batchdemo.outbox;

import java.time.LocalDateTime;

/**
 * {@code member_g_outbox} 의 한 행. <b>커밋된 "보내겠다는 약속"</b> 이다.
 *
 * <p>Outbox 패턴의 전부가 이 레코드의 존재 방식에 있다. 알림은 트랜잭션에 넣을 수 없지만
 * <b>알림을 보내겠다는 사실은 넣을 수 있다.</b> 상태 변경과 이 행의 적재가 같은 트랜잭션에서
 * 커밋되므로, "상태가 바뀌었다" 와 "알림을 보내야 한다" 는 <b>함께 참이거나 함께 거짓</b>이다.
 * before 에서 그 둘이 어긋났던 자리가 여기서 닫힌다.
 *
 * <p><b>{@link NotificationMessage} 와 무엇이 다른가</b><br>
 * 담고 있는 내용은 같다. 다른 것은 <b>어디에 있는가</b>다 — 이쪽은 DB 의 행이고 저쪽은 게이트웨이로
 * 나갈 값이다. {@link #toNotification()} 이 그 경계를 건너는 유일한 지점이며, 그 호출 이후로는
 * 되돌릴 수 없다.
 *
 * <p>{@code retry_count} 와 {@code last_error} 는 담지 않는다. 이 실습의 릴레이는 실패하면 예외를
 * 올려 {@code PENDING} 으로 남길 뿐 재시도 정책을 갖지 않는다 ({@link OutboxStatus#FAILED} 참고).
 * 읽지 않는 값을 레코드에 넣으면 "쓰이고 있다" 는 착각을 준다.
 *
 * @param id             Outbox 행 식별자. 릴레이가 이 순서로 보낸다
 * @param memberId       수신 회원
 * @param eventType      이벤트 종류
 * @param payload        발송 본문
 * @param idempotencyKey 멱등키. 이 컬럼의 UNIQUE 제약이 같은 알림의 이중 적재를 막는다
 * @param createdAt      적재 시각 = 상태가 바뀐 시각
 */
public record OutboxMessage(Long id,
                            Long memberId,
                            String eventType,
                            String payload,
                            String idempotencyKey,
                            LocalDateTime createdAt) {

    /**
     * 밖으로 나갈 값으로 바꾼다. <b>이 값이 발송기로 넘어가는 순간 트랜잭션의 영역을 벗어난다.</b>
     *
     * @return 알림 메시지
     */
    public NotificationMessage toNotification() {
        return new NotificationMessage(memberId, eventType, payload, idempotencyKey, createdAt);
    }
}
