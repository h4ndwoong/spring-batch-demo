package com.h4ndwoong.batchdemo.skip;

import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code skipJob} 시작 전에 {@code member_b} 에 읽을 데이터가 있는지 확인한다.
 *
 * <p>빈 테이블에서도 Step 은 {@code COMPLETED} 로 끝난다. 읽을 것이 없으니 스킵도 없고 격리도 없다.
 * 그 <b>0건짜리 성공</b>이 2번 문제에서는 가장 위험한 결과다. after 가 "오염 500건을 격리하고
 * 완료했다" 와 "아무것도 안 하고 완료했다" 가 똑같이 {@code COMPLETED} 로 보이기 때문이다.
 * 그래서 데이터가 없으면 Step 에 들어가지 않고 실패한다.
 *
 * <p>1번 문제의 {@code MemberAEmptyValidator} 와 판단이 정반대(비어 있어야 한다 vs 차 있어야 한다)인
 * 것에 주의한다. 1번은 빈 테이블에 적재하는 실습이고, 2번은 이미 적재된 오염 데이터를 읽는 실습이다.
 */
public class MemberBSeededValidator implements JobExecutionListener {

    private final JdbcTemplate jdbcTemplate;

    public MemberBSeededValidator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code COUNT(*)} 대신 {@code LIMIT 1} 로 존재 여부만 본다. 10만 건을 매 실행 세는 것은
     * 그 자체로 측정 노이즈다.
     *
     * @param jobExecution 실행 정보. 쓰지 않는다
     * @throws IllegalStateException {@code member_b} 가 비어 있을 때
     */
    @Override
    public void beforeJob(JobExecution jobExecution) {
        boolean empty = jdbcTemplate
                .queryForList("SELECT 1 FROM member_b LIMIT 1")
                .isEmpty();

        if (empty) {
            throw new IllegalStateException(
                    "member_b 가 비어 있다. 2번 문제는 오염 행이 섞인 10만 건을 읽는 실습이므로 "
                            + "읽을 데이터가 없으면 측정이 성립하지 않는다 (0건 처리도 COMPLETED 로 끝나서 "
                            + "성공과 구분되지 않는다). 먼저 시딩한다: "
                            + "./gradlew bootRun --args='--spring.batch.job.enabled=true "
                            + "--spring.batch.job.name=seedJob target=member_b'");
        }
    }
}
