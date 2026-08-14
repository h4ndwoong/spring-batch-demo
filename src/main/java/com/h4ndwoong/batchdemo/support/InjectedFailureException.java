package com.h4ndwoong.batchdemo.support;

/**
 * 장애 주입기가 심는 실패. <b>회복되지 않는 종류</b>다. 5번과 7번이 공유한다.
 *
 * <p>2번 문제의 {@code FaultKind} 처럼 여러 종류를 두지 않는다. 5·7번이 필요로 하는 실패는 딱 하나다 —
 * <b>정해진 지점에서 Step 을 끝장내는 것</b>. 재시도로 회복되거나 스킵으로 넘어가면 "실패한 뒤
 * 재실행" 이라는 상황 자체가 만들어지지 않는다. 그래서 두 문제의 Step 은 {@code faultTolerant} 가
 * 아니고, 이 예외는 어떤 스킵·재시도 목록에도 들어가지 않는다.
 *
 * <p>실무의 대응물은 OOM, 커넥션 풀 고갈, 배포로 인한 프로세스 종료처럼 <b>그 실행에서는 답이
 * 없는</b> 사건이다. 그런 사건이 배치를 중간에 끊었을 때 무엇이 남는가가 두 문제의 출발점이다.
 *
 * <p><b>같은 예외를 두 문제가 서로 다른 곳에서 던진다.</b> 그 위치의 차이가 5번과 7번의 차이다.
 * <ul>
 *   <li>5번 {@link com.h4ndwoong.batchdemo.restart.FailAfterCountItemWriter} — 위임 <b>전</b>에
 *       던진다. 실패한 청크는 아무 흔적도 남기지 않는다. 트랜잭션이 이미 해결한 문제를 피해 간다.</li>
 *   <li>7번 {@link com.h4ndwoong.batchdemo.outbox.FailAfterWriteItemWriter} — 위임 <b>후</b>에
 *       던진다. DB 쓰기는 롤백되지만 <b>이미 나간 알림은 되돌아오지 않는다.</b> 트랜잭션이 해결하지
 *       못하는 문제를 정조준한다.</li>
 * </ul>
 *
 * <p><b>왜 {@code support} 에 있는가</b><br>
 * 5번 전용으로 {@code restart} 패키지에 있었으나, 7번이 같은 성질의 실패를 필요로 하면서 둘째
 * 사용처가 생겼다. 예외의 의미("주입된, 회복 불가능한 실패")는 어느 문제에도 속하지 않으므로
 * 공용으로 옮긴다. {@code TableSeededValidator} 와 {@code GradePolicy} 가 같은 경로를 밟았다.
 */
public class InjectedFailureException extends RuntimeException {

    /**
     * 예외를 만든다.
     *
     * @param writtenCount 실패 직전까지 <b>커밋된</b> 행 수. 실패한 청크는 포함하지 않는다
     */
    public InjectedFailureException(long writtenCount) {
        super("주입된 장애: %d 건을 커밋한 뒤 중단한다".formatted(writtenCount));
    }
}
