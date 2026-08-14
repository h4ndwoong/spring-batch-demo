package com.h4ndwoong.batchdemo.domain;

import java.time.LocalDateTime;

/**
 * 식별자까지 지정해 회원을 만드는 팩토리. {@code MemberA::new} 처럼 각 엔티티의 복원 생성자를 참조한다.
 *
 * <p>7개 엔티티는 스키마가 같고 테이블만 다르므로, 어떤 테이블에 넣을지를 값이 아니라
 * <b>타입</b>으로 고르게 하기 위한 인터페이스다. 시딩처럼 대상 테이블이 런타임에 결정되는
 * 경우에 쓴다.
 *
 * <p><b>왜 식별자를 받는가</b><br>
 * {@code AUTO_INCREMENT} 에 맡기면 적재된 행의 {@code id} 가 몇 번인지 미리 알 수 없다.
 * MariaDB 드라이버는 배치 INSERT 를 bulk 프로토콜로 보내고 InnoDB 는 자동 증가 값을 블록 단위로
 * 미리 할당하므로, 실제로 번호에 구멍이 생긴다. 자기 참조({@code referrer_id})가 실재하는 행을
 * 가리켜야 하는 4번 문제에서는 이 불확실성을 감당할 수 없어 시딩이 {@code id} 를 직접 정한다.
 *
 * @see MemberBase#MemberBase(Long, String, String, MemberGrade, long, MemberStatus, Long, boolean, String, LocalDateTime, LocalDateTime)
 */
@FunctionalInterface
public interface MemberFactory {

    /**
     * 회원을 만든다. 어떤 값도 검증하지 않는다.
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
     * @return 생성된 회원
     */
    MemberBase create(Long id,
                      String email,
                      String name,
                      MemberGrade grade,
                      long point,
                      MemberStatus status,
                      Long referrerId,
                      boolean processed,
                      String idempotencyKey,
                      LocalDateTime createdAt,
                      LocalDateTime updatedAt);
}
