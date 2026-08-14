package com.h4ndwoong.batchdemo.insert;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * {@code member_a} 의 보조 인덱스를 생성한다. <b>언제</b> 생성할지는 모른다.
 *
 * <p>1번 문제에서 before 와 after 의 차이는 인덱스의 <em>존재</em>가 아니라 <em>생성 시점</em>이다.
 * before 는 적재 전에 만들어 행마다 인덱스를 랜덤 갱신하게 하고, after 는 적재 후에 만들어 한 번에
 * 정렬 구축한다. 그래서 DDL 실행("무엇을")과 시점 결정("언제")을 나눈다. 시점은
 * {@link IndexPreCreationListener} 처럼 프로파일별 리스너가 정한다.
 *
 * <p>인덱스가 {@code schema.sql} 이 아니라 여기에 있는 이유도 같다. 스키마에 넣으면 두 프로파일이
 * 같은 상태에서 시작해 버려 비교 대상 자체가 사라진다.
 *
 * <p>모든 DDL 은 {@code IF NOT EXISTS} 다. Job 은 여러 번 실행되고, 그때 이미 인덱스가 있다고 해서
 * 실패해서는 안 된다. 실습을 원점으로 되돌리려면 {@code schema.sql} 하단의 리셋 스니펫으로
 * 인덱스를 직접 삭제한다.
 */
public class MemberAIndexCreator {

    /**
     * {@code schema.sql} 하단 주석이 예고한 세 개의 인덱스.
     *
     * <p>UK 하나와 보조 인덱스 둘이라는 구성이 중요하다. 유니크 인덱스는 삽입마다 중복 검사를
     * 동반하므로 일반 보조 인덱스보다 비싸고, before 의 적재 지연에서 가장 큰 몫을 차지한다.
     */
    private static final List<String> CREATE_STATEMENTS = List.of(
            "ALTER TABLE member_a ADD UNIQUE KEY IF NOT EXISTS uk_member_a_email (email)",
            "ALTER TABLE member_a ADD KEY IF NOT EXISTS idx_member_a_grade (grade)",
            "ALTER TABLE member_a ADD KEY IF NOT EXISTS idx_member_a_created_at (created_at)");

    private static final List<String> DROP_STATEMENTS = List.of(
            "ALTER TABLE member_a DROP INDEX IF EXISTS uk_member_a_email",
            "ALTER TABLE member_a DROP INDEX IF EXISTS idx_member_a_grade",
            "ALTER TABLE member_a DROP INDEX IF EXISTS idx_member_a_created_at");

    private final JdbcTemplate jdbcTemplate;

    public MemberAIndexCreator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 보조 인덱스를 생성한다. 이미 있으면 아무 일도 하지 않는다.
     *
     * <p>DDL 은 MariaDB 에서 암묵적 커밋을 일으키므로 배치 트랜잭션 밖에서 호출해야 한다.
     * Step 안이 아니라 Job 리스너에서 부르는 이유이며, "문제 1개 = Step 1개" 규칙을 지키는
     * 방법이기도 하다.
     */
    public void create() {
        CREATE_STATEMENTS.forEach(jdbcTemplate::execute);
    }

    /**
     * 보조 인덱스를 제거한다. 없으면 아무 일도 하지 않는다.
     *
     * <p>after 가 "PK 만 둔 상태로 적재" 하려면 직전에 before 를 돌린 테이블에 남아 있는 인덱스를
     * 치워야 한다. 시작 상태를 사람의 기억이 아니라 Job 이 보장하게 하려는 것이다.
     *
     * <p>대상 테이블이 비어 있을 때만 호출해야 한다. 100만 건이 들어 있는 테이블에서 인덱스를
     * 지우는 것은 그 자체로 긴 작업이고, 애초에 그 상태는 적재를 시작할 수 없는 상태다.
     */
    public void drop() {
        DROP_STATEMENTS.forEach(jdbcTemplate::execute);
    }
}
