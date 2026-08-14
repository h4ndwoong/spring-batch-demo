package com.h4ndwoong.batchdemo.domain;

/**
 * 회원 등급.
 *
 * <p>등급 <em>값</em>만 정의하고 등급 <em>산정 정책</em>(포인트 임계값 등)은 여기에 두지 않는다.
 * 4번 문제(Processor N+1)의 개선안이 "등급 정책을 Step 시작 시 1회 로딩해 캐시"하는 것이므로,
 * 정책을 enum 상수에 박아두면 캐시할 대상이 사라져 실습이 성립하지 않는다.
 * 정책은 별도 컴포넌트로 분리한다.
 *
 * @see MemberBase#changeGrade(MemberGrade, java.time.LocalDateTime)
 */
public enum MemberGrade {

    BRONZE,
    SILVER,
    GOLD,
    VIP
}
