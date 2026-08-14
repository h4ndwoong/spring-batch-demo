package com.h4ndwoong.batchdemo.lookup;

/**
 * {@code lookupStep} 의 청크 크기. 커밋 단위이자 <b>after 의 {@code IN} 묶음 크기</b>다.
 *
 * <p><b>왜 Job 파라미터가 아니라 Spring 프로퍼티인가</b><br>
 * 이 실습의 다른 조절값({@code pages}, {@code count}, {@code faultAtId})은 모두 Job 파라미터인데
 * 청크 크기만 프로퍼티다. 청크 크기가 <b>Step 을 조립하는 시점에 확정되어야 하는 값</b>이기
 * 때문이다. Job 파라미터는 Job 이 <em>실행될 때</em> 바인딩되므로, 청크 크기를 거기서 읽으려면
 * Step 빈 자체를 잡 스코프로 만들어야 한다. 그러면 Job 을 조립하는 시점(스코프가 아직 없는 시점)에
 * 프록시가 깨진다. 측정 실행이 JVM 하나 = 실행 하나이므로 프로퍼티로 충분하다.
 * <pre>{@code
 * ./gradlew bootRun --args='... --spring.profiles.active=after --lookup.chunk-size=5000 ...'
 * }</pre>
 *
 * <p><b>상한이 있는 이유가 곧 README 가 말한 트레이드오프다.</b> 청크를 키우면 왕복은 줄지만
 * <ul>
 *   <li>{@link ChunkedReferrerLookup} 의 캐시가 그만큼의 추천인을 메모리에 들고 있어야 하고,</li>
 *   <li>{@code IN (?, ?, ...)} 의 바인딩 파라미터가 그만큼 늘어난다 (JDBC 프로토콜의 상한은
 *       65,535개다),</li>
 *   <li>청크 하나가 실패했을 때 되돌아가는 양도 그만큼 커진다.</li>
 * </ul>
 * 그래서 "크면 클수록 좋다" 가 아니며, 그 사실을 측정으로 확인하는 것이 4번 문제의 둘째 축이다.
 *
 * @param value 청크 크기
 */
public record LookupChunkSize(int value) {

    /** 기본 청크 크기. 3번 문제의 페이지 크기와 같은 값이라 두 문제의 수치를 나란히 읽기 좋다. */
    public static final int DEFAULT = 1_000;

    /** 청크 크기 상한. 위의 이유들 때문에 무한정 키울 수 없다. */
    public static final int MAX = 10_000;

    /**
     * 청크 크기를 만든다.
     *
     * @throws IllegalArgumentException 1 미만이거나 {@link #MAX} 를 넘을 때
     */
    public LookupChunkSize {
        if (value < 1 || value > MAX) {
            throw new IllegalArgumentException(
                    "lookup.chunk-size 는 1 이상 %d 이하여야 한다: %d".formatted(MAX, value));
        }
    }
}
