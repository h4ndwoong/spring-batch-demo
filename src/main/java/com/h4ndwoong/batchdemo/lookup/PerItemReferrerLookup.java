package com.h4ndwoong.batchdemo.lookup;

import com.h4ndwoong.batchdemo.domain.MemberGrade;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

/**
 * 행마다 추천인을 조회한다. <b>4번 문제 before 의 전부</b>다.
 *
 * <p><b>재현하는 증상</b><br>
 * {@code find} 한 번이 SELECT 두 번을 보낸다. 50만 건이면 <b>100만 번의 왕복</b>이다.
 * <ol>
 *   <li>{@code SELECT id, grade FROM member_d WHERE id = ?} — 추천인이 실재하는지 확인하고 행을 얻는다</li>
 *   <li>{@code SELECT grade FROM member_d WHERE id = ?} — 보너스 산정에 쓸 등급을 확인한다</li>
 * </ol>
 *
 * <p><b>두 조회가 겹친다는 것이 바로 증상이다.</b> 첫 조회가 이미 {@code grade} 를 가져왔는데 두
 * 번째가 같은 것을 다시 묻는다. 실무에서 이 코드는 한 클래스 안에 이렇게 붙어 있지 않다 —
 * {@code MemberRepository.findById(id)} 로 존재를 확인하는 코드와 {@code GradeService.gradeOf(id)}
 * 로 등급을 얻는 코드가 <b>서로를 모른 채</b> 각자 DB 에 다녀온다. 각 메서드만 놓고 보면 어느 쪽도
 * 잘못 짜지 않았고, 둘 다 PK 조회라 <b>단건 응답은 1ms 도 걸리지 않는다.</b> 문제는 그것이
 * 50만 번 반복된다는 사실뿐이며, 그 사실은 코드를 아무리 들여다봐도 보이지 않는다.
 *
 * <p><b>{@code IN} 절로 묶지 못하는 이유가 성능 지식의 부족이 아니라 구조에 있다.</b>
 * {@code ItemProcessor} 는 행 단위 계약이라 <em>자기가 처리 중인 행 하나</em>밖에 모른다. 청크
 * 전체를 보려면 프레임워크의 다른 확장점이 필요하고, 그것이 after 의 내용이다
 * ({@link ChunkedReferrerLookup}).
 *
 * <p>after 와 <b>같은 컬럼을 같은 PK 경로로</b> 읽는다는 점이 중요하다. 읽는 양이 다르면 왕복
 * 횟수 말고 다른 것이 비교에 끼어든다.
 */
public class PerItemReferrerLookup implements ReferrerLookup {

    /** 추천인 조회. 실무의 {@code MemberRepository.findById} 에 해당한다. */
    private static final String FIND_SQL = "SELECT id, grade FROM member_d WHERE id = ?";

    /** 추천인 등급 확인. 실무의 {@code GradeService.gradeOf} 에 해당한다. 앞의 조회와 겹친다. */
    private static final String GRADE_SQL = "SELECT grade FROM member_d WHERE id = ?";

    private final JdbcTemplate jdbcTemplate;

    private long lookups;
    private long queries;

    /**
     * 조회기를 만든다.
     *
     * @param jdbcTemplate JDBC 템플릿
     */
    public PerItemReferrerLookup(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * {@inheritDoc}
     *
     * <p>추천인이 없으면({@code null}) <b>조회하지 않는다.</b> 그래서 before 의 왕복 횟수는
     * {@code 2 × 건수} 가 아니라 {@code 2 × (건수 - 1)} 이다 ({@code member_d} 에서 추천인이 없는
     * 행은 {@code id=1} 하나뿐이다).
     */
    @Override
    public Optional<Referrer> find(Long referrerId) {
        if (referrerId == null) {
            return Optional.empty();
        }

        lookups++;

        queries++;
        List<Long> found = jdbcTemplate.query(FIND_SQL,
                (rs, rowNum) -> rs.getLong("id"), referrerId);
        if (found.isEmpty()) {
            return Optional.empty();
        }

        queries++;
        List<String> grades = jdbcTemplate.query(GRADE_SQL,
                (rs, rowNum) -> rs.getString("grade"), referrerId);
        if (grades.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new Referrer(found.get(0), MemberGrade.valueOf(grades.get(0))));
    }

    /** {@inheritDoc} */
    @Override
    public ReferrerLookupStats stats() {
        return new ReferrerLookupStats(lookups, queries, 0);
    }

    /**
     * {@inheritDoc}
     *
     * <p>청크를 모르므로 비울 캐시가 없다. 계측치만 되돌린다.
     */
    @Override
    public void reset() {
        lookups = 0;
        queries = 0;
    }
}
