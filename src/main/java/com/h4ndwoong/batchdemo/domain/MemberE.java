package com.h4ndwoong.batchdemo.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 5번 문제(재시작 멱등성) 전용 회원 엔티티. {@code member_e} 에 매핑된다.
 *
 * <p>{@code restartJob} 이 30만 건의 포인트를 차감하다 중간에 실패한 뒤 재실행된다.
 * before 는 {@code processed} 를 쓰지 않고 조건만으로 대상을 조회해 이미 차감된 행을 다시 차감한다.
 * after 는 {@code processed = 0} 인 행만 읽고 같은 트랜잭션에서
 * {@link MemberBase#markProcessed(String, LocalDateTime)} 로 마킹한다.
 *
 * <p>{@code idempotency_key} 의 UNIQUE 제약은 after 개선 기법이므로 스키마가 아니라
 * 프로파일 구성이 부여한다.
 *
 * @see MemberBase#deductPoint(long, LocalDateTime)
 */
@Entity
@Table(name = "member_e")
public class MemberE extends MemberBase {

    /** JPA 전용 기본 생성자. */
    protected MemberE() {
    }

    /**
     * 신규 회원을 생성한다.
     *
     * @see MemberBase#MemberBase(String, String, MemberGrade, long, MemberStatus, Long, LocalDateTime)
     */
    public MemberE(String email,
                   String name,
                   MemberGrade grade,
                   long point,
                   MemberStatus status,
                   Long referrerId,
                   LocalDateTime createdAt) {
        super(email, name, grade, point, status, referrerId, createdAt);
    }

    /**
     * DB 행으로부터 회원을 복원한다.
     *
     * @see MemberBase#MemberBase(Long, String, String, MemberGrade, long, MemberStatus, Long, boolean, String, LocalDateTime, LocalDateTime)
     */
    public MemberE(Long id,
                   String email,
                   String name,
                   MemberGrade grade,
                   long point,
                   MemberStatus status,
                   Long referrerId,
                   boolean processed,
                   String idempotencyKey,
                   LocalDateTime createdAt,
                   LocalDateTime updatedAt) {
        super(id, email, name, grade, point, status, referrerId, processed, idempotencyKey, createdAt, updatedAt);
    }
}
