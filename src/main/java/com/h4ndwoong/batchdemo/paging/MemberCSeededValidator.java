package com.h4ndwoong.batchdemo.paging;

import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code pagingJob} 시작 전에 {@code member_c} 에 읽을 데이터가 있는지 확인한다.
 *
 * <p>빈 테이블에서도 Step 은 {@code COMPLETED} 로 끝난다. 3번 문제에서 그 <b>0건짜리 성공</b>은
 * 특히 위험하다 — offset 페이징의 증상은 "느리다" 인데, 읽을 것이 없으면 before 조차 즉시 끝나
 * <b>"개선했더니 빨라졌다" 와 "아무것도 안 읽었다" 가 똑같아 보인다.</b> 그래서 데이터가 없으면
 * Step 에 들어가지 않고 실패한다.
 *
 * <p>2번의 {@code MemberBSeededValidator} 와 판단은 같지만 합치지 않았다. 대상 테이블이 다르고
 * 실패 메시지가 안내해야 하는 시딩 명령과 규모가 다르며, 무엇보다 {@code MemberAEmptyValidator} 가
 * 남긴 판단("구현이 둘뿐인 지금은 그 추상화가 코드보다 크다")이 여기서도 유효하다. "차 있어야 한다"
 * 계열은 아직 둘({@code member_b}, {@code member_c})이다. <b>4번 문제에서 셋째가 생기면</b> 테이블과
 * 메시지를 주입받는 공용 리스너로 뽑는다.
 */
public class MemberCSeededValidator implements JobExecutionListener {

    private final JdbcTemplate jdbcTemplate;

    public MemberCSeededValidator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code COUNT(*)} 대신 {@code LIMIT 1} 로 존재 여부만 본다. 200만 건을 매 실행 세는 것은
     * 그 자체로 측정 노이즈이며, 하필 이 문제에서는 <b>측정하려는 바로 그 비용</b>(전체 스캔)이다.
     *
     * @param jobExecution 실행 정보. 쓰지 않는다
     * @throws IllegalStateException {@code member_c} 가 비어 있을 때
     */
    @Override
    public void beforeJob(JobExecution jobExecution) {
        boolean empty = jdbcTemplate
                .queryForList("SELECT 1 FROM member_c LIMIT 1")
                .isEmpty();

        if (empty) {
            throw new IllegalStateException(
                    "member_c 가 비어 있다. 3번 문제는 200만 건을 전량 순회하며 페이지당 소요 시간을 "
                            + "재는 실습이므로 읽을 데이터가 없으면 측정이 성립하지 않는다 "
                            + "(0건 순회는 before 도 즉시 끝나서 개선과 구분되지 않는다). 먼저 시딩한다: "
                            + "./gradlew bootRun --args='--spring.batch.job.enabled=true "
                            + "--spring.batch.job.name=seedJob target=member_c'");
        }
    }
}
