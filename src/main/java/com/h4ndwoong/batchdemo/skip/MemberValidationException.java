package com.h4ndwoong.batchdemo.skip;

/**
 * 행 하나가 검증 규칙을 위반했다는 사실. <b>이 예외만 스킵 대상이다.</b>
 *
 * <p><b>왜 전용 예외인가</b><br>
 * after 구성은 {@code .skip(MemberValidationException.class)} 로 <em>이 타입만</em> 건너뛴다.
 * 스킵 대상을 {@code Exception} 이나 {@code RuntimeException} 으로 넓히면 오염 데이터와
 * 코드 버그({@code NullPointerException})와 인프라 장애가 한 덩어리가 되어, 배치가 절반을 버리고도
 * {@code COMPLETED} 로 끝난다. "무엇을 건너뛰어도 되는가" 는 타입으로 좁혀야 한다.
 *
 * <p>식별자와 규칙을 필드로 들고 있는 이유는 {@link ErrorRowIsolatingSkipListener} 가 메시지를
 * 파싱하지 않고 격리 테이블을 채울 수 있어야 하기 때문이다.
 */
public class MemberValidationException extends RuntimeException {

    private final Long memberId;
    private final ValidationRule rule;

    /**
     * 위반 사실을 만든다.
     *
     * @param memberId 위반한 행의 식별자. 아직 식별자가 없는 행이면 {@code null}
     * @param rule     위반한 규칙
     * @param detail   실제 값. 메시지에 그대로 실려 격리 테이블에 남는다
     */
    public MemberValidationException(Long memberId, ValidationRule rule, String detail) {
        super(rule + ": " + detail + " (id=" + memberId + ")");
        this.memberId = memberId;
        this.rule = rule;
    }

    /**
     * 위반한 행의 식별자.
     *
     * @return 식별자. 없으면 {@code null}
     */
    public Long getMemberId() {
        return memberId;
    }

    /**
     * 위반한 규칙. 격리 테이블의 원인 집계에 쓴다.
     *
     * @return 규칙
     */
    public ValidationRule getRule() {
        return rule;
    }
}
