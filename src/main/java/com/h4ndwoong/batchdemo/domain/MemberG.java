package com.h4ndwoong.batchdemo.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 7번 문제(외부 통보와 트랜잭션 경계) 전용 회원 엔티티. {@code member_g} 에 매핑된다.
 *
 * <p>{@code outboxJob} 이 10만 건의 상태를 변경하고 외부 알림을 발송한다. before 는
 * {@code ItemWriter} 안에서 DB 쓰기와 발송을 함께 수행해 롤백 시 유령 알림이 발생하고,
 * after 는 Step 트랜잭션 안에서 {@code member_g_outbox} 에 발송 요청만 기록한 뒤
 * 커밋 이후 별도 릴레이가 발송한다.
 *
 * <p>이 엔티티는 알림 발송을 알지 못한다. 발송 여부·발송 시각은 Outbox 테이블의 관심사이고,
 * 여기에 발송 상태를 두면 before 의 "DB 롤백과 발송이 함께 되돌아가는지" 경계가 흐려진다.
 *
 * @see MemberBase#changeStatus(MemberStatus, LocalDateTime)
 */
@Entity
@Table(name = "member_g")
public class MemberG extends MemberBase {

    /** JPA 전용 기본 생성자. */
    protected MemberG() {
    }

    /**
     * 신규 회원을 생성한다.
     *
     * @see MemberBase#MemberBase(String, String, MemberGrade, long, MemberStatus, Long, LocalDateTime)
     */
    public MemberG(String email,
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
    public MemberG(Long id,
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
