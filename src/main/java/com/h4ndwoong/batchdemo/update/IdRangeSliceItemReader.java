package com.h4ndwoong.batchdemo.update;

import org.springframework.batch.item.ItemReader;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 갱신할 {@code id} 구간을 잘라서 발행한다. 6번 문제 after 의 리더이며, <b>회원을 한 행도 읽지
 * 않는다.</b>
 *
 * <p>이것이 6번 after 의 성격을 결정한다. 1~5번의 리더는 모두 회원 행을 애플리케이션으로
 * 끌어올렸지만, 여기서 서버에 오가는 것은 {@code MIN(id), MAX(id)} 한 번과 슬라이스마다 UPDATE
 * 한 문장뿐이다. 100만 행의 데이터는 <b>서버 밖으로 나오지 않는다.</b>
 *
 * <p><b>Step 통계의 의미가 바뀐다.</b> {@code READ_COUNT} 는 회원 수가 아니라 슬라이스 수(기본
 * 20)다. 그래서 6번은 Step 통계로 before 와 비교할 수 없고, 비교 축은 왕복 횟수·갱신 행 수·시간이
 * 된다. 2번이 "시간으로 비교할 수 없는 문제" 였던 것과 같은 종류의 주의다.
 *
 * <p><b>{@code ItemStream} 을 구현하지 않는다.</b> 재시작 시 위치를 복원하지 않는다는 뜻이다.
 * 6번의 주제는 쓰기 경로이고, 이 배치는 자연 멱등이라({@code grade <> CASE ...} 조건이 이미 옳은
 * 행을 건너뛴다) 처음부터 다시 돌려도 결과가 같다. 재시작과 멱등성은 5번의 주제이며, 거기서
 * 확인한 것처럼 <b>프레임워크의 위치 기억과 데이터의 기억을 겹쳐 두면 서로를 무효화한다.</b>
 * 여기서는 데이터 쪽을 택했다.
 *
 * <p><b>{@code @StepScope} 로 써야 한다.</b> 슬라이스 목록을 필드에 들고 있으므로 Step 실행마다
 * 새 인스턴스여야 한다. 싱글턴이면 두 번째 실행이 소진된 반복자를 물려받아 <b>0건 처리 후
 * {@code COMPLETED}</b> 로 끝난다.
 */
public class IdRangeSliceItemReader implements ItemReader<IdSlice> {

    private static final String RANGE_SQL_TEMPLATE = "SELECT MIN(id) AS min_id, MAX(id) AS max_id FROM %s";

    private final JdbcTemplate jdbcTemplate;
    private final String table;
    private final long sliceSize;

    private Iterator<IdSlice> slices;

    /**
     * 리더를 만든다.
     *
     * @param jdbcTemplate JDBC 템플릿
     * @param table        대상 테이블 이름. <b>코드에 적힌 상수만</b> 넘긴다. 외부 입력은 안 된다
     * @param sliceSize    슬라이스 하나가 덮을 {@code id} 개수. 0 이하면 분할하지 않는다
     */
    public IdRangeSliceItemReader(JdbcTemplate jdbcTemplate, String table, long sliceSize) {
        this.jdbcTemplate = jdbcTemplate;
        this.table = table;
        this.sliceSize = sliceSize;
    }

    /**
     * {@inheritDoc}
     *
     * <p>첫 호출에서 키 범위를 한 번 조회해 슬라이스를 모두 만들어 둔다. 범위 조회는 PK 인덱스의
     * 양 끝을 보는 것이라 100만 행에서도 즉시 끝난다 (3번 문제에서 확인한 것처럼 "훑는" 조회가
     * 아니다).
     *
     * @return 다음 슬라이스. 더 없으면 {@code null}
     */
    @Override
    public IdSlice read() {
        if (slices == null) {
            slices = load().iterator();
        }
        return slices.hasNext() ? slices.next() : null;
    }

    /**
     * 이번 Step 이 처리할 슬라이스 전부.
     *
     * <p>빈 테이블이면 빈 목록이다. 여기까지 오기 전에 {@code TableSeededValidator} 가 Job 을
     * 세우므로 실제로는 그 방어가 뚫렸을 때만 보인다.
     *
     * @return 슬라이스 목록
     */
    private List<IdSlice> load() {
        Map<String, Object> range = jdbcTemplate.queryForMap(RANGE_SQL_TEMPLATE.formatted(table));
        Object minId = range.get("min_id");
        if (minId == null) {
            return List.of();
        }
        return IdSlice.of(((Number) minId).longValue(),
                ((Number) range.get("max_id")).longValue(),
                sliceSize);
    }
}
