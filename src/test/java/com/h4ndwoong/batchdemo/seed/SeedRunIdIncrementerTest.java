package com.h4ndwoong.batchdemo.seed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SeedRunIdIncrementer} 가 이전 실행의 파라미터를 물려주지 않는지 검증한다.
 *
 * <p>이 성질이 깨지면 CLI 에서 생략한 {@code count} 가 이전 실행 값으로 채워져 시딩 규모가 조용히
 * 틀어진다. Spring Boot 의 {@code JobLauncherApplicationRunner} 는
 * {@code incrementer.getNext(이전_파라미터)} 의 결과를 기준으로 CLI 인자를 덮으므로,
 * incrementer 가 무엇을 반환하는지가 곧 무엇이 상속되는지를 결정한다.
 */
class SeedRunIdIncrementerTest {

    private final SeedRunIdIncrementer incrementer = new SeedRunIdIncrementer();

    private static JobParameters previousRun() {
        return new JobParametersBuilder()
                .addString("target", "member_g")
                .addString("count", "50")
                .addString("chunkSize", "100")
                .addLong("run.id", 7L)
                .toJobParameters();
    }

    @Test
    @DisplayName("이전 실행의 파라미터를 물려주지 않고 run.id 만 반환한다")
    void 이전_파라미터를_버린다() {
        JobParameters next = incrementer.getNext(previousRun());

        assertThat(next.getParameters()).containsOnlyKeys("run.id");
        assertThat(next.getLong("run.id")).isEqualTo(8L);
    }

    @Test
    @DisplayName("Spring Batch 기본 RunIdIncrementer 와 달리 count 가 새어 들어오지 않는다")
    void 기본_구현과의_차이() {
        JobParameters leaked = new RunIdIncrementer().getNext(previousRun());
        JobParameters clean = incrementer.getNext(previousRun());

        assertThat(leaked.getString("count")).as("기본 구현은 이전 값을 복사한다").isEqualTo("50");
        assertThat(clean.getString("count")).as("여기서는 상속되지 않아야 한다").isNull();
    }

    @Test
    @DisplayName("첫 실행이면 run.id 는 1이다")
    void 첫_실행() {
        assertThat(incrementer.getNext(new JobParameters()).getLong("run.id")).isEqualTo(1L);
        assertThat(incrementer.getNext(null).getLong("run.id")).isEqualTo(1L);
    }

    @Test
    @DisplayName("run.id 가 없던 실행 뒤에도 1부터 시작한다")
    void run_id_없는_이전_실행() {
        JobParameters previous = new JobParametersBuilder().addString("target", "member_g").toJobParameters();

        assertThat(incrementer.getNext(previous).getLong("run.id")).isEqualTo(1L);
    }

    @Test
    @DisplayName("숫자가 아닌 run.id 는 거부한다")
    void 잘못된_run_id() {
        JobParameters previous = new JobParametersBuilder().addString("run.id", "abc").toJobParameters();

        assertThatThrownBy(() -> incrementer.getNext(previous))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("run.id");
    }
}
