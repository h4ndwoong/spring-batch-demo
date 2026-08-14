package com.h4ndwoong.batchdemo.insert;

import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code insertJob} 시작 전에 {@code member_a} 가 비어 있는지 확인한다.
 *
 * <p>1번 문제의 측정 대상은 "<b>비어 있는</b> 테이블에 100만 건 적재"다. 행이 남아 있는 상태로
 * 다시 적재하면 두 가지가 동시에 무너진다.
 * <ul>
 *   <li>측정 — 인덱스가 이미 커진 상태에서 시작하므로 before/after 어느 쪽의 수치도 이전 실행과
 *       비교할 수 없다.</li>
 *   <li>데이터 — before 는 {@code email} UK 가 걸린 상태로 적재하는데 생성기가 만드는 이메일은
 *       순번의 함수다. 재적재는 반드시 중복 키로 중간에 깨지고, 그 시점에 이미 수십만 행이
 *       커밋되어 어중간하게 오염된 테이블이 남는다.</li>
 * </ul>
 * 그래서 <b>비어 있지 않으면 아예 시작하지 않는다</b>. 다시 적재하려면 {@code TRUNCATE TABLE
 * member_a} 한다.
 *
 * <p>{@code seedJob} 의 {@link com.h4ndwoong.batchdemo.seed.TargetTableEmptyValidator} 와 판단은
 * 같지만 합치지 않았다. 그쪽은 대상 테이블을 Job 파라미터로 <em>매 실행 결정</em>하고 실패 메시지도
 * 시딩 절차를 안내한다. 여기는 대상이 {@code member_a} 로 고정이다. 공통화하려면 테이블 결정
 * 전략과 메시지를 둘 다 주입해야 하는데, 구현이 둘뿐인 지금은 그 추상화가 코드보다 크다.
 */
public class MemberAEmptyValidator implements JobExecutionListener {

    private final JdbcTemplate jdbcTemplate;

    public MemberAEmptyValidator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code COUNT(*)} 대신 {@code LIMIT 1} 로 존재 여부만 보므로 테이블 크기와 무관하게 즉시
     * 끝난다. 100만 건이 들어 있는 테이블에서 매번 전체를 세는 것은 그 자체로 측정 노이즈다.
     *
     * @param jobExecution 실행 정보. 쓰지 않는다
     * @throws IllegalStateException {@code member_a} 가 비어 있지 않을 때
     */
    @Override
    public void beforeJob(JobExecution jobExecution) {
        boolean hasRows = !jdbcTemplate
                .queryForList("SELECT 1 FROM member_a LIMIT 1")
                .isEmpty();

        if (hasRows) {
            throw new IllegalStateException(
                    "member_a 가 비어 있지 않다. 1번 문제는 빈 테이블에 100만 건을 적재하는 데 걸린 "
                            + "시간을 재는 실습이므로 이어서 적재하면 측정이 성립하지 않는다. "
                            + "TRUNCATE TABLE member_a 후 다시 실행한다.");
        }
    }
}
