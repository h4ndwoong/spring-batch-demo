package com.h4ndwoong.batchdemo.insert;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;

/**
 * PK 만 둔 상태로 적재하게 하고, 적재가 <b>끝난 뒤에</b> 보조 인덱스를 만든다. 1번 문제 after 의
 * 개선 기법이다.
 *
 * <p>같은 인덱스를 만들지만 비용이 다르다. 적재 중에 만들면 행마다 B-tree 를 랜덤 위치에 갱신하며
 * 페이지 분할을 일으키고, 적재 후에 만들면 정렬된 데이터를 한 번에 훑어 순차적으로 구축한다.
 * before 가 write IO 를 1.1 GB 넘게 쓴 몫의 상당 부분이 앞쪽 방식의 대가다.
 *
 * <p><b>{@code beforeJob} 에서 인덱스를 지우는 이유</b><br>
 * 직전에 before 프로파일을 돌렸다면 {@code member_a} 에는 이미 인덱스가 남아 있다. 그대로 적재하면
 * after 가 before 와 같은 조건이 되어 실습이 성립하지 않는다. 시작 상태를 사람의 기억에 맡기지 않고
 * Job 이 보장한다. {@link MemberAEmptyValidator} 가 먼저 통과해야 하므로 이 시점의 테이블은
 * 항상 비어 있고, 따라서 인덱스 제거는 사실상 무비용이다.
 *
 * <p><b>실패하면 만들지 않는다</b><br>
 * 적재가 깨진 뒤 인덱스만 만들어 두면 다음 실행의 시작 상태가 오염된다. 어차피 {@code TRUNCATE}
 * 후 다시 적재해야 하므로 인덱스는 그때 만들면 된다.
 *
 * @see IndexPreCreationListener before 는 같은 인덱스를 적재 전에 만든다
 */
public class IndexPostCreationListener implements JobExecutionListener {

    private final MemberAIndexCreator indexCreator;

    public IndexPostCreationListener(MemberAIndexCreator indexCreator) {
        this.indexCreator = indexCreator;
    }

    /**
     * {@inheritDoc}
     *
     * <p>보조 인덱스를 제거해 PK 만 남긴다.
     *
     * @param jobExecution 실행 정보. 쓰지 않는다. 대상 테이블이 {@code member_a} 로 고정이기 때문이다
     */
    @Override
    public void beforeJob(JobExecution jobExecution) {
        indexCreator.drop();
    }

    /**
     * {@inheritDoc}
     *
     * <p>적재에 성공했을 때만 보조 인덱스를 만든다. 이 시간도 after 의 비용이므로
     * {@link com.h4ndwoong.batchdemo.support.DatabaseWorkloadListener} 의 측정 범위 안에서 일어나야
     * 한다. 그래서 Job 리스너 등록 순서가 중요하다.
     *
     * @param jobExecution 실행 정보. 종료 상태를 읽는다
     */
    @Override
    public void afterJob(JobExecution jobExecution) {
        if (jobExecution.getStatus() == BatchStatus.COMPLETED) {
            indexCreator.create();
        }
    }
}
