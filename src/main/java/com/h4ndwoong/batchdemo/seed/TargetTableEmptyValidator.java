package com.h4ndwoong.batchdemo.seed;

import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code seedJob} 시작 전에 대상 테이블이 비어 있는지 확인한다.
 *
 * <p>{@code seedJob} 은 재실행이 쉽도록 {@code RunIdIncrementer} 를 쓰므로 실수로 두 번 실행하기 쉽다.
 * 시드 데이터는 {@code id} 를 순번으로 직접 지정하기 때문에 두 번째 실행은 PK 충돌로 중간에
 * 깨질 텐데, 그때는 이미 얼마간의 행이 커밋된 상태다. 어중간하게 오염된 테이블로 측정을 이어가는
 * 것이 최악이므로 <b>비어 있지 않으면 아예 시작하지 않는다</b>.
 *
 * <p>다시 시딩하려면 대상 테이블을 {@code TRUNCATE} 한다. {@code TRUNCATE} 는
 * {@code AUTO_INCREMENT} 도 함께 되돌리므로 같은 시드로 같은 데이터가 재현된다.
 */
public class TargetTableEmptyValidator implements JobExecutionListener {

    private final JdbcTemplate jdbcTemplate;

    public TargetTableEmptyValidator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * {@inheritDoc}
     *
     * <p>대상 테이블에 행이 하나라도 있으면 예외를 던져 Job 을 실패시킨다. {@code COUNT(*)} 대신
     * {@code LIMIT 1} 로 존재 여부만 보므로 테이블 크기와 무관하게 즉시 끝난다.
     *
     * @param jobExecution 실행 정보. {@code target} 파라미터를 읽는다
     * @throws IllegalStateException 대상 테이블이 비어 있지 않을 때
     */
    @Override
    public void beforeJob(JobExecution jobExecution) {
        SeedTarget target = SeedTarget.from(jobExecution.getJobParameters().getString("target"));
        boolean hasRows = !jdbcTemplate
                .queryForList("SELECT 1 FROM " + target.tableName() + " LIMIT 1")
                .isEmpty();

        if (hasRows) {
            throw new IllegalStateException(target.tableName()
                    + " 이 비어 있지 않다. 중복 시딩은 이후 측정치를 무효하게 만든다. "
                    + "TRUNCATE TABLE " + target.tableName() + " 후 다시 실행한다.");
        }
    }
}
