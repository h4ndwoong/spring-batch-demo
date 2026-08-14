package com.h4ndwoong.batchdemo.support;

import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Job 시작 전에 대상 테이블에 읽을 데이터가 있는지 확인한다. 없으면 Step 에 들어가지 않고 실패한다.
 *
 * <p><b>왜 이 확인이 필요한가</b><br>
 * 빈 테이블에서도 Step 은 {@code COMPLETED} 로 끝난다. 읽을 것이 없으니 예외도 없고 스킵도 없다.
 * 그 <b>0건짜리 성공</b>이 이 실습에서는 가장 위험한 결과다. 문제마다 이유가 조금씩 다르다.
 * <ul>
 *   <li>2번 — "오염 500건을 격리하고 완료했다" 와 "아무것도 안 하고 완료했다" 가 둘 다
 *       {@code COMPLETED} 로 보인다.</li>
 *   <li>3번 — before 의 증상이 "느리다" 인데, 읽을 것이 없으면 before 도 즉시 끝나
 *       "개선했더니 빨라졌다" 와 "아무것도 안 읽었다" 가 구분되지 않는다.</li>
 *   <li>4번 — 조회 횟수가 0이면 before 의 "행당 2회" 와 after 의 "청크당 1회" 가 똑같이 0회다.</li>
 * </ul>
 *
 * <p><b>1번의 {@link com.h4ndwoong.batchdemo.insert.MemberAEmptyValidator} 와 판단이 정반대</b>인
 * 것에 주의한다 (비어 있어야 한다 vs 차 있어야 한다). 1번은 빈 테이블에 적재하는 실습이고,
 * 나머지는 이미 적재된 데이터를 읽는 실습이다. 그래서 그쪽과는 합치지 않는다.
 *
 * <p><b>추출 시점</b><br>
 * {@code MemberBSeededValidator}(2번) 와 {@code MemberCSeededValidator}(3번) 로 같은 코드가 두 벌
 * 있었고, 3번의 Javadoc 에 "4번 문제에서 셋째가 생기면 테이블과 메시지를 주입받는 공용 리스너로
 * 뽑는다" 고 적어 두었다. 4번의 {@code member_d} 가 그 셋째다. 달라지는 것은 <b>테이블 이름과
 * 실패 메시지</b> 둘뿐이므로 그 둘만 주입받는다.
 *
 * <p><b>테이블 이름이 SQL 에 문자열로 들어간다.</b> Job 파라미터 같은 외부 입력을 여기에 넘기면
 * 안 된다. 호출자는 반드시 코드에 적힌 상수를 넘긴다 (시딩 대상을 파라미터로 받는
 * {@link com.h4ndwoong.batchdemo.seed.SeedTarget} 이 열거형 조회를 강제하는 것과 같은 이유다).
 */
public class TableSeededValidator implements JobExecutionListener {

    private final JdbcTemplate jdbcTemplate;
    private final String table;
    private final String reason;

    /**
     * 리스너를 만든다.
     *
     * @param jdbcTemplate JDBC 템플릿
     * @param table        대상 테이블 이름. <b>코드에 적힌 상수만</b> 넘긴다. 외부 입력은 안 된다
     * @param reason       비어 있으면 왜 측정이 성립하지 않는지. 실패 메시지에 그대로 들어간다
     */
    public TableSeededValidator(JdbcTemplate jdbcTemplate, String table, String reason) {
        this.jdbcTemplate = jdbcTemplate;
        this.table = table;
        this.reason = reason;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code COUNT(*)} 대신 {@code LIMIT 1} 로 존재 여부만 본다. 200만 건을 매 실행 세는 것은
     * 그 자체로 측정 노이즈이며, 하필 3번 문제에서는 <b>측정하려는 바로 그 비용</b>(전체 스캔)이다.
     *
     * @param jobExecution 실행 정보. 쓰지 않는다
     * @throws IllegalStateException 대상 테이블이 비어 있을 때
     */
    @Override
    public void beforeJob(JobExecution jobExecution) {
        boolean empty = jdbcTemplate
                .queryForList("SELECT 1 FROM " + table + " LIMIT 1")
                .isEmpty();

        if (empty) {
            throw new IllegalStateException(table + " 가 비어 있다. " + reason + " 먼저 시딩한다: "
                    + "./gradlew bootRun --args='--spring.batch.job.enabled=true "
                    + "--spring.batch.job.name=seedJob target=" + table + "'");
        }
    }
}
