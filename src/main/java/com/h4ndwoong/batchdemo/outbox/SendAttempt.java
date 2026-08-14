package com.h4ndwoong.batchdemo.outbox;

/**
 * 실제로 외부로 나간 알림 한 건의 흔적. {@link NotificationRecorder} 가 순서대로 모은다.
 *
 * <p>{@link NotificationMessage} 를 통째로 들고 있지 않은 이유는 두 가지다. 10만 건의 본문을 메모리에
 * 쌓을 이유가 없고, 무엇보다 <b>여기서 세어야 하는 것은 본문이 아니라 "누구에게 몇 번" 이기 때문</b>이다.
 *
 * @param memberId       수신 회원. 유령 알림을 가리려면 DB 의 상태와 대조해야 하므로 필요하다
 * @param idempotencyKey 멱등키. 중복 발송은 이 값이 두 번 나타나는 것으로 정의된다
 */
public record SendAttempt(Long memberId, String idempotencyKey) {
}
