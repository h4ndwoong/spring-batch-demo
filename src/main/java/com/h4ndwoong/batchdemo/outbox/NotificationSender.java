package com.h4ndwoong.batchdemo.outbox;

/**
 * 외부로 알림을 보낸다. <b>이 인터페이스의 계약이 7번 문제의 전제 자체다.</b>
 *
 * <p><b>계약</b>
 * <ol>
 *   <li>성공하면 정상 반환한다.</li>
 *   <li>실패하면 {@link NotificationException} 을 던진다.</li>
 *   <li><b>호출된 순간 알림은 외부에 나갔다.</b> 이후의 롤백은 이 호출에 아무 영향도 주지 않는다.</li>
 *   <li><b>같은 메시지를 두 번 주면 두 번 나간다.</b> 중복 제거는 이 인터페이스의 책임이 아니다.</li>
 * </ol>
 *
 * <p>3·4번 조항을 지키는 것이 중요하다. 스스로 중복을 걸러 주는 구현을 끼우면 before 의 증상이
 * 사라지고 7번은 성립하지 않는다. 실무의 SMS·푸시 게이트웨이가 정확히 이렇게 동작한다 — 같은
 * 문자를 두 번 요청하면 두 번 보낸다. <b>멱등은 보내는 쪽이 만들어야 한다.</b>
 *
 * <p><b>왜 인터페이스인가</b><br>
 * 이 실습에서 인터페이스로 두는 유일한 협력자다. 이유가 셋이다.
 * <ul>
 *   <li>채널 교체(문자·푸시·메일)는 실재하는 변경 지점이다.</li>
 *   <li>{@link FaultInjectingNotificationSender} 라는 둘째 구현이 이미 있다 — 데코레이터도 구현이다.</li>
 *   <li>before 의 라이터와 after 의 릴레이가 <b>같은 빈</b>을 주입받아야 발송 수를 한 축에서 잰다.</li>
 * </ul>
 * 반면 멱등키 생성이나 페이로드 형식은 구현이 하나뿐이므로 인터페이스로 만들지 않는다 (5번의
 * {@code ExpiryIdempotencyKey} 와 같은 판단이다).
 *
 * <p><b>실제 외부 API 를 부르지 않는다.</b> 이 실습의 구현체는 발송을 기록하고 로그로 남길 뿐이다.
 * 그래도 "롤백되지 않는다" 는 성질은 진짜다 — 인메모리 기록도 로그도 트랜잭션이 되돌리지 못한다.
 */
@FunctionalInterface
public interface NotificationSender {

    /**
     * 알림 한 건을 보낸다.
     *
     * @param message 보낼 메시지
     * @throws NotificationException 발송에 실패했을 때. <b>이미 보낸 것은 되돌아오지 않는다</b>
     */
    void send(NotificationMessage message);
}
