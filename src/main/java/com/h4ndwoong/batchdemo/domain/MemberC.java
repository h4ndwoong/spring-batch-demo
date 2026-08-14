package com.h4ndwoong.batchdemo.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 3번 문제(offset 페이징 함정) 전용 회원 엔티티. {@code member_c} 에 매핑된다.
 *
 * <p>{@code pagingJob} 이 200만 건을 순회한다. before 는 {@code LIMIT ... OFFSET n},
 * after 는 {@code WHERE id > :lastId} 키셋 페이징이다. 두 경로 모두 JDBC 리더를 쓰므로
 * {@code RowMapper} 가 복원 생성자로 인스턴스를 만든다.
 *
 * @see MemberBase
 */
@Entity
@Table(name = "member_c")
public class MemberC extends MemberBase {

    /** JPA 전용 기본 생성자. */
    protected MemberC() {
    }

    /**
     * 신규 회원을 생성한다.
     *
     * @see MemberBase#MemberBase(String, String, MemberGrade, long, MemberStatus, Long, LocalDateTime)
     */
    public MemberC(String email,
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
    public MemberC(Long id,
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
