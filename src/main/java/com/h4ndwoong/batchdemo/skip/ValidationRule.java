package com.h4ndwoong.batchdemo.skip;

/**
 * {@link MemberValidator} 가 검사하는 규칙. 스킵된 행의 <b>원인</b>이 되는 값이다.
 *
 * <p>원인을 문자열이 아니라 enum 으로 둔 이유는 격리 테이블에 적힌 원인을 <em>집계</em>해야 하기
 * 때문이다. 2번 문제의 대사(reconciliation)는 "스킵 500건 = 이메일 오류 250 + 음수 포인트 250" 까지
 * 맞아떨어져야 성립하는데, 메시지 문자열로는 그 집계를 신뢰할 수 없다.
 */
public enum ValidationRule {

    /** 이메일 형식 위반. 시드 데이터의 {@code invalid-email-{id}} 가 여기 걸린다. */
    EMAIL_FORMAT,

    /** 음수 포인트. 시드 데이터가 심는 또 하나의 오염이다. */
    NEGATIVE_POINT,

    /**
     * 이름 누락.
     *
     * <p>시드 데이터는 이 오염을 만들지 않는다. 그래도 규칙으로 두는 이유는, 검증기가 "시드가 심은
     * 오염만 아는" 물건이 되면 실제 데이터에서 다른 결함을 만났을 때 조용히 통과시키기 때문이다.
     */
    BLANK_NAME
}
