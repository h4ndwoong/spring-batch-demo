package com.h4ndwoong.batchdemo.insert;

import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;

/**
 * 적재를 시작하기 <b>전에</b> {@code member_a} 의 보조 인덱스를 만든다. 1번 문제 before 의 증상을
 * 만들어 내는 장치다.
 *
 * <p>인덱스가 이미 있는 테이블에 100만 건을 넣으면 행마다 UK 중복 검사와 인덱스 B-tree 갱신이
 * 따라붙는다. {@code email} 은 순번 기반이라 정렬 순서와 대체로 맞지만 {@code grade} 와
 * {@code created_at} 은 값이 흩어져 있어 <em>랜덤 위치</em>에 삽입되고, 이것이 페이지 분할과
 * 디스크 write IO 로 이어진다. after 는 같은 인덱스를 적재가 끝난 뒤에 만들어 이 비용을 없앤다.
 *
 * <p><b>왜 Step 이 아니라 Job 리스너인가</b><br>
 * "문제 1개 = Job 1개, {@code insertJob → insertStep}" 이 실습 규칙이다. 인덱스 생성을 별도 Step 으로
 * 만들면 Step 이 둘이 되어 before/after 의 Step 통계를 같은 축에서 비교할 수 없다. 또 DDL 은
 * MariaDB 에서 암묵적 커밋을 일으키므로 청크 트랜잭션 안에서 실행해서도 안 된다.
 *
 * @see MemberAIndexCreator
 */
public class IndexPreCreationListener implements JobExecutionListener {

    private final MemberAIndexCreator indexCreator;

    public IndexPreCreationListener(MemberAIndexCreator indexCreator) {
        this.indexCreator = indexCreator;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@link MemberAEmptyValidator} 보다 <b>뒤에</b> 등록해야 한다. 적재를 시작할 수 없는 상황이면
     * 인덱스도 건드리지 않는 편이 실습 상태를 덜 흔든다.
     *
     * @param jobExecution 실행 정보. 쓰지 않는다. 대상 테이블이 {@code member_a} 로 고정이기 때문이다
     */
    @Override
    public void beforeJob(JobExecution jobExecution) {
        indexCreator.create();
    }
}
