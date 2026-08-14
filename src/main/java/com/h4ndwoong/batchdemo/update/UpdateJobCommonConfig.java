package com.h4ndwoong.batchdemo.update;

import com.h4ndwoong.batchdemo.support.GradePolicyLoader;
import com.h4ndwoong.batchdemo.support.TableSeededValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

/**
 * 6번 문제에서 <b>before 와 after 가 공유해야 하는</b> 구성.
 *
 * <p>6번 문제의 차이는 하나다 — <b>같은 갱신을 몇 번의 왕복에 나눠 담는가</b>. 등급 규칙도, 대상
 * 범위도, 갱신할 행도, 측정 장치도 양쪽이 같아야 "왕복만 줄었다" 가 성립한다. 프로파일별 구성에
 * 남는 것은 읽기와 쓰기의 <b>모양</b>뿐이다.
 *
 * <pre>
 *   before  회원 100만 행을 읽어  →  행마다 UPDATE ... WHERE id = ?        왕복 ≈ 갱신 행 수
 *   after   id 구간 20개를 읽어   →  구간마다 집합 UPDATE 한 문장          왕복 = 슬라이스 수
 * </pre>
 *
 * <p><b>1~5번과 다른 점: after 가 모든 항목에서 이기지 않는다.</b> 집합 UPDATE 는 왕복을 없애는
 * 대신 락 단위를 청크(1,000행)에서 슬라이스(기본 5만행)로 키운다. 그래서 6번에는 <b>다이얼</b>이
 * 하나 있고({@code update.slice-size}), 최적점은 "가능한 최대" 가 아니라 왕복 곡선과 락 곡선이
 * 교차하는 지점이다.
 *
 * <p><b>측정 축</b>
 * <table border="1">
 *   <caption>프로퍼티</caption>
 *   <tr><th>이름</th><th>기본값</th><th>적용</th><th>설명</th></tr>
 *   <tr><td>{@code update.slice-size}</td><td>{@value #DEFAULT_SLICE_SIZE}</td><td>after</td>
 *       <td>슬라이스 하나가 덮을 {@code id} 개수. {@code 0} 이면 한 문장이 전 구간을 잠근다</td></tr>
 *   <tr><td>{@code update.grade-point-index}</td><td>{@code false}</td><td><b>양쪽</b></td>
 *       <td>{@code (grade, point)} 인덱스를 둔 상태로 잴지 여부. 부록 측정이다</td></tr>
 * </table>
 *
 * <p>Job 파라미터는 {@code run.id} 하나뿐이다. 이 배치는 자연 멱등이라 (같은 포인트는 같은 등급)
 * 몇 번을 돌려도 결과가 같고, 두 번째 실행부터는 갱신할 행이 없어 즉시 끝난다. 5번처럼 재시작을
 * 다룰 필요가 없는 이유다.
 */
@Configuration
public class UpdateJobCommonConfig {

    /** 6번 문제의 대상 테이블. */
    public static final String TABLE = "member_f";

    /**
     * before 의 커밋 단위. <b>6번의 관심사가 아니므로 상수</b>다 (5번과 같은 판단).
     *
     * <p>다만 이 값이 before 의 락 단위이기도 하다. after 의 슬라이스와 비교되는 자리이므로 1,000 —
     * 즉 "흔한 배치의 기본값" — 으로 둔다.
     */
    public static final int CHUNK_SIZE = 1_000;

    /**
     * after 의 기본 슬라이스 크기. 100만 건이면 20개로 잘린다.
     *
     * <p>왕복 20회는 before 의 75만 회에 비하면 사실상 0이고, 문장 하나가 잠그는 것은 전체의
     * 1/20 이다. 이 값을 0으로 두면 왕복은 1회가 되지만 <b>100만 행이 한 트랜잭션에 잠긴다.</b>
     */
    public static final long DEFAULT_SLICE_SIZE = 50_000L;

    /**
     * {@code updated_at} 의 출처. 2·5번과 같은 이유로 빈으로 두지 않는다.
     *
     * <p>양쪽이 같은 시계를 쓴다는 사실이 중요하다. 체크섬은 {@code updated_at} 의 값이 아니라
     * {@code NULL} 여부만 세지만, 시계를 나누면 "언제 찍힌 값인가" 가 프로파일마다 달라진다.
     */
    public static final Clock CLOCK = Clock.systemDefaultZone();

    /**
     * {@code member_f} 에 읽을 데이터가 있는지 확인하는 리스너.
     *
     * <p><b>6번에서 이 방어가 특히 필요하다.</b> after 는 빈 테이블에서도 "슬라이스 0개 처리 후
     * {@code COMPLETED}" 로 끝나는데, 그것은 개선의 모습과 <b>글자 그대로 같다</b> (재실행이 정확히
     * 그 모양이다). 시딩을 잊은 실행과 할 일이 없던 실행은 다르고, 그 구분은 테이블이 비었는지로만
     * 지을 수 있다.
     *
     * @param jdbcTemplate JDBC 템플릿
     * @return 리스너
     */
    @Bean
    public TableSeededValidator memberFSeededValidator(JdbcTemplate jdbcTemplate) {
        return new TableSeededValidator(jdbcTemplate, TABLE,
                "6번 문제는 100만 건의 등급을 재계산하며 쓰기 경로를 비교하는 실습이므로 갱신할 데이터가 "
                        + "없으면 측정이 성립하지 않는다 (before 의 '행마다 왕복' 과 after 의 "
                        + "'슬라이스마다 왕복' 이 똑같이 0회다).");
    }

    /**
     * 등급 정책 로더. <b>양쪽이 같은 분포에서 같은 임계값을 얻는다.</b>
     *
     * @param jdbcTemplate JDBC 템플릿
     * @return 로더
     */
    @Bean
    public GradePolicyLoader memberFGradePolicyLoader(JdbcTemplate jdbcTemplate) {
        return new GradePolicyLoader(jdbcTemplate, TABLE);
    }

    /**
     * 등급 분포 지문을 남기는 측정 장치.
     *
     * @param jdbcTemplate JDBC 템플릿
     * @return 리포터
     */
    @Bean
    public GradeRecalcReporter gradeRecalcReporter(JdbcTemplate jdbcTemplate) {
        return new GradeRecalcReporter(jdbcTemplate);
    }

    /**
     * {@code (grade, point)} 인덱스 DDL 실행기.
     *
     * @param jdbcTemplate JDBC 템플릿
     * @return DDL 실행기
     */
    @Bean
    public MemberFGradePointIndex memberFGradePointIndex(JdbcTemplate jdbcTemplate) {
        return new MemberFGradePointIndex(jdbcTemplate);
    }

    /**
     * 인덱스 상태를 설정대로 맞추는 리스너. <b>프로파일과 직교하는 측정 축</b>이다.
     *
     * @param index   DDL 실행기
     * @param enabled {@code update.grade-point-index}. 기본은 인덱스 없는 상태
     * @return 리스너
     */
    @Bean
    public GradePointIndexListener gradePointIndexListener(
            MemberFGradePointIndex index,
            @Value("${update.grade-point-index:false}") boolean enabled) {
        return new GradePointIndexListener(index, enabled);
    }
}
