package com.h4ndwoong.batchdemo.restart;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.domain.MemberE;
import com.h4ndwoong.batchdemo.support.MemberRowMapper;
import com.h4ndwoong.batchdemo.support.TableSeededValidator;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import javax.sql.DataSource;
import java.sql.Types;
import java.time.Clock;

/**
 * 5번 문제에서 <b>before 와 after 가 공유해야 하는</b> 구성.
 *
 * <p>5번 문제의 차이는 하나다 — <b>처리했다는 사실을 데이터에 남기는가</b>. 그 하나의 결정이
 * 세 자리에 나타난다.
 * <ol>
 *   <li>리더의 {@code WHERE} 절과 {@code saveState}</li>
 *   <li>가공 뒤 처리 표시를 세우는가 ({@link ProcessMarkingItemProcessor} 유무)</li>
 *   <li>{@code UPDATE} 문이 {@code processed} / {@code idempotency_key} 를 건드리는가</li>
 * </ol>
 * 셋은 서로 없이 성립하지 않는 <b>한 결정의 세 단면</b>이다. 표시를 세워도 읽기 조건이 보지 않으면
 * 소용이 없고, 읽기 조건만 바꾸면 표시가 서지 않아 아무것도 걸러지지 않는다. 나머지 — 소멸 계산,
 * 청크 크기, 장애 주입, 측정 장치, 시드 확인 — 는 전부 여기 있고 양쪽이 같다.
 *
 * <p><b>이 구성 자체에 프로파일이 걸려 있는 이유</b><br>
 * 장애 주입기가 프로파일별 {@code UPDATE} 라이터를 감싸므로, 프로파일 없이 뜨는 컨텍스트(단위
 * 테스트, 시딩 전용 실행)에서는 조립할 수 없다. 4번의 {@code LookupJobCommonConfig} 와 같은 상황이다.
 *
 * <p><b>Job 파라미터</b>
 * <table border="1">
 *   <caption>파라미터</caption>
 *   <tr><th>이름</th><th>식별</th><th>기본값</th><th>설명</th></tr>
 *   <tr><td>{@code run.id}</td><td><b>식별</b></td><td>incrementer 가 부여</td>
 *       <td>JobInstance 를 가른다. <b>재시작하려면 실패한 실행의 값을 그대로 다시 준다</b></td></tr>
 *   <tr><td>{@code failAfterCount}</td><td><b>비식별</b></td><td>{@code 0} (= 장애 없음)</td>
 *       <td>이 건수를 커밋한 뒤 다음 청크에서 실패시킨다</td></tr>
 * </table>
 *
 * <p><b>{@code failAfterCount} 가 비식별이어야 하는 이유가 곧 5번의 교훈 하나다.</b> JobInstance 의
 * 정체성은 <b>"무엇을 처리할 것인가"</b> 만으로 이루어져야 한다. 장애 주입 지점이나 재시도 설정 같은
 * "어떻게 실행할 것인가" 가 정체성에 섞이면, 그 값을 바꾼 재실행이 재시작이 아니라 <b>새 인스턴스</b>가
 * 되어 버린다. 실무에서 "재시작했는데 처음부터 다시 돌더라" 의 가장 흔한 원인이다.
 * CLI 에서는 {@code DefaultJobParametersConverter} 의 {@code key=value,type,identifying} 문법으로
 * 넘긴다.
 * <pre>{@code
 * failAfterCount=150000,java.lang.Long,false
 * }</pre>
 *
 * <p><b>재시작과 재실행은 다르다.</b> Spring Boot 의 {@code JobLauncherApplicationRunner} 는 CLI 가
 * <em>기존 인스턴스의 식별 파라미터를 그대로 줄 때만</em> 재시작으로 취급한다 (그때 비식별
 * 파라미터는 물려주지 않으므로 장애도 다시 심기지 않는다). 그냥 같은 명령을 다시 치면
 * {@code RunIdOnlyIncrementer} 가 {@code run.id} 를 올려 <b>새 JobInstance</b> 가 되고, 프레임워크는
 * 아무것도 기억하지 못한다. 그 상황에서 데이터만이 기억을 대신할 수 있는가가 5번의 질문이다.
 */
@Configuration
@Profile({"before", "after"})
public class RestartJobCommonConfig {

    /**
     * 커밋 단위. <b>양쪽 공통</b>이며 5번의 관심사가 아니므로 상수다.
     *
     * <p>청크 크기가 조회 횟수를 지배하는가는 4번 문제의 주제였고, 여기서는 "실패 지점 앞뒤로
     * 무엇이 커밋되어 있는가" 만 정한다. {@code failAfterCount} 를 이 값의 배수로 주면 실패 직전까지
     * 커밋된 건수가 정확히 그 값이 된다.
     */
    public static final int CHUNK_SIZE = 1_000;

    /**
     * 한 행에서 소멸시킬 포인트. <b>고정액</b>이다.
     *
     * <p>차감액이 잔액에 비례하면 (예: 10%) 이중 차감의 피해가 행마다 달라져 계산으로 예측할 수
     * 없다. 고정액이면 <b>재실행 한 번의 피해 = 대상 행 수 × 이 값</b> 으로 정확히 떨어진다.
     * 3번 문제가 offset 의 총 스캔량을 산수로 예측한 것과 같은 성격이다.
     */
    public static final long EXPIRE_AMOUNT = 1_000L;

    /**
     * 읽기 SQL 틀. <b>읽는 컬럼과 정렬은 양쪽이 같고 {@code WHERE} 절만 다르다.</b>
     *
     * <p>{@code ORDER BY id} 가 양쪽에 있어야 한다. before 의 재시작은 저장된 행 수만큼 앞을
     * 건너뛰는 방식이라 <b>결과셋의 순서가 실행마다 같다는 전제</b> 위에서만 옳다. 순서를 정하지
     * 않으면 before 는 재시작에서마저 조용히 틀린다.
     */
    public static final String SELECT_SQL_TEMPLATE = """
            SELECT id, email, name, grade, point, status, referrer_id,
                   processed, idempotency_key, created_at, updated_at
            FROM member_e
            WHERE %s
            ORDER BY id""";

    /** {@code updated_at} 의 출처. 2번과 같은 이유로 빈으로 두지 않는다. */
    private static final Clock CLOCK = Clock.systemDefaultZone();

    /**
     * {@code member_e} 를 {@code id} 순으로 읽는 커서 리더를 만든다. <b>양쪽이 이 메서드를 쓴다.</b>
     *
     * <p><b>커서인 이유.</b> 페이징 리더는 3번 문제의 주제이고, 무엇보다 after 의 결과셋은 실행마다
     * 줄어들기 때문에 offset 기반 페이징과 섞으면 어느 쪽이 원인인지 귀속할 수 없다.
     * {@code verifyCursorPosition = false} 인 이유는 2번 문제의 리더에 적었다.
     *
     * <p><b>{@code saveState} 가 이 메서드의 유일한 분기점이다.</b>
     * <ul>
     *   <li>before ({@code true}, 기본값) — 재시작하면 저장된 {@code read.count} 만큼
     *       {@code jumpToItem} 으로 건너뛴다. before 의 조회 조건은 자기가 쓴 값과 무관하므로
     *       결과셋이 변하지 않고, 그래서 <b>재시작에서는 우연히 옳다.</b></li>
     *   <li>after ({@code false}) — 켜 두면 <b>망가진다.</b> {@code processed = 0} 이 결과셋을 실행마다
     *       줄이므로, 15만을 건너뛰라는 지시가 13만 5천 건짜리 결과셋에 적용되어 <b>남은 전부를
     *       건너뛰고 {@code COMPLETED} 로 끝난다.</b> 처리 흔적을 데이터에 남기기로 했다면 프레임워크의
     *       위치 기억은 켜 둘 수 없다 — 둘은 같은 질문에 서로 다른 답을 하는 장치다.</li>
     * </ul>
     *
     * @param dataSource 데이터 소스
     * @param where      대상 선별 조건. <b>코드에 적힌 상수만</b> 넘긴다. 외부 입력은 안 된다
     * @param saveState  재시작 시 읽은 행 수를 복원할지 여부
     * @return 리더
     */
    public static JdbcCursorItemReader<MemberBase> newReader(DataSource dataSource,
                                                            String where,
                                                            boolean saveState) {
        return new JdbcCursorItemReaderBuilder<MemberBase>()
                .name("memberEItemReader")
                .dataSource(dataSource)
                .sql(SELECT_SQL_TEMPLATE.formatted(where))
                .rowMapper(new MemberRowMapper(MemberE::new))
                .fetchSize(CHUNK_SIZE)
                .verifyCursorPosition(false)
                .saveState(saveState)
                .build();
    }

    /**
     * 회원을 UPDATE 파라미터로 변환한다. <b>양쪽 공통</b>이다.
     *
     * <p>before 의 SQL 은 {@code idempotencyKey} 를 쓰지 않지만, 파라미터 소스를 나누지 않는다.
     * 이름 있는 파라미터는 SQL 이 참조하지 않으면 그냥 무시되고, 무엇보다 <b>양쪽이 같은 값을
     * 넘긴다</b>는 사실이 코드에 남는 편이 낫다 — before 가 다른 값을 계산해서 틀리는 것이 아니기
     * 때문이다.
     *
     * @param member 가공된 회원
     * @return 이름 있는 파라미터 소스
     */
    public static SqlParameterSource toParameters(MemberBase member) {
        return new MapSqlParameterSource()
                .addValue("point", member.getPoint())
                .addValue("idempotencyKey", member.getIdempotencyKey(), Types.VARCHAR)
                .addValue("updatedAt", member.getUpdatedAt(), Types.TIMESTAMP)
                .addValue("id", member.getId());
    }

    /**
     * {@code member_e} 에 읽을 데이터가 있는지 확인하는 리스너.
     *
     * <p><b>5번은 "0건 처리 후 {@code COMPLETED}" 가 정답인 유일한 문제다</b> (after 의 재실행이
     * 정확히 그 모양이다). 그래도 이 리스너와 충돌하지 않는다 — 이 리스너는 <em>테이블이 비었는지</em>
     * 를 볼 뿐 처리 건수를 보지 않기 때문이다. 시딩을 잊은 실행과 멱등하게 끝난 실행은 다른 것이고,
     * 여기서 갈린다.
     *
     * @param jdbcTemplate JDBC 템플릿
     * @return 리스너
     */
    @Bean
    public TableSeededValidator memberESeededValidator(JdbcTemplate jdbcTemplate) {
        return new TableSeededValidator(jdbcTemplate, "member_e",
                "5번 문제는 30만 건의 포인트를 소멸시킨 뒤 다시 실행하는 실습이므로 소멸시킬 데이터가 "
                        + "없으면 측정이 성립하지 않는다 (before 의 '두 번 차감' 과 after 의 '한 번만 차감' 이 "
                        + "똑같이 0원이다).");
    }

    /**
     * 포인트를 소멸시키는 프로세서. <b>양쪽이 같은 클래스, 같은 빈을 쓴다.</b>
     *
     * <p>after 는 이 빈을 {@link ProcessMarkingItemProcessor} 로 감싸서 쓴다. 감싸는 쪽이
     * 프로파일 구성에 있으므로, before 의 Step 은 이 빈을 그대로 받는다.
     *
     * @return 프로세서
     */
    @Bean
    public PointExpiryItemProcessor pointExpiryItemProcessor() {
        return new PointExpiryItemProcessor(EXPIRE_AMOUNT, CLOCK);
    }

    /**
     * Step 이 실제로 쓰는 라이터. 장애 주입기가 프로파일별 UPDATE 라이터를 감싼다.
     *
     * <p><b>{@code @StepScope} 인 이유는 두 가지다.</b> 장애 지점을 Job 파라미터에서 읽어야 하고,
     * 커밋 건수 카운터가 Step 실행 하나 안에서만 유지되어야 한다. 싱글턴이면 테스트가 Job 을 여러 번
     * 실행할 때 두 번째부터는 장애가 심어지지 않는다.
     *
     * <p>장애 주입은 <b>before/after 공통</b>이다. 한쪽에만 심으면 "실패한 뒤 재실행" 이라는 같은
     * 상황을 양쪽에 줄 수 없다.
     *
     * @param delegate       프로파일별 UPDATE 라이터
     * @param failAfterCount 이 건수를 커밋한 뒤 실패시킨다. 없으면 장애를 심지 않는다
     * @return 라이터
     */
    @Bean
    @StepScope
    public FailAfterCountItemWriter memberEItemWriter(
            @Qualifier("memberEUpdateWriter") JdbcBatchItemWriter<MemberBase> delegate,
            @Value("#{jobParameters['failAfterCount']}") String failAfterCount) {

        return new FailAfterCountItemWriter(delegate,
                failAfterCount == null ? 0L : Long.parseLong(failAfterCount.trim()));
    }

    /**
     * 잔액 지문을 남기는 측정 장치.
     *
     * @param jdbcTemplate JDBC 템플릿
     * @return 리포터
     */
    @Bean
    public PointBalanceReporter pointBalanceReporter(JdbcTemplate jdbcTemplate) {
        return new PointBalanceReporter(jdbcTemplate);
    }

    /**
     * 멱등키 UNIQUE 제약 DDL 실행기. 생성할지 제거할지는 프로파일별 리스너가 정한다.
     *
     * @param jdbcTemplate JDBC 템플릿
     * @return DDL 실행기
     */
    @Bean
    public MemberEIdempotencyIndex memberEIdempotencyIndex(JdbcTemplate jdbcTemplate) {
        return new MemberEIdempotencyIndex(jdbcTemplate);
    }
}
