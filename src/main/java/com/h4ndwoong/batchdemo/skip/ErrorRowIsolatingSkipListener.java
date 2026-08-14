package com.h4ndwoong.batchdemo.skip;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import org.springframework.batch.core.SkipListener;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 스킵된 행을 {@code member_b_error} 로 격리한다. 2번 문제 after 의 <b>핵심 장치</b>다.
 *
 * <p>스킵은 그 자체로는 개선이 아니다. 오염 행을 건너뛰기만 하면 Step 은 {@code COMPLETED} 로
 * 끝나지만 <b>10만 건 중 500건이 조용히 사라진다.</b> before 의 "전부 실패" 보다 나쁠 수도 있는
 * 결과다. 실패는 눈에 띄지만 소리 없는 유실은 눈에 띄지 않기 때문이다. 스킵을
 * <em>격리 + 사후 추적 가능</em>으로 바꾸는 것이 이 리스너의 존재 이유다.
 *
 * <p><b>두 인터페이스를 함께 구현하는 이유</b><br>
 * 격리 기록에는 "어느 실행에서 빠진 행인가"({@code step_execution_id})가 필요한데,
 * {@link SkipListener} 의 콜백은 {@link StepExecution} 을 넘겨주지 않는다.
 * {@link StepExecutionListener#beforeStep} 에서 식별자를 받아 두는 것이 가장 단순한 방법이다.
 * 그래서 이 리스너는 Step 에 <b>두 번</b> 등록된다 ({@code AfterSkipJobConfig} 참고).
 *
 * <p>{@code onSkipInRead} 를 빈 구현으로 두지 않은 것에 주의한다. 이 실습에서 읽기 단계 스킵은
 * 발생하지 않지만, 빈 구현은 "나중에 발생하면 조용히 사라진다" 는 뜻이 된다.
 */
public class ErrorRowIsolatingSkipListener
        implements SkipListener<MemberBase, MemberBase>, StepExecutionListener {

    private final ErrorRowRecorder recorder;
    private final Clock clock;

    /**
     * 현재 Step 실행 식별자.
     *
     * <p>필드로 들고 있어도 되는 이유는 이 실습이 Job 을 하나씩 순차 실행하기 때문이다
     * ({@code --spring.batch.job.name} 으로 하나만 지정한다). 병렬 Step 이 생기면 이 가정은 깨진다.
     */
    private Long stepExecutionId;

    /**
     * 리스너를 만든다.
     *
     * @param recorder 격리 기록 저장소
     * @param clock    스킵 시각의 출처
     */
    public ErrorRowIsolatingSkipListener(ErrorRowRecorder recorder, Clock clock) {
        this.recorder = recorder;
        this.clock = clock;
    }

    /**
     * {@inheritDoc}
     *
     * <p>격리 기록에 남길 Step 실행 식별자를 확보한다.
     */
    @Override
    public void beforeStep(StepExecution stepExecution) {
        this.stepExecutionId = stepExecution.getId();
    }

    /**
     * {@inheritDoc}
     *
     * <p>읽기 단계에서 스킵된 행. 항목을 넘겨받을 수 없으므로 {@code member_id} 와 {@code raw_item}
     * 이 비는 것이 정상이다. 그래도 남긴다 — 몇 건이 읽히지 못했는지는 알아야 한다.
     */
    @Override
    public void onSkipInRead(Throwable t) {
        record(SkipPhase.READ, null, t);
    }

    /**
     * {@inheritDoc}
     *
     * <p>2번 문제의 오염 행 500건이 전부 이 경로로 들어온다.
     */
    @Override
    public void onSkipInProcess(MemberBase item, Throwable t) {
        record(SkipPhase.PROCESS, item, t);
    }

    /**
     * {@inheritDoc}
     *
     * <p>쓰기 단계에서 스킵된 행. 지금 구성에서는 쓰기 예외가 스킵 대상이 아니므로 호출되지 않는다.
     */
    @Override
    public void onSkipInWrite(MemberBase item, Throwable t) {
        record(SkipPhase.WRITE, item, t);
    }

    private void record(SkipPhase phase, MemberBase item, Throwable cause) {
        recorder.record(SkippedRow.of(phase, item, cause, stepExecutionId, LocalDateTime.now(clock)));
    }
}
