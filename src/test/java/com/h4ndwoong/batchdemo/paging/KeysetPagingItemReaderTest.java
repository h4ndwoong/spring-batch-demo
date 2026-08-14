package com.h4ndwoong.batchdemo.paging;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.domain.MemberC;
import com.h4ndwoong.batchdemo.support.MemberRowMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.jdbc.core.RowMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KeysetPagingItemReader}(after)가 offset 리더와 <b>똑같은 계약</b>을 지키는지, 그리고 SQL 이
 * 키셋 모양인지 확인한다.
 *
 * <p>상속받은 계약 시험이 이 클래스의 본체다. after 가 before 와 같은 행 집합을 같은 순서로
 * 돌려준다는 것이 먼저 서야 "더 빠르다" 가 의미를 갖는다.
 */
class KeysetPagingItemReaderTest extends PagingItemReaderContract {

    @Override
    protected MeasuredPagingItemReader reader(RowMapper<MemberBase> rowMapper, PageTimingRecorder recorder) {
        return new KeysetPagingItemReader(
                jdbcTemplate,
                PagingJobCommonConfig.SELECT_FROM,
                rowMapper,
                PagingJobCommonConfig.PAGE_SIZE,
                recorder);
    }

    @Test
    @DisplayName("마지막으로 읽은 id 다음부터 가져온다 - OFFSET 이 없다")
    void 키셋을_쓴다() {
        String sql = reader(null, recorder).sql();

        assertThat(sql).contains("WHERE id > ?");
        assertThat(sql)
                .as("offset 이 0으로 고정된다는 것이 이 개선의 이름(ZeroOffset)이다")
                .doesNotContain("OFFSET");
        assertThat(sql).contains("ORDER BY id ASC");
    }

    @Test
    @DisplayName("페이지 번호를 모르고도 읽는다 - 뒤 페이지가 앞 페이지보다 비싸지 않은 이유")
    void 위치는_id_로만_정해진다() throws Exception {
        MeasuredPagingItemReader reader = reader(new MemberRowMapper(MemberC::new), recorder);
        reader.open(new ExecutionContext());
        try {
            for (int i = 0; i < PagingJobCommonConfig.PAGE_SIZE + 1; i++) {
                reader.read();
            }

            assertThat(recorder.report().pageCount())
                    .as("두 번째 페이지를 가져왔다").isEqualTo(2);
            assertThat(reader.read().getId())
                    .as("두 번째 페이지도 id 순서가 이어진다")
                    .isEqualTo(PagingJobCommonConfig.PAGE_SIZE + 2L);
        }
        finally {
            reader.close();
        }
    }
}
