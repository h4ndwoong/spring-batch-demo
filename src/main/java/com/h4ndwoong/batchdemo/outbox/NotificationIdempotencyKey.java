package com.h4ndwoong.batchdemo.outbox;

/**
 * 상태 변경 알림의 멱등키를 만든다. <b>before 와 after 가 같은 것을 쓴다.</b>
 *
 * <p>키는 "이 배치가 이 회원에게 보낸 알림" 을 유일하게 가리켜야 한다. 5번의
 * {@code ExpiryIdempotencyKey} 와 같은 형태이고 같은 한계를 갖는다 — 회차 개념이 없으므로 회원
 * 식별자 하나로 충분하다. 실무라면 {@code status-changed:2026-01-01:12345} 처럼 회차가 앞에 붙는다.
 *
 * <p><b>5번의 키와 다른 점은 그 키가 어디로 가는가다.</b> 5번의 키는 {@code member_e} 의 컬럼에
 * 남아 <em>DB 안에서</em> 처리 이력이 되었다. 7번의 키는 메시지에 실려 <em>DB 밖으로</em> 나간다.
 * 나간 키는 되돌릴 수 없고, 그것을 보고 거절해 줄 쪽이 없으면 아무 일도 하지 않는다.
 *
 * <p><b>인터페이스가 아닌 이유</b><br>
 * 5번과 같다. 구현이 하나뿐이고 둘째 구현을 요구하는 요건이 없다. 회차 개념이 들어오는 순간
 * {@code IdempotencyKeyGenerator} 로 승격하면 되며, 그때는 5번의 키와 함께 승격한다.
 *
 * <p>접두사 25자 + {@code long} 최대 19자로 최악의 경우에도 44자다
 * ({@value NotificationMessage#KEY_LIMIT} 자 제한 안에 들어온다).
 */
public final class NotificationIdempotencyKey {

    /** 어떤 테이블의 어떤 사건인지. 다른 Job 의 키와 섞이지 않도록 접두사를 둔다. */
    private static final String PREFIX = "member_g:status-changed:";

    private NotificationIdempotencyKey() {
    }

    /**
     * 회원 하나의 상태 변경 알림을 가리키는 키.
     *
     * @param memberId 회원 식별자
     * @return 멱등키. 같은 식별자에는 언제나 같은 값이다
     * @throws IllegalArgumentException {@code memberId} 가 {@code null} 일 때. 식별자가 없으면
     *                                  "어느 알림인가" 를 말할 수 없고, 그러면 중복인지도 알 수 없다
     */
    public static String of(Long memberId) {
        if (memberId == null) {
            throw new IllegalArgumentException("식별자 없는 회원에게는 멱등키를 만들 수 없다");
        }
        return PREFIX + memberId;
    }
}
