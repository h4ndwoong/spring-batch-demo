package com.h4ndwoong.batchdemo.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 6번 문제(대량 UPDATE 쓰기 경로) 전용 회원 엔티티. {@code member_f} 에 매핑된다.
 *
 * <p>{@code updateJob} 이 100만 건의 등급을 재계산한다. before 는 읽은 행마다
 * {@link MemberBase#changeGrade(MemberGrade, LocalDateTime)} 후 개별 UPDATE 를 실행하고,
 * after 는 조건 기반 집합 UPDATE 로 엔티티를 거치지 않는다.
 *
 * <p>양쪽이 같은 {@code updated_at} 을 쓰도록 상태 전이 메서드가 시각을 인자로 받는다.
 * after 의 {@code SET updated_at = :now} 와 의미를 맞춰야 비교가 성립한다.
 *
 * @see MemberBase
 */
@Entity
@Table(name = "member_f")
public class MemberF extends MemberBase {

    /** JPA 전용 기본 생성자. */
    protected MemberF() {
    }

    /**
     * 신규 회원을 생성한다.
     *
     * @see MemberBase#MemberBase(String, String, MemberGrade, long, MemberStatus, Long, LocalDateTime)
     */
    public MemberF(String email,
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
    public MemberF(Long id,
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
