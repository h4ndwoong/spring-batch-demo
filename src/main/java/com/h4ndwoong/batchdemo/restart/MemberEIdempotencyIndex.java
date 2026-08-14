package com.h4ndwoong.batchdemo.restart;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code member_e.idempotency_key} 의 UNIQUE 제약을 만들고 지운다. <b>언제</b> 할지는 모른다.
 *
 * <p>1번 문제의 {@code MemberAIndexCreator} 와 같은 분리다 — DDL 실행("무엇을")과 시점 결정("언제")을
 * 나누고, 시점은 프로파일별 리스너가 정한다. 이 제약이 {@code schema.sql} 이 아니라 여기 있는 이유도
 * 같다. 스키마에 넣으면 두 프로파일이 같은 상태에서 시작해 버려 비교 대상 자체가 사라진다.
 *
 * <p><b>이 UK 가 실제로 막는 것과 막지 못하는 것</b><br>
 * 5번의 개선은 흔히 "process indicator + 멱등키 UK" 로 요약되지만, 둘의 역할은 같지 않다.
 * <ul>
 *   <li>순차 재실행에서 이중 차감을 실제로 막는 것은 <b>읽기 조건 {@code processed = 0}</b> 과
 *       쓰기 조건 {@code WHERE id = ? AND processed = 0} 이다. 같은 행에 같은 키를 다시 쓰는 것은
 *       <em>자기 자신과의 충돌</em>이라 UNIQUE 제약에 걸리지 않는다.</li>
 *   <li>UK 가 막는 것은 <b>서로 다른 두 행이 같은 키를 갖는 상황</b>이다. 키 생성 규칙이 잘못되어
 *       (예: 회원 식별자를 빠뜨려) 여러 행이 한 키로 뭉개지면, 그 배치는 조용히 성공하고 처리 이력만
 *       거짓이 된다. UK 는 그때 실패시킨다 — <b>마지막 그물이지 정문이 아니다.</b></li>
 *   <li>before 에 이 UK 를 걸어 보면 아무것도 막지 못한다. before 는 키를 아예 쓰지 않아 전부
 *       {@code NULL} 이고, <b>{@code NULL} 은 UNIQUE 제약을 통과</b>하기 때문이다. "UK 를 걸었는데 왜
 *       안 막히죠" 의 정체가 이것이다.</li>
 * </ul>
 *
 * <p>DDL 은 {@code IF NOT EXISTS} / {@code IF EXISTS} 다. Job 은 여러 번 실행되고, 그때 이미 제약이
 * 있다고 해서 실패해서는 안 된다.
 */
public class MemberEIdempotencyIndex {

    /** {@code schema.sql} 하단 주석이 예고한 제약. */
    private static final String CREATE_STATEMENT =
            "ALTER TABLE member_e ADD UNIQUE KEY IF NOT EXISTS uk_member_e_idem (idempotency_key)";

    private static final String DROP_STATEMENT =
            "ALTER TABLE member_e DROP INDEX IF EXISTS uk_member_e_idem";

    private final JdbcTemplate jdbcTemplate;

    /**
     * DDL 실행기를 만든다.
     *
     * @param jdbcTemplate JDBC 템플릿
     */
    public MemberEIdempotencyIndex(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * UNIQUE 제약을 만든다. 이미 있으면 아무 일도 하지 않는다.
     *
     * <p>DDL 은 MariaDB 에서 암묵적 커밋을 일으키므로 배치 트랜잭션 밖에서 호출해야 한다.
     * Step 안이 아니라 Job 리스너에서 부르는 이유이며, "문제 1개 = Step 1개" 규칙을 지키는
     * 방법이기도 하다.
     */
    public void create() {
        jdbcTemplate.execute(CREATE_STATEMENT);
    }

    /**
     * UNIQUE 제약을 지운다. 없으면 아무 일도 하지 않는다.
     *
     * <p>before 가 "개선 기법이 하나도 없는 상태" 로 시작하려면 직전에 after 를 돌린 테이블에 남아
     * 있는 제약을 치워야 한다. 시작 상태를 사람의 기억이 아니라 Job 이 보장하게 하려는 것이다
     * (1번 문제의 판단 그대로). before 가 키를 쓰지 않아 제약이 무해하다는 것과는 별개로, 유니크
     * 인덱스는 <b>유지 비용</b>이 있으므로 남겨 두면 시간 비교가 오염된다.
     */
    public void drop() {
        jdbcTemplate.execute(DROP_STATEMENT);
    }
}
