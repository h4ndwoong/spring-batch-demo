package com.h4ndwoong.batchdemo.outbox;

import java.time.LocalDateTime;

/**
 * 외부로 나갈 알림 한 건. <b>before 와 after 가 문자 그대로 같은 값을 만든다.</b>
 *
 * <p>7번의 비교 축은 "무엇을 보내는가" 가 아니라 <b>"언제, 어떤 트랜잭션 경계에서 보내는가"</b> 다.
 * 그래서 메시지 자체는 {@link StatusChangedNotification} 한 곳에서만 만들어지고 양쪽이 그것을
 * 공유한다. 메시지가 프로파일마다 다르면 "같은 알림을 몇 번 보냈는가" 라는 질문이 성립하지 않는다.
 *
 * <p><b>{@code idempotencyKey} 를 양쪽 다 싣는다.</b> before 에도 키가 있다. 그런데도 before 는
 * 중복 발송한다 — <b>키는 그것으로 무언가를 거절하는 쪽이 있을 때만 산다.</b> 5번에서
 * "UNIQUE 제약은 그 컬럼에 실제로 쓰는 코드가 있을 때만 산다" 를 배웠고, 여기서는 그 문장의
 * 바깥 판을 본다. 키를 붙이는 것은 개선이 아니다.
 *
 * @param memberId       수신 회원 식별자
 * @param eventType      이벤트 종류. {@code member_g_outbox.event_type} 에 그대로 들어간다
 * @param payload        발송 본문. {@code VARCHAR(2000)} 안에 들어가야 한다
 * @param idempotencyKey 이 알림을 유일하게 가리키는 키. {@code VARCHAR(64)} 안에 들어가야 한다
 * @param createdAt      알림이 만들어진 시각. 상태가 바뀐 시각과 같다
 */
public record NotificationMessage(Long memberId,
                                  String eventType,
                                  String payload,
                                  String idempotencyKey,
                                  LocalDateTime createdAt) {

    /** {@code member_g_outbox.payload} 컬럼 길이. */
    public static final int PAYLOAD_LIMIT = 2_000;

    /** {@code member_g_outbox.idempotency_key} 컬럼 길이. */
    public static final int KEY_LIMIT = 64;

    /**
     * 메시지를 만든다. <b>길이를 넘는 값을 잘라서 담지 않는다.</b>
     *
     * <p>2번 문제의 {@code ErrorRowRecorder} 는 격리 기록을 <em>잘라서</em> 저장했다. 격리가 컬럼
     * 길이 때문에 실패하면 격리 자체가 무너지기 때문이었다. 여기서는 반대다 — 잘린 멱등키는
     * <b>다른 회원의 키와 충돌</b>할 수 있고, 그러면 중복 방지 장치가 조용히 거짓말을 시작한다.
     * 알림은 잘라서 보내느니 보내지 않는 편이 낫다.
     */
    public NotificationMessage {
        if (memberId == null) {
            throw new IllegalArgumentException("식별자 없는 회원에게는 알림을 만들 수 없다");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("이벤트 종류가 필요하다: memberId=" + memberId);
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("멱등키가 필요하다: memberId=" + memberId);
        }
        if (idempotencyKey.length() > KEY_LIMIT) {
            throw new IllegalArgumentException(
                    "멱등키가 %d 자를 넘는다 (잘라 쓰면 다른 회원의 키와 충돌한다): %s"
                            .formatted(KEY_LIMIT, idempotencyKey));
        }
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("발송 본문이 필요하다: memberId=" + memberId);
        }
        if (payload.length() > PAYLOAD_LIMIT) {
            throw new IllegalArgumentException(
                    "발송 본문이 %d 자를 넘는다: memberId=%d, length=%d"
                            .formatted(PAYLOAD_LIMIT, memberId, payload.length()));
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("발송 시각이 필요하다: memberId=" + memberId);
        }
    }
}
