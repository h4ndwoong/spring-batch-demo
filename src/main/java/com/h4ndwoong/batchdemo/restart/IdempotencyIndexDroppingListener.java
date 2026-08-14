package com.h4ndwoong.batchdemo.restart;

import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;

/**
 * Job 을 시작하기 전에 {@code uk_member_e_idem} 을 지운다. <b>before 전용</b>이다.
 *
 * <p>before 는 개선 기법이 하나도 없는 상태에서 시작해야 한다. 직전에 after 를 돌린 테이블에는
 * 제약이 남아 있으므로, 그 상태를 <b>사람의 기억이 아니라 Job 이</b> 되돌린다. 1번 문제의 after 가
 * 적재 전에 인덱스를 지웠던 것과 같은 판단이다.
 *
 * <p><b>제약이 남아 있어도 before 의 증상은 재현된다.</b> before 는 {@code idempotency_key} 를 쓰지
 * 않아 값이 전부 {@code NULL} 이고 {@code NULL} 은 UNIQUE 제약을 통과하기 때문이다. 그럼에도 지우는
 * 이유는 두 가지다 — 유니크 인덱스에는 <b>유지 비용</b>이 있어 남겨 두면 시간 비교가 오염되고,
 * "before 인데 개선 기법이 걸려 있는" 상태가 실습을 읽는 사람을 헷갈리게 한다.
 *
 * @see MemberEIdempotencyIndex
 */
public class IdempotencyIndexDroppingListener implements JobExecutionListener {

    private final MemberEIdempotencyIndex index;

    /**
     * 리스너를 만든다.
     *
     * @param index UNIQUE 제약 DDL 실행기
     */
    public IdempotencyIndexDroppingListener(MemberEIdempotencyIndex index) {
        this.index = index;
    }

    /**
     * {@inheritDoc}
     *
     * @param jobExecution 실행 정보. 쓰지 않는다
     */
    @Override
    public void beforeJob(JobExecution jobExecution) {
        index.drop();
    }
}
