package com.h4ndwoong.batchdemo.insert;

import com.h4ndwoong.batchdemo.domain.MemberA;
import com.h4ndwoong.batchdemo.domain.MemberFactory;
import com.h4ndwoong.batchdemo.seed.MemberSeedGenerator;
import com.h4ndwoong.batchdemo.seed.MemberSeedItemReader;

/**
 * {@code insertJob} 이 적재할 {@link MemberA} 를 만들어 내는 리더를 조립한다.
 *
 * <p>1번 문제는 "비어 있는 {@code member_a} 에 100만 건 적재" 자체가 측정 대상이므로 읽을 원본
 * 데이터가 없다. {@code seedJob} 이 쓰는 {@link MemberSeedGenerator} 를 그대로 재사용해 같은
 * {@code seed} 면 언제나 같은 100만 건이 나오게 한다. before 와 after 가 문자 그대로 동일한 입력을
 * 받아야 비교가 성립하기 때문이다.
 *
 * <p><b>{@code member_a} 의 데이터 요건</b><br>
 * 오염 행 없음, 자기 참조 없음. 1번 문제는 쓰기 경로의 비용만 보는 실습이라 검증 실패({@code 2번})나
 * 추가 조회({@code 4번}) 요인이 섞이면 측정치가 흐려진다. {@code member_a} 가
 * {@link com.h4ndwoong.batchdemo.seed.SeedTarget} 에 없는 이유이기도 하다.
 *
 * <p>이 클래스는 프로파일을 모른다. before 와 after 가 <b>같은 리더</b>를 쓰고 쓰기 경로만 달라야
 * 한다는 것이 실습 규칙이므로, 리더 조립은 프로파일별 구성이 아니라 여기에 둔다.
 */
public final class MemberAItemReaderFactory {

    /** 1번 문제의 기본 적재 규모. Job 파라미터 {@code count} 로 덮을 수 있다. */
    public static final long DEFAULT_COUNT = 1_000_000L;

    /**
     * 식별자를 <b>버리고</b> 새 {@link MemberA} 를 만드는 팩토리.
     *
     * <p>{@link MemberSeedGenerator} 는 순번을 그대로 {@code id} 로 넘기지만, 여기서는 그 값을 쓰지
     * 않고 {@code AUTO_INCREMENT} 에 채번을 맡긴다. 두 가지 이유가 있다.
     * <ul>
     *   <li>{@code id} 가 채워진 엔티티는 JPA 가 detached 로 판정한다. {@code JpaItemWriter} 의
     *       {@code persist} 경로가 성립하려면 아이템이 transient 여야 한다. before 의 증상인
     *       "{@code IDENTITY} 때문에 JDBC batch 가 꺼져 행마다 INSERT" 를 재현하려면 채번을
     *       DB 에 맡기는 것이 전제다.</li>
     *   <li>{@code member_a} 는 자기 참조가 없다. {@code id} 를 미리 정해야 했던 이유(4번 문제의
     *       {@code referrer_id} 가 실재하는 행을 가리켜야 한다)가 여기서는 없다.</li>
     * </ul>
     * 버려지는 나머지 값({@code processed}, {@code idempotencyKey}, {@code updatedAt})은 생성기가
     * 항상 {@code false}/{@code null} 로 주므로 손실이 없다.
     */
    private static final MemberFactory NEW_MEMBER_A =
            (id, email, name, grade, point, status, referrerId, processed, idempotencyKey, createdAt, updatedAt) ->
                    new MemberA(email, name, grade, point, status, referrerId, createdAt);

    private MemberAItemReaderFactory() {
    }

    /**
     * 리더를 만든다.
     *
     * @param count 생성할 건수
     * @param seed  난수 시드. 같은 시드는 같은 데이터를 만든다
     * @return {@code member_a} 에 적재할 회원을 만들어 내는 리더
     */
    public static MemberSeedItemReader of(long count, long seed) {
        MemberSeedGenerator generator = new MemberSeedGenerator(
                NEW_MEMBER_A, 0, false, seed, MemberSeedGenerator.BASE_TIME);
        return new MemberSeedItemReader(generator, count);
    }
}
