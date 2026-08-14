package com.h4ndwoong.batchdemo.lookup;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.domain.MemberGrade;
import org.springframework.batch.core.ChunkListener;
import org.springframework.batch.core.ItemReadListener;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 청크의 추천인을 {@code WHERE id IN (...)} <b>한 번</b>으로 가져온다. <b>4번 문제 after 의 전부</b>다.
 *
 * <p><b>개선의 정체</b><br>
 * before 는 행마다 2회 왕복했다. 여기서는 청크(1,000행)당 1회다 —
 * <b>왕복이 2,000분의 1</b>이 된다. 읽는 컬럼도, 타는 인덱스(PK)도, 결과도 같다.
 * 줄어든 것은 <b>왕복 횟수뿐</b>이다.
 *
 * <p><b>{@code ItemProcessor} 는 행 하나만 아는데 어떻게 청크를 묶는가</b><br>
 * 청크 지향 Step 의 실행 순서가 답이다. <b>청크의 모든 행을 다 읽은 뒤에</b> 가공이 시작된다.
 * <pre>
 *   beforeChunk → afterRead × N → process × N → write → afterChunk
 *                 └ 여기서 id 를 모은다 ┘ └ 첫 호출이 IN 조회 1회를 유발, 나머지는 캐시 ┘
 * </pre>
 * 그래서 이 클래스는 {@link ItemReadListener} 자격으로 <b>읽히는 동안 {@code referrer_id} 만
 * 모으고</b>, 첫 {@link #find} 호출에서 모아둔 전체를 한 번에 조회한다. 프로세서는 자기가 어떤
 * 전략과 일하는지 모른 채 {@link ReferrerLookup#find} 만 부른다.
 *
 * <p><b>리더를 바꾸지 않는다는 것이 설계의 요점이다.</b> 페이징 리더로 바꿔 "페이지 = 청크" 를
 * 만들어도 같은 효과를 얻지만, 그러면 <b>읽기 경로가 달라져</b> 개선의 원인을 조회 방식 하나로
 * 귀속시킬 수 없다. 리더 SQL 에 {@code JOIN} 을 넣는 답(가장 빠르다)도 같은 이유로 쓰지 않는다 —
 * 조회 횟수 문제를 조회 자체를 없애서 비껴가면 4번 문제가 남지 않는다.
 *
 * <p><b>전제: {@code faultTolerant} Step 이 아니어야 한다.</b> 스킵·재시도가 켜지면 프레임워크가
 * 청크를 행 단위로 되짚으며 read/process 를 뒤섞으므로 "읽기가 끝난 뒤 가공" 이 깨진다. 4번은
 * 오류 처리 문제가 아니므로({@code member_d} 에 오염 행이 없다) faultTolerant 를 쓰지 않는다.
 * 그래도 계약을 어긴 호출에 <b>조용히 틀린 답을 주지는 않는다</b> — 모아둔 적 없는 {@code id} 를
 * 물으면 그 자리에서 한 건 조회한다 ({@link #find} 참고).
 *
 * <p><b>이것이 공짜가 아닌 지점</b>이 README 가 말한 "청크 사이즈 트레이드오프" 다. 청크가 커질수록
 * 왕복은 줄지만 (1) 캐시가 그만큼의 추천인을 메모리에 들고 있어야 하고, (2) {@code IN} 절의
 * 바인딩 파라미터가 그만큼 늘어난다. 상한은
 * {@link LookupJobCommonConfig#MAX_CHUNK_SIZE} 에서 지킨다.
 */
public class ChunkedReferrerLookup implements ReferrerLookup, ItemReadListener<MemberBase>, ChunkListener {

    private static final String SELECT_PREFIX = "SELECT id, grade FROM member_d WHERE id IN (";

    private static final String SINGLE_SQL = "SELECT id, grade FROM member_d WHERE id = ?";

    private final JdbcTemplate jdbcTemplate;

    /** 이번 청크에서 읽힌 {@code referrer_id} 들. 중복은 여기서 이미 사라진다. */
    private final Set<Long> pending = new LinkedHashSet<>();

    /** 이번 청크의 조회 결과. 없는 {@code id} 는 담기지 않는다. */
    private final Map<Long, Referrer> cache = new HashMap<>();

    /** 중복 포함, 이번 청크에서 요구된 조회 수. {@link #pending} 과의 차이가 아낀 조회다. */
    private int chunkRequests;

    private boolean loaded;

    private long lookups;
    private long queries;
    private long deduplicated;

    /**
     * 조회기를 만든다.
     *
     * @param jdbcTemplate JDBC 템플릿
     */
    public ChunkedReferrerLookup(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * {@inheritDoc}
     *
     * <p>읽히는 족족 {@code referrer_id} 만 모은다. <b>여기서는 DB 에 가지 않는다.</b>
     */
    @Override
    public void afterRead(MemberBase item) {
        Long referrerId = item.getReferrerId();
        if (referrerId != null) {
            pending.add(referrerId);
            chunkRequests++;
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>이전 청크의 캐시를 버린다. 청크 경계를 넘겨 캐시를 유지하면 왕복이 더 줄겠지만, 그러면
     * "청크당 1회" 라는 주장이 흐려지고 메모리 상한도 사라진다. 청크를 넘는 재사용은 측정 결과를
     * 본 뒤에 판단할 일이다.
     */
    @Override
    public void beforeChunk(ChunkContext context) {
        clearChunk();
    }

    private void clearChunk() {
        pending.clear();
        cache.clear();
        chunkRequests = 0;
        loaded = false;
    }

    /**
     * {@inheritDoc}
     *
     * <p>첫 호출이 청크 전체의 조회를 유발하고, 같은 청크의 나머지 호출은 모두 캐시에서 답한다.
     * 모아둔 적 없는 {@code id} 를 물으면(= 계약을 어긴 호출) 그 자리에서 한 건을 조회한다.
     * 조용히 "없음" 을 돌려주면 산정 결과가 before 와 갈라지는데, 그것은 <b>느려지는 것보다 나쁜
     * 실패</b>다.
     */
    @Override
    public Optional<Referrer> find(Long referrerId) {
        if (referrerId == null) {
            return Optional.empty();
        }

        lookups++;

        if (!loaded) {
            loadPending();
        }
        if (pending.contains(referrerId)) {
            return Optional.ofNullable(cache.get(referrerId));
        }
        return findOne(referrerId);
    }

    /** 모아둔 {@code id} 전체를 한 문장으로 가져온다. 모은 것이 없으면 <b>쿼리를 보내지 않는다</b>. */
    private void loadPending() {
        loaded = true;
        if (pending.isEmpty()) {
            return;
        }

        deduplicated += chunkRequests - pending.size();
        queries++;

        String sql = SELECT_PREFIX + String.join(", ", Collections.nCopies(pending.size(), "?")) + ")";
        jdbcTemplate.query(sql, resultSet -> {
            long id = resultSet.getLong("id");
            cache.put(id, new Referrer(id, MemberGrade.valueOf(resultSet.getString("grade"))));
        }, pending.toArray());
    }

    private Optional<Referrer> findOne(Long referrerId) {
        queries++;
        return jdbcTemplate.query(SINGLE_SQL,
                        (rs, rowNum) -> new Referrer(rs.getLong("id"), MemberGrade.valueOf(rs.getString("grade"))),
                        referrerId)
                .stream()
                .findFirst();
    }

    /** {@inheritDoc} */
    @Override
    public ReferrerLookupStats stats() {
        return new ReferrerLookupStats(lookups, queries, deduplicated);
    }

    /** {@inheritDoc} */
    @Override
    public void reset() {
        clearChunk();
        lookups = 0;
        queries = 0;
        deduplicated = 0;
    }
}
