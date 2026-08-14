package com.h4ndwoong.batchdemo.paging;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

/**
 * {@code WHERE id > ? ... LIMIT ?} 로 페이지를 가져오는 키셋(ZeroOffset) 리더. <b>3번 문제 after</b> 다.
 *
 * <p><b>개선의 정체</b><br>
 * offset 은 "앞의 n건을 세어서 버린다" 이고 키셋은 "마지막으로 본 지점부터 이어서 읽는다" 이다.
 * {@code id} 에 PK 인덱스가 있으므로 {@code id > :lastId} 는 <b>인덱스 탐색 한 번</b>으로 시작점을
 * 찾고 거기서 1,000행만 읽는다. 페이지 번호가 몇이든 하는 일이 같아서
 * <ul>
 *   <li>페이지당 소요 시간이 <b>평탄</b>하다</li>
 *   <li>전체 스캔량이 N²/(2×페이지크기) 가 아니라 <b>N</b> 이다</li>
 * </ul>
 * 쿼리 수는 offset 과 <b>같다</b>. 줄어든 것은 왕복이 아니라 쿼리 하나가 읽는 행 수다.
 *
 * <p><b>공짜가 아니다 — 전제가 있다.</b>
 * <ul>
 *   <li><b>정렬 키가 유니크해야 한다.</b> 중복 값이 있으면 {@code >} 로 넘어갈 때 같은 값을 가진
 *       행들이 통째로 잘려 나간다. 여기서는 PK 인 {@code id} 를 쓰므로 성립한다. 실무에서
 *       {@code created_at} 처럼 중복 가능한 컬럼으로 키셋을 하려면 {@code (created_at, id)} 복합
 *       비교가 필요하다.</li>
 *   <li><b>정렬 키에 인덱스가 있어야 한다.</b> 없으면 {@code id > ?} 를 위해 매번 전체를 훑게 되어
 *       offset 과 다를 바 없어진다.</li>
 *   <li><b>임의 페이지로 건너뛸 수 없다.</b> "1,500페이지를 보여 달라" 는 요구는 키셋으로 답할 수
 *       없다. 배치의 전량 순회에는 문제가 없지만 화면 페이지네이션에는 제약이다.</li>
 * </ul>
 *
 * <p><b>위치를 필드로 들고 있다.</b> {@code lastId} 가 이 리더의 커서다. 그래서 재시작이 성립하지
 * 않으며({@link MeasuredPagingItemReader} 참고) {@link #doClose()} 에서 반드시 되돌려야 한다 —
 * 되돌리지 않으면 같은 컨텍스트에서 Job 을 두 번째 실행할 때 <b>0건을 읽고 조용히 COMPLETED</b> 로
 * 끝난다.
 */
public class KeysetPagingItemReader extends MeasuredPagingItemReader {

    /** 첫 페이지의 시작점. {@code id} 는 1부터이므로 0이면 전부 포함된다. */
    private static final long FIRST_KEY = 0L;

    private static final String PAGE_CLAUSE = " WHERE id > ? ORDER BY id ASC LIMIT ?";

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<MemberBase> rowMapper;
    private final String sql;

    private long lastId = FIRST_KEY;

    /**
     * 리더를 만든다.
     *
     * @param jdbcTemplate JDBC 템플릿
     * @param selectFrom   {@code SELECT ... FROM 테이블} 까지. 조건과 페이징 절은 이 클래스가 붙인다
     * @param rowMapper    행 매퍼
     * @param pageSize     페이지 크기
     * @param recorder     페이지별 소요 시간을 받을 측정 장치
     */
    public KeysetPagingItemReader(JdbcTemplate jdbcTemplate,
                                  String selectFrom,
                                  RowMapper<MemberBase> rowMapper,
                                  int pageSize,
                                  PageTimingRecorder recorder) {
        super("keysetPagingItemReader", pageSize, recorder);
        this.jdbcTemplate = jdbcTemplate;
        this.rowMapper = rowMapper;
        this.sql = selectFrom + PAGE_CLAUSE;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code pageIndex} 를 쓰지 않는 것이 핵심이다. 이 리더는 <b>몇 번째 페이지인지 모른다.</b>
     * 아는 것은 마지막으로 읽은 {@code id} 뿐이고, 그래서 뒤 페이지가 앞 페이지보다 비싸지 않다.
     */
    @Override
    protected List<MemberBase> fetchPage(int pageIndex, int pageSize) {
        List<MemberBase> page = jdbcTemplate.query(sql, rowMapper, lastId, pageSize);
        if (!page.isEmpty()) {
            lastId = page.get(page.size() - 1).getId();
        }
        return page;
    }

    /** {@inheritDoc} */
    @Override
    public String sql() {
        return sql;
    }

    /**
     * {@inheritDoc}
     *
     * <p>커서를 처음으로 되돌린다. 상위 클래스는 페이지 번호와 버퍼만 되돌리므로 이 리더의 위치는
     * 여기서 직접 초기화해야 한다.
     */
    @Override
    protected void doClose() throws Exception {
        super.doClose();
        lastId = FIRST_KEY;
    }
}
