package com.h4ndwoong.batchdemo.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 4번 문제(Processor N+1 조회) 전용 회원 엔티티. {@code member_d} 에 매핑된다.
 *
 * <p>{@code lookupJob} 이 50만 건을 가공하며 각 행의 {@code referrerId} 로 추천인을 참조한다.
 * 추천인 참조를 {@code @ManyToOne} 연관으로 매핑하지 않고 <b>식별자 값</b>으로 둔 이유는,
 * 연관 매핑을 하면 fetch 전략이 조회 횟수를 좌우해 before(행당 2회 SELECT)와
 * after(청크당 1회 {@code IN} 조회)의 차이가 Hibernate 내부 동작에 묻히기 때문이다.
 * 조회 방식을 명시적으로 통제해야 쿼리 수를 측정할 수 있다.
 *
 * @see MemberBase#getReferrerId()
 */
@Entity
@Table(name = "member_d")
public class MemberD extends MemberBase {

    /** JPA 전용 기본 생성자. */
    protected MemberD() {
    }

    /**
     * 신규 회원을 생성한다.
     *
     * @see MemberBase#MemberBase(String, String, MemberGrade, long, MemberStatus, Long, LocalDateTime)
     */
    public MemberD(String email,
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
    public MemberD(Long id,
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
