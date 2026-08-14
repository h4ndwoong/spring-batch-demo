package com.h4ndwoong.batchdemo.paging;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OffsetPagingItemReader}(before)가 페이징 계약을 지키는지, 그리고 <b>정말 OFFSET 을 쓰는지</b>
 * 확인한다.
 *
 * <p>SQL 을 문자열로 확인하는 시험이 하나 있는 이유는, 3번 문제의 before/after 차이가 코드 구조가
 * 아니라 <b>SQL 한 줄</b>이기 때문이다. 실행 결과만 보면 두 구현은 구별되지 않는다 — 같은 행을 같은
 * 순서로 돌려주고, 쿼리 수도 같다. before 가 정말 함정에 빠져 있는지는 SQL 을 봐야 안다.
 * (Spring Batch 의 {@code JdbcPagingItemReader} 를 그냥 썼다면 이 시험이 실패한다. 그 리더는 이미
 * 키셋 페이징이라서 offset 의 증상이 재현되지 않는다.)
 */
class OffsetPagingItemReaderTest extends PagingItemReaderContract {

    @Override
    protected MeasuredPagingItemReader reader(RowMapper<MemberBase> rowMapper, PageTimingRecorder recorder) {
        return new OffsetPagingItemReader(
                jdbcTemplate,
                PagingJobCommonConfig.SELECT_FROM,
                rowMapper,
                PagingJobCommonConfig.PAGE_SIZE,
                recorder);
    }

    @Test
    @DisplayName("OFFSET 으로 페이지를 가져온다 - before 가 재현하려는 함정 그 자체")
    void offset_을_쓴다() {
        String sql = reader(null, recorder).sql();

        assertThat(sql).contains("LIMIT ? OFFSET ?");
        assertThat(sql)
                .as("offset 방식은 시작점을 조건으로 찾지 않고 앞의 행을 세어서 버린다")
                .doesNotContain("id >");
        assertThat(sql)
                .as("정렬이 없으면 느린 게 아니라 틀린 구현이 된다").contains("ORDER BY id ASC");
    }
}
