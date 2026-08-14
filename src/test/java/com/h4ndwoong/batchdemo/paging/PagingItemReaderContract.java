package com.h4ndwoong.batchdemo.paging;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.domain.MemberC;
import com.h4ndwoong.batchdemo.support.MemberRowMapper;
import com.h4ndwoong.batchdemo.support.MemberTableSeeder;
import com.h4ndwoong.batchdemo.support.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 두 페이징 리더가 <b>똑같이 지켜야 하는 계약</b>. offset 이든 키셋이든 이 시험을 통과해야 한다.
 *
 * <p>3번 문제에서 리더를 바꾸는 것은 성능 개선이지만, 페이징 방식을 바꿀 때 가장 먼저 깨지는 것은
 * 성능이 아니라 <b>정확성</b>이다. 행을 건너뛰거나 중복해서 읽고도 그냥 빨라 보일 수 있다. 그래서
 * 계약을 한곳에 두고 두 구현에 똑같이 적용한다 — 한쪽에만 있는 시험은 비교를 보장하지 못한다.
 *
 * <p>계약은 네 가지다.
 * <ol>
 *   <li>{@code id} 오름차순으로 <b>빠짐없이, 중복 없이</b> 전부 읽는다</li>
 *   <li>더 없으면 {@code null} 을 돌려준다</li>
 *   <li>{@code maxItemCount} 만큼만 읽고 멈춘다 ({@code pages} 파라미터의 근거)</li>
 *   <li>닫았다 다시 열면 처음부터 읽는다 (리더가 들고 있는 위치가 되돌려진다)</li>
 * </ol>
 */
@SpringBootTest(properties = {
        TestDatabase.URL,
        TestDatabase.BATCH_SCHEMA,
        TestDatabase.DOMAIN_SCHEMA
})
abstract class PagingItemReaderContract {

    /** 페이지 크기의 배수가 <b>아닌</b> 건수. 마지막 페이지가 부분 페이지가 된다. */
    private static final long COUNT = 3_500L;

    private static final int FULL_PAGES = 3;
    private static final int LAST_PAGE_ROWS = 500;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    protected final PageTimingRecorder recorder = new PageTimingRecorder();

    /**
     * 시험할 리더를 만든다.
     *
     * @param rowMapper 행 매퍼
     * @param recorder  측정 장치
     * @return 리더
     */
    protected abstract MeasuredPagingItemReader reader(RowMapper<MemberBase> rowMapper,
                                                       PageTimingRecorder recorder);

    @BeforeEach
    void 데이터를_채운다() {
        MemberTableSeeder.seed(jdbcTemplate, "member_c", MemberC::new, COUNT, 0);
    }

    @AfterEach
    void 실습_테이블을_비운다() {
        jdbcTemplate.execute("TRUNCATE TABLE member_c");
    }

    @Test
    @DisplayName("전부를 id 오름차순으로 빠짐없이 읽는다 - 페이징 방식이 결과를 바꾸면 안 된다")
    void 전량_순회() throws Exception {
        List<Long> ids = readAll(newReader());

        assertThat(ids).hasSize((int) COUNT);
        assertThat(ids).isSorted();
        assertThat(ids.get(0)).isEqualTo(1L);
        assertThat(ids.get(ids.size() - 1)).isEqualTo(COUNT);
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("마지막 페이지가 부분 페이지면 추가 조회를 하지 않는다")
    void 부분_페이지에서_끝난다() throws Exception {
        readAll(newReader());

        PageTimingReport report = recorder.report();

        assertThat(report.pageCount()).isEqualTo(FULL_PAGES + 1);
        assertThat(report.pages().get(FULL_PAGES).rows())
                .as("마지막 페이지는 %d행이므로 리더는 더 물어보지 않는다", LAST_PAGE_ROWS)
                .isEqualTo(LAST_PAGE_ROWS);
    }

    @Test
    @DisplayName("건수가 페이지 크기의 배수면 빈 페이지를 한 번 더 읽는다 - 2,001 페이지의 이유")
    void 배수면_빈_페이지가_한_장_더() throws Exception {
        long count = PagingJobCommonConfig.PAGE_SIZE * 2L;
        MemberTableSeeder.seed(jdbcTemplate, "member_c", MemberC::new, count, 0);

        List<Long> ids = readAll(newReader());

        assertThat(ids).hasSize((int) count);
        assertThat(recorder.report().pageCount()).isEqualTo(3);
        assertThat(recorder.report().pages().get(2).rows())
                .as("'더 없음' 을 확인하는 조회다. offset 이면 이 한 번도 전체를 훑는다").isZero();
    }

    @Test
    @DisplayName("maxItemCount 까지만 읽는다 - pages 파라미터가 서 있는 자리")
    void 상한까지만_읽는다() throws Exception {
        MeasuredPagingItemReader reader = newReader();
        reader.setMaxItemCount(PagingJobCommonConfig.PAGE_SIZE * 2);

        List<Long> ids = readAll(reader);

        assertThat(ids).hasSize(PagingJobCommonConfig.PAGE_SIZE * 2);
        assertThat(ids.get(ids.size() - 1)).isEqualTo(PagingJobCommonConfig.PAGE_SIZE * 2L);
        assertThat(recorder.report().pageCount())
                .as("상한에 걸리면 다음 페이지를 가져오지 않는다").isEqualTo(2);
    }

    @Test
    @DisplayName("닫았다 다시 열면 처음부터 읽는다 - 리더가 들고 있는 위치가 되돌려진다")
    void 다시_열면_처음부터() throws Exception {
        MeasuredPagingItemReader reader = newReader();
        readAll(reader);

        List<Long> ids = readAll(reader);

        assertThat(ids)
                .as("되돌리지 않으면 두 번째 실행이 0건을 읽고 조용히 성공한다")
                .hasSize((int) COUNT);
        assertThat(ids.get(0)).isEqualTo(1L);
    }

    @Test
    @DisplayName("재시작을 지원하지 않는다고 선언한다 - 조용히 틀린 구간을 읽느니 실패한다")
    void 재시작_미지원() {
        MeasuredPagingItemReader reader = newReader();

        assertThatThrownBy(() -> reader.jumpToItem(1_000))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("재시작");
    }

    private MeasuredPagingItemReader newReader() {
        return reader(new MemberRowMapper(MemberC::new), recorder);
    }

    /**
     * 리더를 열고 끝까지 읽은 뒤 닫는다.
     *
     * @param reader 리더
     * @return 읽은 행의 식별자. 읽은 순서 그대로
     */
    private static List<Long> readAll(MeasuredPagingItemReader reader) throws Exception {
        reader.open(new ExecutionContext());
        try {
            List<Long> ids = new ArrayList<>();
            MemberBase member;
            while ((member = reader.read()) != null) {
                ids.add(member.getId());
            }
            return ids;
        }
        finally {
            reader.close();
        }
    }
}
