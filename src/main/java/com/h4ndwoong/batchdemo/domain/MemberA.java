package com.h4ndwoong.batchdemo.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 1번 문제(대량 INSERT 성능) 전용 회원 엔티티. {@code member_a} 에 매핑된다.
 *
 * <p>{@code insertJob} 이 100만 건을 적재한다. before 는 보조 인덱스가 미리 생성된 상태에서
 * {@code JpaItemWriter} 로 행별 INSERT 하고, after 는 PK 만 둔 채 {@code JdbcBatchItemWriter} 로
 * 적재한 뒤 인덱스를 생성한다. 보조 인덱스는 스키마가 아니라 프로파일이 제어한다.
 *
 * @see MemberBase
 */
@Entity
@Table(name = "member_a")
public class MemberA extends MemberBase {

    /** JPA 전용 기본 생성자. */
    protected MemberA() {
    }

    /**
     * 신규 회원을 생성한다.
     *
     * @see MemberBase#MemberBase(String, String, MemberGrade, long, MemberStatus, Long, LocalDateTime)
     */
    public MemberA(String email,
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
    public MemberA(Long id,
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
