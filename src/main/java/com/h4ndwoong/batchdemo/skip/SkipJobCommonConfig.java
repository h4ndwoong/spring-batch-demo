package com.h4ndwoong.batchdemo.skip;

import com.h4ndwoong.batchdemo.domain.MemberB;
import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.support.MemberRowMapper;
import com.h4ndwoong.batchdemo.support.TableSeededValidator;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import javax.sql.DataSource;
import java.sql.Types;
import java.time.Clock;

/**
 * 2번 문제에서 <b>before 와 after 가 공유해야 하는</b> 구성.
 *
 * <p>2번 문제의 차이는 오직 하나다 — <b>Step 이 오류를 견디는가</b>. 리더도 프로세서도 라이터도
 * 청크 크기도 양쪽이 같아야 한다. 그래야 "after 가 빠른 게 아니라 after 는 끝까지 간다" 는 결론이
 * 다른 변수에 오염되지 않는다. 프로파일별 구성({@link BeforeSkipJobConfig},
 * {@link AfterSkipJobConfig})에는 {@code faultTolerant()} 블록과 스킵 리스너 등록만 남는다.
 */
@Configuration
public class SkipJobCommonConfig {

    /**
     * 커밋 단위. <b>양쪽 공통</b>이며 이 값이 100인 데에는 이유가 있다.
     *
     * <p>시드 데이터는 200번째 행마다 오염되어 있다. 청크가 100이면 <b>첫 청크(1~100)는 커밋되고
     * 두 번째 청크(101~200)에서 죽는다.</b> before 의 진짜 증상은 "실패했다" 가 아니라
     * "10만 건 중 100건만 반영된 채 멈췄고, 어디까지 처리됐는지 코드를 읽어야 안다" 이므로
     * 커밋된 청크가 최소한 하나는 있어야 재현된다. 500이나 1000으로 잡으면 첫 청크에서 죽어
     * 한 행도 커밋되지 않아 다른 이야기가 된다.
     */
    public static final int CHUNK_SIZE = 100;

    /**
     * 처리 시각의 출처. 실행 환경에서는 시스템 시계이며, 테스트는 고정 시계를 직접 넘긴다.
     *
     * <p>빈으로 등록하지 않는 이유는 다른 문제의 구성이 각자의 시계를 갖게 될 수 있어서다. 시계가
     * 전역 빈이 되면 6번 문제에서 {@code updated_at} 을 고정하려 할 때 2번의 구성까지 영향을 받는다.
     */
    private static final Clock CLOCK = Clock.systemDefaultZone();

    private static final String SELECT_SQL = """
            SELECT id, email, name, grade, point, status, referrer_id,
                   processed, idempotency_key, created_at, updated_at
            FROM member_b
            ORDER BY id""";

    /**
     * 가공 결과를 반영하는 UPDATE. {@code processed} 와 {@code updated_at} 만 건드린다.
     *
     * <p>2번 문제의 관심사는 쓰기 경로의 비용이 아니라 오류 처리이므로, 쓰기는 양쪽 프로파일에서
     * 문자 그대로 같은 문장이어야 한다.
     */
    private static final String UPDATE_SQL = """
            UPDATE member_b
            SET processed = :processed, updated_at = :updatedAt
            WHERE id = :id""";

    /**
     * {@code member_b} 에 읽을 데이터가 있는지 확인하는 리스너.
     *
     * <p>같은 판단을 하는 리스너가 3번({@code member_c})·4번({@code member_d})에도 있어
     * {@link TableSeededValidator} 로 뽑았다. 여기 남는 것은 <b>테이블과 실패 메시지</b>뿐이다.
     *
     * @param jdbcTemplate JDBC 템플릿
     * @return 리스너
     */
    @Bean
    public TableSeededValidator memberBSeededValidator(JdbcTemplate jdbcTemplate) {
        return new TableSeededValidator(jdbcTemplate, "member_b",
                "2번 문제는 오염 행이 섞인 10만 건을 읽는 실습이므로 읽을 데이터가 없으면 측정이 "
                        + "성립하지 않는다 (0건 처리도 COMPLETED 로 끝나서 성공과 구분되지 않는다).");
    }

    /**
     * {@code member_b} 를 {@code id} 순으로 읽는 커서 리더.
     *
     * <p><b>커서인 이유.</b> 기본값({@code useSharedExtendedConnection = false})에서 커서는 Step
     * 트랜잭션과 <em>다른 연결</em>을 쓴다. 그래서 스킵이나 재시도로 청크가 롤백되어도 커서가
     * 되감기지 않는다. 페이징의 함정은 3번 문제의 주제이므로 여기서 끌어오지 않는다.
     *
     * <p><b>{@code verifyCursorPosition = false} 인 이유</b><br>
     * Spring Batch 는 기본적으로 매 행마다 {@code ResultSet.getRow()} 로 커서가 예상 위치에
     * 있는지 확인한다. {@code RowMapper} 가 실수로 커서를 움직이는 것을 잡기 위한 장치다.
     * 그런데 {@code fetchSize} 를 준 MariaDB 드라이버는 결과를 <em>스트리밍</em>으로 내려주고,
     * 스트리밍 결과셋은 {@code getRow()} 를 지원하지 않아 첫 행부터
     * {@code Unexpected cursor position change} 로 죽는다. 이 프로젝트의
     * {@link MemberRowMapper} 는 커서를 움직이지 않으므로 확인을 끄는 쪽이 맞다.
     * 10만 건을 드라이버 메모리에 한 번에 올리지 않는 편이 2번 문제의 관측(오류 처리 비용)에도
     * 유리하다.
     *
     * @param dataSource 데이터 소스
     * @return 리더
     */
    @Bean
    public JdbcCursorItemReader<MemberBase> memberBItemReader(DataSource dataSource) {
        return new JdbcCursorItemReaderBuilder<MemberBase>()
                .name("memberBItemReader")
                .dataSource(dataSource)
                .sql(SELECT_SQL)
                .rowMapper(new MemberRowMapper(MemberB::new))
                .fetchSize(CHUNK_SIZE)
                .verifyCursorPosition(false)
                .build();
    }

    /**
     * 검증기.
     *
     * @return 검증기
     */
    @Bean
    public MemberValidator memberValidator() {
        return new MemberValidator();
    }

    /**
     * 검증 후 처리 완료로 표시하는 프로세서. 오염 행에서 예외를 던지는 유일한 지점이다.
     *
     * @param memberValidator 검증기
     * @return 프로세서
     */
    @Bean
    public MemberValidatingItemProcessor memberBItemProcessor(MemberValidator memberValidator) {
        return new MemberValidatingItemProcessor(memberValidator, CLOCK);
    }

    /**
     * 실제 UPDATE 를 보내는 라이터.
     *
     * @param dataSource 데이터 소스
     * @return 라이터
     */
    @Bean
    public JdbcBatchItemWriter<MemberBase> memberBUpdateWriter(DataSource dataSource) {
        return new JdbcBatchItemWriterBuilder<MemberBase>()
                .dataSource(dataSource)
                .sql(UPDATE_SQL)
                .itemSqlParameterSourceProvider(SkipJobCommonConfig::toParameters)
                .build();
    }

    /**
     * Step 이 실제로 쓰는 라이터. 장애 주입기가 실제 라이터를 감싼다.
     *
     * <p><b>{@code @StepScope} 인 이유는 두 가지다.</b> 장애 주입 파라미터를 Job 파라미터에서
     * 읽어야 하고, 실패 횟수 카운터가 Step 실행 하나 안에서만 유지되어야 한다. 싱글턴이면 테스트가
     * Job 을 두 번 실행할 때 두 번째 실행에서는 장애가 심어지지 않는다.
     *
     * <p>장애 주입 파라미터는 <b>before/after 공통</b>이다. 한쪽에만 장애를 심으면 비교가 성립하지
     * 않는다. 기본값은 "장애 없음" 이므로 평소 실행은 영향을 받지 않는다.
     *
     * @param delegate   실제 UPDATE 라이터
     * @param faultAtId  장애를 심을 청크의 첫 행 식별자. 없으면 장애를 심지 않는다
     * @param faultTimes 실패시킬 횟수. 없으면 {@code 2} (after 의 {@code retryLimit = 3} 안에서 회복된다)
     * @param faultKind  장애 종류. 없으면 {@link FaultKind#TRANSIENT}
     * @return 라이터
     */
    @Bean
    @StepScope
    public FaultInjectingItemWriter memberBItemWriter(
            @Qualifier("memberBUpdateWriter") JdbcBatchItemWriter<MemberBase> delegate,
            @Value("#{jobParameters['faultAtId']}") String faultAtId,
            @Value("#{jobParameters['faultTimes']}") String faultTimes,
            @Value("#{jobParameters['faultKind']}") String faultKind) {

        return new FaultInjectingItemWriter(
                delegate,
                faultAtId == null ? 0L : Long.parseLong(faultAtId),
                faultTimes == null ? 2 : Integer.parseInt(faultTimes),
                faultKind == null ? FaultKind.TRANSIENT : FaultKind.valueOf(faultKind.trim().toUpperCase()));
    }

    /**
     * 회원을 UPDATE 파라미터로 변환한다.
     *
     * <p>{@code updated_at} 은 프로세서가 채운다. {@code null} 이 넘어온다면 프로세서를 거치지 않은
     * 항목이라는 뜻이므로 SQL 타입을 명시해 드라이버가 추론하지 않게 한다.
     *
     * @param member 가공된 회원
     * @return 이름 있는 파라미터 소스
     */
    private static SqlParameterSource toParameters(MemberBase member) {
        return new MapSqlParameterSource()
                .addValue("processed", member.isProcessed())
                .addValue("updatedAt", member.getUpdatedAt(), Types.TIMESTAMP)
                .addValue("id", member.getId());
    }
}
