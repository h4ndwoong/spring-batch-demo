package com.h4ndwoong.batchdemo.restart;

/**
 * {@link FailAfterCountItemWriter} 가 심는 장애. <b>회복되지 않는 종류</b>다.
 *
 * <p>2번 문제의 {@code FaultKind} 처럼 여러 종류를 두지 않는다. 5번이 필요로 하는 실패는 딱 하나다 —
 * <b>정해진 지점에서 Step 을 끝장내는 것</b>. 재시도로 회복되거나 스킵으로 넘어가면 "실패한 뒤
 * 재실행" 이라는 상황 자체가 만들어지지 않는다. 그래서 {@code restartStep} 은 {@code faultTolerant}
 * 가 아니고, 이 예외는 어떤 스킵·재시도 목록에도 들어가지 않는다.
 *
 * <p>실무의 대응물은 OOM, 커넥션 풀 고갈, 배포로 인한 프로세스 종료처럼 <b>그 실행에서는 답이
 * 없는</b> 사건이다. 그런 사건이 배치를 중간에 끊었을 때 무엇이 남는가가 5번의 출발점이다.
 */
public class InjectedFailureException extends RuntimeException {

    /**
     * 예외를 만든다.
     *
     * @param writtenCount 실패 직전까지 실제로 쓴 행 수. 커밋된 건수와 같다
     */
    public InjectedFailureException(long writtenCount) {
        super("주입된 장애: %d 건을 커밋한 뒤 중단한다".formatted(writtenCount));
    }
}
