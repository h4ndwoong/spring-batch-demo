package com.h4ndwoong.batchdemo.paging;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.domain.MemberC;
import com.h4ndwoong.batchdemo.support.MemberRowMapper;
import com.h4ndwoong.batchdemo.support.TableSeededValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * 3번 문제에서 <b>before 와 after 가 공유해야 하는</b> 구성.
 *
 * <p>3번 문제의 차이는 오직 하나다 — <b>페이지를 어떤 SQL 로 가져오는가</b>. 페이지 크기도, 청크
 * 크기도, 행 매퍼도, 라이터도, 측정 장치도 양쪽이 같아야 한다. 그래야 "after 가 빠르다" 가
 * 다른 변수에 오염되지 않는다. 프로파일별 구성({@link BeforePagingJobConfig},
 * {@link AfterPagingJobConfig})에는 <b>리더 빈 하나</b>와 Job/Step 선언만 남는다.
 */
@Configuration
public class PagingJobCommonConfig {

    /**
     * 페이지 크기. <b>양쪽 공통</b>이며 상수인 데에 이유가 있다.
     *
     * <p>offset 페이징의 총 스캔량은 N²/(2×페이지크기)라서 이 값이 바뀌면 before 의 비용이
     * 통째로 움직인다. Job 파라미터로 열어 두면 before 와 after 를 서로 다른 값으로 돌려 놓고
     * 비교하는 사고가 난다. 실험하고 싶으면 이 상수를 고쳐 양쪽을 다시 돌린다.
     */
    public static final int PAGE_SIZE = 1_000;

    /**
     * 커밋 단위. <b>페이지 크기와 같게 둔다.</b>
     *
     * <p>청크와 페이지가 어긋나면 "이 청크가 느린 이유가 페이지를 새로 가져와서인가, 커밋 때문인가"
     * 를 구분할 수 없다. 같게 두면 청크 하나가 정확히 페이지 하나다.
     */
    public static final int CHUNK_SIZE = PAGE_SIZE;

    /**
     * 페이지 획득 SQL 의 앞부분. 정렬·조건·페이징 절은 각 리더가 붙인다.
     *
     * <p>전 컬럼을 읽는다. 커버링 인덱스로 도망가면 offset 의 비용이 줄어드는데, 그건 3번 문제의
     * 개선안이 아니라 문제를 비껴가는 것이다. 실무의 배치도 대개 행 전체를 필요로 한다.
     */
    public static final String SELECT_FROM = """
            SELECT id, email, name, grade, point, status, referrer_id,
                   processed, idempotency_key, created_at, updated_at
            FROM member_c""";

    /**
     * {@code pages} 파라미터를 리더의 최대 항목 수로 바꾼다.
     *
     * <p><b>왜 이 파라미터가 있는가</b><br>
     * before 의 200만 건 완주는 오래 걸린다 (앞 페이지의 비용이 아니라 뒤 페이지의 비용이 지배하며,
     * 총 스캔량이 약 20억 행이다). {@code pages=200} 이면 앞 20만 건만 읽고 끝나는데, 페이지별 시간
     * 그래프의 <b>기울기</b>는 그 구간에서 이미 드러난다. 양쪽에 같은 값을 주는 한 비교는 공정하다.
     *
     * <p>기본값은 "전체" 다. 파라미터를 생략한 실행이 조용히 일부만 읽고 끝나면 그 측정치는
     * 아무 의미가 없기 때문이다.
     *
     * @param pages 읽을 페이지 수. {@code null} 이거나 0 이하면 전체
     * @return 리더에 설정할 최대 항목 수
     * @throws NumberFormatException 숫자가 아닌 값이 넘어왔을 때
     */
    public static int maxItemCount(String pages) {
        if (pages == null) {
            return Integer.MAX_VALUE;
        }
        int value = Integer.parseInt(pages.trim());
        if (value <= 0) {
            return Integer.MAX_VALUE;
        }
        long total = (long) value * PAGE_SIZE;
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    /**
     * {@code member_c} 에 읽을 데이터가 있는지 확인하는 리스너.
     *
     * <p>3번의 이 리스너가 남긴 약속("4번 문제에서 셋째가 생기면 테이블과 메시지를 주입받는 공용
     * 리스너로 뽑는다")을 4번에서 지켰다. {@link TableSeededValidator} 가 그 결과다.
     *
     * @param jdbcTemplate JDBC 템플릿
     * @return 리스너
     */
    @Bean
    public TableSeededValidator memberCSeededValidator(JdbcTemplate jdbcTemplate) {
        return new TableSeededValidator(jdbcTemplate, "member_c",
                "3번 문제는 200만 건을 전량 순회하며 페이지당 소요 시간을 재는 실습이므로 읽을 데이터가 "
                        + "없으면 측정이 성립하지 않는다 (0건 순회는 before 도 즉시 끝나서 개선과 구분되지 않는다).");
    }

    /**
     * 행 매퍼. 양쪽 리더가 같은 인스턴스를 쓴다.
     *
     * @return 매퍼
     */
    @Bean
    public RowMapper<MemberBase> memberCRowMapper() {
        return new MemberRowMapper(MemberC::new);
    }

    /**
     * 페이지별 소요 시간을 모으는 측정 장치.
     *
     * <p>측정 장치가 프로파일별로 나뉘면 한쪽만 재거나 다르게 재는 사고가 난다
     * ({@code MeasurementConfig} 와 같은 이유다).
     *
     * @return 측정 장치
     */
    @Bean
    public PageTimingRecorder pageTimingRecorder() {
        return new PageTimingRecorder();
    }

    /**
     * 순회 결과를 세는 라이터. DB 에는 쓰지 않는다.
     *
     * <p>Step 빌더가 라이터를 {@code StepExecutionListener} 로 자동 등록하므로 프로파일별 구성에서
     * 따로 리스너로 등록하지 않는다.
     *
     * @return 라이터
     */
    @Bean
    public TraversalChecksumItemWriter memberCItemWriter() {
        return new TraversalChecksumItemWriter();
    }
}
