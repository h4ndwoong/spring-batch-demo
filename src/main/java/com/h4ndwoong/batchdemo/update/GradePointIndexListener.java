package com.h4ndwoong.batchdemo.update;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;

/**
 * Job 시작 전에 {@code (grade, point)} 인덱스를 설정대로 맞춘다. <b>양쪽 프로파일 공통</b>이다.
 *
 * <p>5번의 {@code IdempotencyIndexCreatingListener} / {@code ...DroppingListener} 는 프로파일이
 * 생성과 제거를 나눠 가졌지만, 여기서는 한 리스너가 프로퍼티를 보고 양쪽 다 한다. 이 인덱스는
 * <b>개선 기법이 아니라 측정 대상</b>이기 때문이다 — before 에도 after 에도 걸어 볼 수 있어야
 * "갱신하는 컬럼 위의 인덱스가 대량 UPDATE 를 얼마나 비싸게 만드는가" 를 각각 잴 수 있다.
 * ({@link MemberFGradePointIndex} 에 역할이 바뀐 경위를 적었다.)
 *
 * <p>Step 이 아니라 Job 리스너인 이유는 DDL 이 암묵적 커밋을 일으키기 때문이고, 등록 순서를
 * 측정 리스너 뒤에 두는 이유는 인덱스 생성 비용도 측정 범위에 들어가야 하기 때문이다.
 */
public class GradePointIndexListener implements JobExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(GradePointIndexListener.class);

    private final MemberFGradePointIndex index;
    private final boolean enabled;

    /**
     * 리스너를 만든다.
     *
     * @param index   DDL 실행기
     * @param enabled 인덱스를 둔 상태로 측정할지 여부. {@code false} 면 지운 상태로 시작한다
     */
    public GradePointIndexListener(MemberFGradePointIndex index, boolean enabled) {
        this.index = index;
        this.enabled = enabled;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code beforeJob} 에서 상태를 맞춘다. 켜는 쪽만이 아니라 <b>끄는 쪽도</b> 실행해야 직전
     * 측정이 남긴 인덱스가 이번 측정에 섞이지 않는다.
     *
     * @param jobExecution 실행 정보. 쓰지 않는다
     */
    @Override
    public void beforeJob(JobExecution jobExecution) {
        if (enabled) {
            index.create();
        }
        else {
            index.drop();
        }
        log.info("(grade, point) 인덱스: {} (--update.grade-point-index)", enabled ? "생성" : "제거");
    }
}
