package com.h4ndwoong.batchdemo.restart;

/**
 * 포인트 소멸 처리의 멱등키를 만든다. <b>after 만 쓴다.</b>
 *
 * <p>키는 "이 배치가 이 회원에게 한 일" 을 유일하게 가리켜야 한다. 여기서는 회원 식별자 하나로
 * 충분하다 — 이 실습이 <b>한 회차만</b> 다루기 때문이다. 실무라면 정산 기준일 같은 회차 식별자를
 * 앞에 붙여 {@code expire:2026-01-01:12345} 가 되고, 회차별 처리 이력은 {@code member_e} 가 아니라
 * 별도 테이블에 쌓인다. 이 실습이 그렇게 하지 않는 이유는 "문제 1개 = 테이블 1개" 규칙이다.
 *
 * <p><b>인터페이스가 아닌 이유</b><br>
 * 구현이 하나뿐이고, 두 번째 구현을 요구하는 요건이 아직 없다. 회차 개념이 들어오는 순간
 * {@code IdempotencyKeyGenerator} 로 승격하면 된다. 그전까지 인터페이스는 이름만 늘린다.
 *
 * <p><b>{@code idempotency_key} 컬럼은 {@code VARCHAR(64)} 다.</b> 접두사 16자 + {@code long} 최대
 * 19자로 최악의 경우에도 35자다.
 */
public final class ExpiryIdempotencyKey {

    /** 어떤 배치의 어떤 처리인지. 다른 Job 이 같은 컬럼을 쓰게 되면 키가 섞이지 않도록 접두사를 둔다. */
    private static final String PREFIX = "member_e:expire:";

    private ExpiryIdempotencyKey() {
    }

    /**
     * 회원 하나의 소멸 처리를 가리키는 키.
     *
     * @param memberId 회원 식별자
     * @return 멱등키. 같은 식별자에는 언제나 같은 값이다
     * @throws IllegalArgumentException {@code memberId} 가 {@code null} 일 때. 식별자가 없는 행은
     *                                  처리 흔적을 남길 자리도 없다는 뜻이므로 조용히 넘기지 않는다
     */
    public static String of(Long memberId) {
        if (memberId == null) {
            throw new IllegalArgumentException("식별자 없는 행에는 멱등키를 만들 수 없다");
        }
        return PREFIX + memberId;
    }
}
