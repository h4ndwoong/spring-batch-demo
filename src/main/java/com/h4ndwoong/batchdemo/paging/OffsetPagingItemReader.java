package com.h4ndwoong.batchdemo.paging;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

/**
 * {@code LIMIT ? OFFSET ?} 로 페이지를 가져오는 리더. <b>3번 문제 before 의 함정 그 자체</b>다.
 *
 * <p><b>증상</b><br>
 * {@code OFFSET n} 은 "n번째부터 주세요" 라는 뜻이 아니라 <b>"앞의 n건을 읽고 버린 뒤 주세요"</b>
 * 라는 뜻이다. DB 는 n번째 행으로 순간이동할 방법이 없다. 그래서
 * <ul>
 *   <li>1페이지는 1,000행을 읽는다</li>
 *   <li>2,000페이지는 <b>2,000,000행을 읽고 1,999,000행을 버린 뒤</b> 1,000행을 준다</li>
 * </ul>
 * 페이지당 시간이 페이지 번호에 비례해 늘고, 전체 순회의 총 스캔량은 행 수 N 에 대해
 * <b>N²/(2×페이지크기)</b> 로 커진다. 200만 건·1,000행 페이지면 약 <b>20억 행</b>이다.
 * 같은 순회를 키셋으로 하면 200만 행이므로 1,000배다.
 *
 * <p><b>가장 고약한 점은 쿼리 수가 같다는 것이다.</b> before 도 after 도 SELECT 를 2,001번 보낸다.
 * 1번 문제처럼 "왕복이 줄었다" 로는 이 개선을 설명할 수 없고, 2번처럼 "끝까지 갔다" 도 아니다.
 * 차이는 <b>쿼리 한 번이 읽는 행 수</b>에만 있으므로 {@code Handler_read_next} 같은 서버 카운터를
 * 봐야 드러난다. 애플리케이션 로그에는 아무 이상이 없다.
 *
 * <p><b>왜 {@code JdbcPagingItemReader} 를 쓰지 않았는가</b><br>
 * 쓸 수 없다. Spring Batch 의 {@code MySqlPagingQueryProvider} 는 2페이지부터
 * {@code generateRemainingPagesQuery()} 를 쓰는데 그 구현이
 * {@code SqlPagingQueryUtils.generateLimitSqlQuery(provider, true, "LIMIT n")} 이고, 두 번째 인자가
 * {@code true} 면 정렬 키 조건({@code WHERE id > ?})을 붙인다. 즉 <b>내장 리더는 이미 키셋 페이징</b>
 * 이라 offset 의 함정이 재현되지 않는다 (OFFSET 은 재시작용
 * {@code generateJumpToItemQuery()} 에만 나온다). 실무에서 이 함정에 빠지는 경로는
 * {@code JpaPagingItemReader}({@code setFirstResult} → {@code LIMIT ? OFFSET ?}) 이거나 직접 짠
 * 페이징 쿼리이며, 후자를 그대로 재현한 것이 이 클래스다. JPA 리더를 쓰지 않은 이유는 하이드레이션
 * 비용이라는 <b>두 번째 변수</b>가 끼어들어 after(JDBC 키셋)와의 비교 축이 오염되기 때문이다.
 */
public class OffsetPagingItemReader extends MeasuredPagingItemReader {

    /**
     * 정렬은 {@code id} 오름차순으로 고정한다. 정렬이 없으면 페이지 사이에 행이 중복되거나
     * 빠질 수 있고, 그러면 느린 것이 아니라 <b>틀린</b> 구현이 된다.
     */
    private static final String PAGE_CLAUSE = " ORDER BY id ASC LIMIT ? OFFSET ?";

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<MemberBase> rowMapper;
    private final String sql;

    /**
     * 리더를 만든다.
     *
     * @param jdbcTemplate JDBC 템플릿
     * @param selectFrom   {@code SELECT ... FROM 테이블} 까지. 정렬과 페이징 절은 이 클래스가 붙인다
     * @param rowMapper    행 매퍼
     * @param pageSize     페이지 크기
     * @param recorder     페이지별 소요 시간을 받을 측정 장치
     */
    public OffsetPagingItemReader(JdbcTemplate jdbcTemplate,
                                  String selectFrom,
                                  RowMapper<MemberBase> rowMapper,
                                  int pageSize,
                                  PageTimingRecorder recorder) {
        super("offsetPagingItemReader", pageSize, recorder);
        this.jdbcTemplate = jdbcTemplate;
        this.rowMapper = rowMapper;
        this.sql = selectFrom + PAGE_CLAUSE;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code OFFSET} 이 페이지 번호에 비례해 커진다. 이 곱셈이 이 문제의 전부다.
     */
    @Override
    protected List<MemberBase> fetchPage(int pageIndex, int pageSize) {
        long offset = (long) pageIndex * pageSize;
        return jdbcTemplate.query(sql, rowMapper, pageSize, offset);
    }

    /** {@inheritDoc} */
    @Override
    public String sql() {
        return sql;
    }
}
