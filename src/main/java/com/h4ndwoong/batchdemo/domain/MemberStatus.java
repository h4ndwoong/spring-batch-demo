package com.h4ndwoong.batchdemo.domain;

/**
 * 회원 상태.
 *
 * <p>7번 문제(외부 통보와 트랜잭션 경계)에서 상태 변경이 외부 알림 발송의 트리거가 된다.
 *
 * @see MemberBase#changeStatus(MemberStatus, java.time.LocalDateTime)
 */
public enum MemberStatus {

    /** 정상 활동 중. 스키마 기본값이다. */
    ACTIVE,

    /** 휴면. */
    DORMANT,

    /** 탈퇴. */
    WITHDRAWN
}
