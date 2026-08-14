package com.h4ndwoong.batchdemo.seed;

import org.springframework.batch.core.JobParameter;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersIncrementer;

/**
 * {@code run.id} <b>만</b> 증가시키는 incrementer. 이전 실행의 다른 파라미터는 물려주지 않는다.
 *
 * <p><b>{@code RunIdIncrementer} 를 쓰면 안 되는 이유</b><br>
 * Spring Boot 의 {@code JobLauncherApplicationRunner} 는 Job 에 incrementer 가 있으면
 * {@code incrementer.getNext(이전_실행의_파라미터)} 의 결과를 기준으로 삼고 그 위에 CLI 인자를 덮는다.
 * 그런데 {@code RunIdIncrementer} 는 {@code new JobParametersBuilder(previous)} 로 이전 파라미터를
 * <em>전부 복사</em>한 뒤 {@code run.id} 만 올린다. 그래서 CLI 에서 생략한 파라미터가 이전 실행 값으로
 * 조용히 채워진다.
 *
 * <p>실제로 다음이 가능해진다.
 * <pre>
 * 1) target=member_g count=50   → 50건 적재
 * 2) target=member_c            → count 를 안 줬는데 이전 실행의 50 이 상속되어
 *                                 200만 건이 아니라 50건만 적재된다
 * </pre>
 * 시딩 규모가 조용히 틀어지면 그 뒤의 모든 측정치가 무의미해지고, 원인을 찾기도 어렵다.
 * 그래서 이전 파라미터를 버리고 {@code run.id} 만 만들어 넘긴다. 생략한 파라미터는
 * {@link SeedJobConfig} 가 정한 기본값으로 해석된다.
 *
 * @see <a href="https://docs.spring.io/spring-batch/reference/job.html">JobParametersIncrementer</a>
 */
public class SeedRunIdIncrementer implements JobParametersIncrementer {

    /** {@code RunIdIncrementer} 와 같은 키를 쓴다. 로그와 메타데이터에서 의미가 그대로 읽히도록. */
    static final String RUN_ID = "run.id";

    /**
     * {@inheritDoc}
     *
     * <p>반환값은 {@code run.id} 하나뿐이다. 이전 파라미터는 {@code run.id} 를 읽는 데만 쓰인다.
     *
     * @param parameters 이전 실행의 파라미터. {@code null} 이면 처음 실행으로 본다
     * @return {@code run.id} 만 담긴 파라미터
     * @throws IllegalArgumentException 이전 {@code run.id} 가 숫자가 아닐 때
     */
    @Override
    public JobParameters getNext(JobParameters parameters) {
        return new JobParametersBuilder()
                .addLong(RUN_ID, nextRunId(parameters))
                .toJobParameters();
    }

    private long nextRunId(JobParameters parameters) {
        if (parameters == null) {
            return 1L;
        }
        JobParameter<?> previous = parameters.getParameters().get(RUN_ID);
        if (previous == null) {
            return 1L;
        }
        try {
            return Long.parseLong(previous.getValue().toString()) + 1;
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("숫자가 아닌 " + RUN_ID + ": " + previous.getValue(), e);
        }
    }
}
