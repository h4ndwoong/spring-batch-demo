package com.h4ndwoong.batchdemo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

import java.time.LocalDateTime;

/**
 * {@code member_a} ~ {@code member_g} 가 공유하는 공통 회원 상태와 상태 전이를 정의한다.
 *
 * <p><b>왜 {@code @MappedSuperclass} 인가</b><br>
 * 7가지 문제는 "문제 1개 = 테이블 1개" 규칙에 따라 서로 데이터를 공유하지 않는다.
 * {@code @MappedSuperclass} 는 컬럼 구조만 물려주고 JPQL 다형 조회는 허용하지 않으므로,
 * 한 문제의 조회가 다른 문제의 테이블을 건드리는 사고를 타입 수준에서 막아준다.
 *
 * <p><b>이 클래스가 하지 않는 일</b>
 * <ul>
 *   <li><b>검증</b> — 잘못된 이메일 형식이나 음수 포인트를 막지 않는다. 2번 문제(skip/retry)는
 *       오염된 행을 <em>읽어들인 뒤</em> 검증 단계에서 걸러내는 실습이므로, 생성 시점에 막으면
 *       오염 데이터를 적재할 수도 읽을 수도 없게 된다. 검증은 {@code ItemProcessor} 책임이다.</li>
 *   <li><b>등급 산정 정책</b> — 4번 문제의 개선안이 정책 캐싱이므로 정책은 외부 컴포넌트로 둔다.</li>
 *   <li><b>멱등키 생성</b> — 5·7번 문제의 키 생성 전략은 Job 파라미터에 의존한다.
 *       여기서는 {@link #markProcessed(String, LocalDateTime)} 로 전달받아 저장만 한다.</li>
 * </ul>
 *
 * <p><b>시각을 파라미터로 받는 이유</b><br>
 * 상태 전이 메서드는 {@code LocalDateTime.now()} 를 직접 호출하지 않고 시각을 인자로 받는다.
 * 6번 문제에서 before(행별 UPDATE)와 after(집합 UPDATE {@code SET updated_at = :now})가
 * 동일한 {@code updated_at} 의미를 갖게 하여 비교 축을 맞추기 위함이고, 테스트에서 시각을
 * 고정할 수 있게 하기 위함이다.
 *
 * <p><b>{@link GenerationType#IDENTITY} 를 쓰는 이유</b><br>
 * IDENTITY 는 INSERT 직후 생성 키를 읽어야 하므로 Hibernate 의 JDBC batch insert 가 비활성화된다.
 * 이는 1번 문제 before 의 증상인 "행별 INSERT" 를 그대로 재현해주므로 의도적으로 선택했다.
 * after 는 {@code JdbcBatchItemWriter} 를 쓰므로 이 제약의 영향을 받지 않는다.
 */
@MappedSuperclass
public abstract class MemberBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "email", nullable = false, length = 100)
    private String email;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "grade", nullable = false, length = 20)
    private MemberGrade grade;

    @Column(name = "point", nullable = false)
    private long point;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MemberStatus status;

    /**
     * 추천인 식별자. 같은 테이블의 {@code id} 를 가리키는 자기 참조이지만 FK 제약은 걸지 않는다.
     * 4번 문제의 N+1 은 조회 횟수 문제이지 참조 무결성 문제가 아니고, FK 체크 비용이
     * 1번 문제의 적재 성능 측정치를 오염시키기 때문이다.
     */
    @Column(name = "referrer_id")
    private Long referrerId;

    /** 처리 완료 표시(process indicator). 5번 문제에서 재실행 시 재처리를 막는 데 쓴다. */
    @Column(name = "processed", nullable = false)
    private boolean processed;

    /** 멱등키. 5·7번 문제에서 중복 처리·중복 발송을 DB 레벨에서 차단하는 데 쓴다. */
    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** JPA 전용 기본 생성자. */
    protected MemberBase() {
    }

    /**
     * 신규 회원을 생성한다. {@code processed} 는 {@code false}, {@code idempotencyKey} 와
     * {@code updatedAt} 은 {@code null} 로 시작한다.
     *
     * <p>어떤 값도 검증하지 않는다. 오염 데이터 적재가 2번 문제의 전제이기 때문이다.
     *
     * @param email      이메일. 형식을 검증하지 않는다
     * @param name       이름
     * @param grade      등급
     * @param point      보유 포인트. 음수를 허용한다
     * @param status     상태
     * @param referrerId 추천인 식별자. 추천인이 없으면 {@code null}
     * @param createdAt  생성 시각. 대량 생성 시 분포를 통제할 수 있도록 호출자가 정한다
     */
    protected MemberBase(String email,
                         String name,
                         MemberGrade grade,
                         long point,
                         MemberStatus status,
                         Long referrerId,
                         LocalDateTime createdAt) {
        this.email = email;
        this.name = name;
        this.grade = grade;
        this.point = point;
        this.status = status;
        this.referrerId = referrerId;
        this.processed = false;
        this.idempotencyKey = null;
        this.createdAt = createdAt;
        this.updatedAt = null;
    }

    /**
     * DB 행으로부터 회원을 복원한다. JDBC 기반 리더(3·6번 문제)의 {@code RowMapper} 가 쓴다.
     *
     * <p>이 생성자로 만든 인스턴스는 영속 상태가 아니다. JPA 로 다시 저장하려면 병합이 필요하다.
     *
     * @param id             식별자
     * @param email          이메일
     * @param name           이름
     * @param grade          등급
     * @param point          보유 포인트
     * @param status         상태
     * @param referrerId     추천인 식별자. 없으면 {@code null}
     * @param processed      처리 완료 여부
     * @param idempotencyKey 멱등키. 없으면 {@code null}
     * @param createdAt      생성 시각
     * @param updatedAt      최종 수정 시각. 수정된 적이 없으면 {@code null}
     */
    protected MemberBase(Long id,
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
        this.id = id;
        this.email = email;
        this.name = name;
        this.grade = grade;
        this.point = point;
        this.status = status;
        this.referrerId = referrerId;
        this.processed = processed;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * 포인트를 차감한다.
     *
     * <p>잔액 부족을 검사하지 않는다. 5번 문제 before 의 증상이 "재실행 시 포인트 이중 소멸"인데,
     * 여기서 예외를 던지면 이중 소멸 대신 예외가 발생해 재현하려던 증상이 바뀐다.
     *
     * @param amount 차감할 포인트
     * @param at     수정 시각
     */
    public void deductPoint(long amount, LocalDateTime at) {
        this.point -= amount;
        this.updatedAt = at;
    }

    /**
     * 처리 완료로 표시하고 멱등키를 기록한다. 5번 문제 after 의 process indicator 마킹이다.
     *
     * <p>이미 처리된 행인지 검사하지 않는다. 중복 처리 차단은 읽기 조건({@code processed = 0})과
     * {@code idempotency_key} 의 UNIQUE 제약이 담당한다.
     *
     * @param idempotencyKey 멱등키. 생성 전략은 호출자가 정한다
     * @param at             수정 시각
     */
    public void markProcessed(String idempotencyKey, LocalDateTime at) {
        this.processed = true;
        this.idempotencyKey = idempotencyKey;
        this.updatedAt = at;
    }

    /**
     * 등급을 변경한다. 어떤 등급으로 바꿀지는 호출자(등급 정책)가 결정한다.
     *
     * @param grade 새 등급
     * @param at    수정 시각
     */
    public void changeGrade(MemberGrade grade, LocalDateTime at) {
        this.grade = grade;
        this.updatedAt = at;
    }

    /**
     * 상태를 변경한다. 7번 문제에서 외부 알림 발송의 트리거가 된다.
     *
     * @param status 새 상태
     * @param at     수정 시각
     */
    public void changeStatus(MemberStatus status, LocalDateTime at) {
        this.status = status;
        this.updatedAt = at;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getName() {
        return name;
    }

    public MemberGrade getGrade() {
        return grade;
    }

    public long getPoint() {
        return point;
    }

    public MemberStatus getStatus() {
        return status;
    }

    public Long getReferrerId() {
        return referrerId;
    }

    public boolean isProcessed() {
        return processed;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
