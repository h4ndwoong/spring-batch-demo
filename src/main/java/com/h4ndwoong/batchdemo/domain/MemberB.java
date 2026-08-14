package com.h4ndwoong.batchdemo.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 2번 문제(skip/retry, 오류 행 격리) 전용 회원 엔티티. {@code member_b} 에 매핑된다.
 *
 * <p>{@code skipJob} 이 10만 건 중 오염 행 500건을 만난다. 이 엔티티는 이메일 형식이나
 * 음수 포인트를 <b>검증하지 않으므로</b> 오염 행도 정상적으로 적재·조회된다. 검증은
 * {@code ItemProcessor} 에서 수행하고, 스킵된 행은 {@code SkipListener} 가
 * {@code member_b_error} 로 격리한다.
 *
 * @see MemberBase
 */
@Entity
@Table(name = "member_b")
public class MemberB extends MemberBase {

    /** JPA 전용 기본 생성자. */
    protected MemberB() {
    }

    /**
     * 신규 회원을 생성한다.
     *
     * @see MemberBase#MemberBase(String, String, MemberGrade, long, MemberStatus, Long, LocalDateTime)
     */
    public MemberB(String email,
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
    public MemberB(Long id,
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
