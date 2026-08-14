package com.h4ndwoong.batchdemo.outbox;

/**
 * 알림 발송이 실패했다. {@link NotificationSender} 계약의 실패 신호다.
 *
 * <p><b>이 예외가 청크 트랜잭션을 타고 올라가면 무슨 일이 벌어지는가가 프로파일마다 다르다.</b>
 * <ul>
 *   <li>before — 그 청크의 상태 변경이 롤백된다. 그런데 <b>실패 이전에 이미 보낸 알림은 남는다.</b>
 *       실패 하나가 유령 알림 여러 건을 만든다.</li>
 *   <li>after (릴레이) — {@code SENT} 표시가 롤백되어 {@code PENDING} 으로 남는다. 다음 실행이 그
 *       청크를 <b>통째로 다시 보낸다</b> — 이미 보낸 것까지. 이것이 Outbox 가 exactly-once 가 아니라
 *       <b>at-least-once</b> 인 이유이고, 청크 크기가 곧 중복의 상한인 이유다.</li>
 * </ul>
 *
 * <p>런타임 예외인 것은 {@code ItemWriter} 의 서명을 오염시키지 않기 위해서만은 아니다. 발송 실패는
 * <b>호출한 쪽이 그 자리에서 처리할 수 있는 종류의 일이 아니다.</b> 재시도할지 미룰지는 트랜잭션
 * 경계를 아는 쪽 — Step 구성 — 이 정한다.
 */
public class NotificationException extends RuntimeException {

    /**
     * 예외를 만든다.
     *
     * @param message 실패 사유
     */
    public NotificationException(String message) {
        super(message);
    }
}
