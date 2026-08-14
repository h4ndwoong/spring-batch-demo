package com.h4ndwoong.batchdemo.restart;

import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;

/**
 * Job 을 시작하기 전에 {@code uk_member_e_idem} 을 만든다. <b>after 전용</b>이다.
 *
 * <p>1번 문제의 {@code IndexPreCreationListener} 와 같은 자리이지만 목적이 반대다. 1번의 인덱스
 * 선생성은 <em>증상을 만들기 위한</em> 장치였고, 여기의 제약 생성은 <em>개선 기법</em> 자체다.
 *
 * <p><b>재시작될 때도 다시 불린다.</b> 재시작은 새 {@code JobExecution} 이므로 Job 리스너가 또
 * 호출된다. DDL 이 {@code IF NOT EXISTS} 라 무해하고, 오히려 그래야 한다 — 첫 실행이 이 리스너를
 * 지나기 전에 죽었더라도 재시작한 실행은 제약이 있는 상태에서 시작해야 한다.
 *
 * @see MemberEIdempotencyIndex UK 가 실제로 막는 것과 막지 못하는 것
 */
public class IdempotencyIndexCreatingListener implements JobExecutionListener {

    private final MemberEIdempotencyIndex index;

    /**
     * 리스너를 만든다.
     *
     * @param index UNIQUE 제약 DDL 실행기
     */
    public IdempotencyIndexCreatingListener(MemberEIdempotencyIndex index) {
        this.index = index;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code TableSeededValidator} 보다 <b>뒤에</b> 등록해야 한다. 처리를 시작할 수 없는 상황이면
     * 스키마도 건드리지 않는 편이 실습 상태를 덜 흔든다 (1번의 판단 그대로).
     *
     * @param jobExecution 실행 정보. 쓰지 않는다. 대상 테이블이 {@code member_e} 로 고정이기 때문이다
     */
    @Override
    public void beforeJob(JobExecution jobExecution) {
        index.create();
    }
}
