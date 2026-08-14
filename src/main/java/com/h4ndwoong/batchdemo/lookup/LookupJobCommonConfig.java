package com.h4ndwoong.batchdemo.lookup;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.domain.MemberD;
import com.h4ndwoong.batchdemo.support.MemberRowMapper;
import com.h4ndwoong.batchdemo.support.TableSeededValidator;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 4번 문제에서 <b>before 와 after 가 공유해야 하는</b> 구성.
 *
 * <p>4번 문제의 차이는 오직 하나다 — <b>추천인을 어떻게 조회하는가</b>({@link ReferrerLookup}).
 * 리더도, 프로세서도, 라이터도, 등급 정책도, 청크 크기도, 측정 장치도 양쪽이 같아야 한다. 그래야
 * "after 가 왕복을 2,000분의 1로 줄였다" 가 다른 변수에 오염되지 않는다. 프로파일별
 * 구성({@link BeforeLookupJobConfig}, {@link AfterLookupJobConfig})에는 <b>조회 전략 빈 하나</b>와
 * Job/Step 선언만 남는다.
 *
 * <p><b>프로세서와 보고자가 여기 있는 이유</b><br>
 * 둘 다 {@link ReferrerLookup} 을 주입받지만, 그 타입의 빈은 활성 프로파일에 하나뿐이므로 공통
 * 구성에서 타입으로 받으면 저절로 해당 프로파일의 전략이 꽂힌다. 프로파일별 구성에 복사해 두면
 * 한쪽만 고쳤을 때 두 실행이 조용히 다른 일을 하게 된다.
 *
 * <p><b>이 구성 자체에 프로파일이 걸려 있는 이유</b><br>
 * 위와 같이 공통 구성이 프로파일 빈({@link ReferrerLookup})에 의존하므로, 프로파일 없이 뜨는
 * 컨텍스트(단위 테스트, 시딩 전용 실행)에서는 조립할 수 없다. 4번 문제의 구성은 before 든 after 든
 * <b>조회 전략이 정해져야만 의미가 있으므로</b> 구성 전체를 두 프로파일로 제한한다. 2·3번의 공통
 * 구성에 프로파일이 없는 것은 그쪽이 프로파일 빈에 의존하지 않기 때문이지, 규칙이 달라서가 아니다.
 */
@Configuration
@Profile({"before", "after"})
public class LookupJobCommonConfig {

    /**
     * 읽기 SQL. <b>양쪽 공통</b>이며 전 컬럼을 읽는다.
     *
     * <p>가공에 필요한 것은 {@code id, point, grade, referrer_id} 뿐이지만, 실무의 배치는 대개 행
     * 전체를 다룬다. 무엇보다 이 SQL 이 양쪽에서 같기만 하면 읽기 비용은 비교 축에 영향을 주지
     * 않는다 — 4번에서 달라지는 것은 <b>읽은 뒤에 무엇을 더 조회하는가</b>다.
     */
    public static final String SELECT_SQL = """
            SELECT id, email, name, grade, point, status, referrer_id,
                   processed, idempotency_key, created_at, updated_at
            FROM member_d
            ORDER BY id""";

    /**
     * {@code limit} 파라미터를 리더의 최대 항목 수로 바꾼다.
     *
     * <p><b>왜 이 파라미터가 있는가</b><br>
     * before 의 50만 건 완주는 100만 번의 왕복이라 오래 걸린다. {@code limit=50000} 이면 앞 5만
     * 건만 처리하는데, <b>왕복 비율(행당 2회 대 청크당 1회)은 그 구간에서 이미 그대로 드러난다.</b>
     * 3번의 {@code pages} 와 같은 장치이고, 양쪽에 같은 값을 주는 한 비교는 공정하다.
     *
     * @param limit 처리할 행 수. {@code null} 이거나 0 이하면 전체
     * @return 리더에 설정할 최대 항목 수
     * @throws NumberFormatException 숫자가 아닌 값이 넘어왔을 때
     */
    public static int maxItemCount(String limit) {
        if (limit == null) {
            return Integer.MAX_VALUE;
        }
        int value = Integer.parseInt(limit.trim());
        return value <= 0 ? Integer.MAX_VALUE : value;
    }

    /**
     * 청크 크기. 프로퍼티 {@code lookup.chunk-size} 로 정하며 기본값은
     * {@link LookupChunkSize#DEFAULT} 다.
     *
     * @param configured 설정값
     * @return 검증된 청크 크기
     */
    @Bean
    public LookupChunkSize lookupChunkSize(
            @Value("${lookup.chunk-size:" + LookupChunkSize.DEFAULT + "}") int configured) {
        return new LookupChunkSize(configured);
    }

    /**
     * {@code member_d} 에 읽을 데이터가 있는지 확인하는 리스너.
     *
     * @param jdbcTemplate JDBC 템플릿
     * @return 리스너
     */
    @Bean
    public TableSeededValidator memberDSeededValidator(JdbcTemplate jdbcTemplate) {
        return new TableSeededValidator(jdbcTemplate, "member_d",
                "4번 문제는 50만 건을 순회하며 행마다 추천인을 조회하는 실습이므로 읽을 데이터가 없으면 "
                        + "측정이 성립하지 않는다 (조회가 0회면 before 의 '행당 2회' 와 after 의 "
                        + "'청크당 1회' 가 똑같이 0회다).");
    }

    /**
     * {@code member_d} 를 {@code id} 순으로 읽는 커서 리더.
     *
     * <p><b>커서인 이유.</b> 페이징 리더를 쓰면 "페이지 = 청크" 라는 구조가 생겨 after 의 일괄
     * 조회를 리더 쪽에서 흉내 낼 수 있게 되는데, 그러면 <b>읽기 경로가 양쪽에서 달라진다.</b>
     * 4번의 개선은 읽기가 아니라 <em>읽은 뒤의 조회</em>에 있으므로 리더는 양쪽 동일한 단순
     * 스트리밍이어야 한다. {@code verifyCursorPosition = false} 인 이유는 2번 문제의 리더에 적었다.
     *
     * <p>{@code @StepScope} 인 이유는 {@code limit} 을 Job 파라미터에서 읽어야 하기 때문이다.
     *
     * @param dataSource     데이터 소스
     * @param lookupChunkSize 청크 크기. {@code fetchSize} 를 여기에 맞춘다
     * @param limit          처리할 행 수. 없으면 전체
     * @return 리더
     */
    @Bean
    @StepScope
    public JdbcCursorItemReader<MemberBase> memberDItemReader(
            DataSource dataSource,
            LookupChunkSize lookupChunkSize,
            @Value("#{jobParameters['limit']}") String limit) {
        JdbcCursorItemReader<MemberBase> reader = new JdbcCursorItemReaderBuilder<MemberBase>()
                .name("memberDItemReader")
                .dataSource(dataSource)
                .sql(SELECT_SQL)
                .rowMapper(new MemberRowMapper(MemberD::new))
                .fetchSize(lookupChunkSize.value())
                .verifyCursorPosition(false)
                .build();
        reader.setMaxItemCount(maxItemCount(limit));
        return reader;
    }

    /**
     * 등급 정책 로더.
     *
     * @param jdbcTemplate JDBC 템플릿
     * @return 로더
     */
    @Bean
    public GradePolicyLoader gradePolicyLoader(JdbcTemplate jdbcTemplate) {
        return new GradePolicyLoader(jdbcTemplate);
    }

    /**
     * 등급을 재산정하는 프로세서. <b>양쪽이 같은 클래스를 쓴다.</b>
     *
     * <p><b>{@code @StepScope} 가 "Step 시작 시 정책 1회 로딩" 을 구현한다.</b> 이 빈은 Step 실행마다
     * 한 번 만들어지므로, 생성 시점에 부르는 {@link GradePolicyLoader#load()} 도 Step 당 한 번이다.
     * 정책을 행마다 다시 읽는 것과의 차이가 여기서 구조로 드러난다 — 로딩 위치가 프로세서 <em>안</em>이
     * 아니라 프로세서를 <em>만드는 곳</em>이다.
     *
     * @param referrerLookup 활성 프로파일의 조회 전략
     * @param gradePolicyLoader 정책 로더
     * @return 프로세서
     */
    @Bean
    @StepScope
    public GradeRecalculatingItemProcessor memberDItemProcessor(ReferrerLookup referrerLookup,
                                                               GradePolicyLoader gradePolicyLoader) {
        return new GradeRecalculatingItemProcessor(referrerLookup, gradePolicyLoader.load());
    }

    /**
     * 산정 결과를 세는 라이터. DB 에는 쓰지 않는다.
     *
     * <p>Step 빌더가 라이터를 {@code StepExecutionListener} 로 자동 등록하므로 프로파일별 구성에서
     * 따로 리스너로 등록하지 않는다.
     *
     * @return 라이터
     */
    @Bean
    public GradeDecisionItemWriter memberDItemWriter() {
        return new GradeDecisionItemWriter();
    }

    /**
     * 조회 계측치를 보고하는 측정 장치.
     *
     * <p>측정 장치가 프로파일별로 나뉘면 한쪽만 재거나 다르게 재는 사고가 난다
     * ({@code MeasurementConfig} 와 같은 이유다).
     *
     * @param referrerLookup  활성 프로파일의 조회 전략
     * @param lookupChunkSize 청크 크기
     * @return 보고자
     */
    @Bean
    public ReferrerLookupReporter referrerLookupReporter(ReferrerLookup referrerLookup,
                                                        LookupChunkSize lookupChunkSize) {
        return new ReferrerLookupReporter(referrerLookup, lookupChunkSize.value());
    }
}
