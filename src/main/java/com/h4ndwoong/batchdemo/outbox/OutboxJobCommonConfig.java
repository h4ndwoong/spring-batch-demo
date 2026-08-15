package com.h4ndwoong.batchdemo.outbox;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.domain.MemberG;
import com.h4ndwoong.batchdemo.domain.MemberStatus;
import com.h4ndwoong.batchdemo.support.MemberRowMapper;
import com.h4ndwoong.batchdemo.support.TableSeededValidator;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
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
 * 7번 문제에서 <b>before 와 after 가 공유해야 하는</b> 구성.
 *
 * <p>7번 문제의 차이는 하나다 — <b>알림을 트랜잭션 안에서 보내는가, 밖에서 보내는가</b>. 대상 선별,
 * 상태 전이, 만들어지는 메시지, 회원 테이블에 나가는 UPDATE, 장애 주입, 측정 장치가 전부 여기 있고
 * 양쪽이 같다. 프로파일별 구성에 남는 것은 <b>그 UPDATE 앞에 무엇이 오는가</b> 하나뿐이다.
 *
 * <pre>
 *   before  발송  →  UPDATE member_g              발송이 트랜잭션 밖에 있다
 *   after   INSERT member_g_outbox  →  UPDATE     발송이 커밋 이후로 밀린다
 * </pre>
 *
 * <p><b>before 는 허수아비가 아니다.</b> 이 구성의 읽기 조건과 쓰기 조건을 보라.
 * <pre>{@code
 *   읽기  WHERE status = 'ACTIVE'
 *   쓰기  UPDATE ... WHERE id = ? AND status = 'ACTIVE'
 * }</pre>
 * 5번에서 배운 process indicator 를 <b>양쪽이 이미 적용한 상태</b>다. 그래서 7번의 DB 는 어느
 * 프로파일에서도 완벽히 멱등하다 — 재실행하면 상태 변경 0행이다. 그런데도 before 는 유령 알림을
 * 내보내고 중복 발송을 한다. <b>5번의 처리 표시는 DB 를 지키지 성 밖을 지키지 않는다.</b> 이것이
 * 7번이 5번과 다른 문제인 이유다.
 *
 * <p><b>이 구성 자체에 프로파일이 걸려 있는 이유</b><br>
 * 장애 주입기가 프로파일별 라이터({@code memberGDelegateWriter})를 감싸므로, 프로파일 없이 뜨는
 * 컨텍스트(단위 테스트, 시딩 전용 실행)에서는 조립할 수 없다. 4·5번과 같은 상황이다.
 *
 * <p><b>Job 파라미터</b>
 * <table border="1">
 *   <caption>파라미터</caption>
 *   <tr><th>이름</th><th>식별</th><th>기본값</th><th>설명</th></tr>
 *   <tr><td>{@code run.id}</td><td><b>식별</b></td><td>incrementer 가 부여</td>
 *       <td>JobInstance 를 가른다. 7번이 다루는 것은 재시작이 아니라 <b>재실행</b>이다</td></tr>
 *   <tr><td>{@code failAfterCount}</td><td><b>비식별</b></td><td>{@code 0} (= 장애 없음)</td>
 *       <td>이 건수를 커밋한 뒤 다음 청크를 <b>쓰고 나서</b> 실패시킨다</td></tr>
 * </table>
 * 비식별이어야 하는 이유는 5번에 적었다 — JobInstance 의 정체성에 "어떻게 실행할 것인가" 가 섞이면
 * 재시작이 조용히 재실행이 된다. CLI 문법은 {@code failAfterCount=50000,java.lang.Long,false} 다.
 *
 * <p><b>프로퍼티</b>
 * <table border="1">
 *   <caption>프로퍼티</caption>
 *   <tr><th>이름</th><th>기본값</th><th>적용</th><th>설명</th></tr>
 *   <tr><td>{@code outbox.relay-chunk-size}</td><td>{@value #CHUNK_SIZE}</td><td>after</td>
 *       <td>릴레이의 커밋 단위. <b>재발송 중복의 상한</b>이다</td></tr>
 *   <tr><td>{@code outbox.send-fail-after}</td><td>{@code 0}</td><td><b>양쪽</b></td>
 *       <td>이 건수를 보낸 뒤 발송을 실패시킨다. 부록 측정이다</td></tr>
 *   <tr><td>{@code outbox.send-fail-times}</td><td>{@code 1}</td><td><b>양쪽</b></td>
 *       <td>발송 실패 횟수. 회복되어야 재발송을 관찰할 수 있다</td></tr>
 * </table>
 */
@Configuration
@Profile({"before", "after"})
public class OutboxJobCommonConfig {

    /** 7번 문제의 대상 테이블. */
    public static final String TABLE = "member_g";

    /** after 전용 Outbox 테이블. before 에서는 한 행도 생기지 않는다. */
    public static final String OUTBOX_TABLE = "member_g_outbox";

    /**
     * 커밋 단위. <b>양쪽 공통</b>이며 7번의 관심사가 아니므로 상수다.
     *
     * <p>다만 이 값이 <b>유령 알림과 중복 발송의 크기</b>를 결정한다. 실패하는 청크 하나가 통째로
     * 유령이 되고, 그 청크가 재실행에서 그대로 중복이 되기 때문이다. 6번에서 청크가 락 단위였다면
     * 7번에서 청크는 <b>사고의 단위</b>다.
     */
    public static final int CHUNK_SIZE = 1_000;

    /** 전이 전 상태. 시드의 약 95% 가 여기 해당한다. */
    public static final MemberStatus FROM_STATUS = MemberStatus.ACTIVE;

    /** 전이 후 상태. */
    public static final MemberStatus TO_STATUS = MemberStatus.DORMANT;

    /**
     * 대상 선별 조건.
     *
     * <p>배치가 <b>자기가 쓰는 컬럼으로</b> 대상을 고른다. 5번 after 의 {@code processed = 0} 과 같은
     * 구조이고, 그래서 재실행하면 읽을 것이 없다. <b>DB 쪽 멱등성은 여기서 이미 끝나 있다.</b>
     */
    public static final String WHERE = "status = 'ACTIVE'";

    private static final String SELECT_SQL = """
            SELECT id, email, name, grade, point, status, referrer_id,
                   processed, idempotency_key, created_at, updated_at
            FROM member_g
            WHERE %s
            ORDER BY id""".formatted(WHERE);

    /**
     * 회원의 상태를 바꾸는 UPDATE. <b>양쪽이 문자 그대로 같은 문장을 보낸다.</b>
     *
     * <p>{@code AND status = 'ACTIVE'} 는 두 번째 방어선이다. 이미 전이된 행이 여기까지 오면 이
     * UPDATE 는 0행을 갱신하고, {@code JdbcBatchItemWriter} 의 기본 설정({@code assertUpdates = true})
     * 이 그것을 조용히 넘기지 않고 실패시킨다 (5번과 같은 판단).
     *
     * <p><b>이 문장이 양쪽 같다는 사실이 7번의 출발점이다.</b> DB 에 대해서는 두 프로파일이 완전히
     * 같은 일을 한다. 그런데 결과는 다르다 — 다른 것은 DB 에 적히지 않는 쪽에서 일어난다.
     */
    public static final String UPDATE_SQL = """
            UPDATE member_g
            SET status = :status, updated_at = :updatedAt
            WHERE id = :id AND status = 'ACTIVE'""";

    /**
     * {@code updated_at} 의 출처. 2·5·6번과 같은 이유로 빈으로 두지 않는다.
     *
     * <p>7번에서는 이유가 하나 더 있다. 이 시각이 그대로 <b>알림의 시각</b>이 되므로
     * ({@link StatusChangedNotification}), 시계를 나누면 before 와 after 가 서로 다른 메시지를 만든다.
     */
    public static final Clock CLOCK = Clock.systemDefaultZone();

    /**
     * {@code member_g} 에 읽을 데이터가 있는지 확인하는 리스너.
     *
     * <p><b>7번에서 이 방어가 특히 필요하다.</b> 빈 테이블에서 돌리면 발송 0건, 유령 0건, 중복 0건
     * 으로 끝나는데 <b>그것은 after 가 성공한 모습과 글자 그대로 같다.</b> 5번에서 "0건 처리 후
     * COMPLETED" 가 개선의 증거였던 것과 같은 함정이며, 그 구분은 테이블이 비었는지로만 지을 수 있다.
     *
     * @param jdbcTemplate JDBC 템플릿
     * @return 리스너
     */
    @Bean
    public TableSeededValidator memberGSeededValidator(JdbcTemplate jdbcTemplate) {
        return new TableSeededValidator(jdbcTemplate, TABLE,
                "7번 문제는 10만 건의 상태를 바꾸며 알림을 보내는 실습이므로 대상이 없으면 측정이 "
                        + "성립하지 않는다 (before 의 '유령 알림' 과 after 의 '유령 없음' 이 똑같이 0건이다).");
    }

    /**
     * 나간 알림을 모으는 기록기. <b>7번의 주 측정 장치</b>이며 싱글턴이다.
     *
     * <p>Step 마다 비우지 않는 이유는 {@link NotificationRecorder} 에 적었다 — 중복 발송은 실행을
     * 가로질러야만 보인다.
     *
     * @return 기록기
     */
    @Bean
    public NotificationRecorder notificationRecorder() {
        return new NotificationRecorder();
    }

    /**
     * 알림 발송기. <b>before 의 라이터와 after 의 릴레이가 같은 빈을 받는다.</b>
     *
     * <p>같은 빈이어야 발송 수를 한 축에서 잰다. 발송 장애 주입기가 감싸고 있지만 기본값에서는
     * 그냥 위임한다.
     *
     * @param recorder       기록기
     * @param failAfterSends {@code outbox.send-fail-after}. 부록 측정용 발송 장애 지점
     * @param failTimes      {@code outbox.send-fail-times}. 실패 횟수
     * @return 발송기
     */
    @Bean
    public NotificationSender notificationSender(
            NotificationRecorder recorder,
            @Value("${outbox.send-fail-after:0}") long failAfterSends,
            @Value("${outbox.send-fail-times:1}") int failTimes) {
        return new FaultInjectingNotificationSender(
                new LoggingNotificationSender(recorder), failAfterSends, failTimes);
    }

    /**
     * DB 의 상태와 나간 알림을 대조하는 측정 장치.
     *
     * @param jdbcTemplate JDBC 템플릿
     * @param recorder     기록기
     * @return 리포터
     */
    @Bean
    public NotificationDeliveryReporter notificationDeliveryReporter(JdbcTemplate jdbcTemplate,
                                                                     NotificationRecorder recorder) {
        return new NotificationDeliveryReporter(jdbcTemplate, recorder);
    }

    /**
     * 전이 대상 회원을 {@code id} 순으로 읽는 커서 리더. <b>양쪽이 같은 빈을 쓴다.</b>
     *
     * <p><b>{@code saveState} 를 끈다.</b> 5번 after 와 같은 이유다 — 읽기 조건이 자기가 쓰는 컬럼을
     * 보므로 결과셋이 실행마다 짧아지고, 그러면 프레임워크가 저장해 둔 "몇 번째까지 읽었다" 는 다른
     * 결과셋의 인덱스가 되어 남은 행을 통째로 건너뛴다. 7번이 다루는 것은 재시작이 아니라 재실행
     * 이므로 잃는 것도 없다.
     *
     * <p>커서인 이유는 3~6번과 같다. 페이징은 3번의 주제이고 여기서 섞으면 비교 축에 끼어든다.
     *
     * @param dataSource 데이터 소스
     * @return 리더
     */
    @Bean
    public JdbcCursorItemReader<MemberBase> memberGItemReader(DataSource dataSource) {
        return new JdbcCursorItemReaderBuilder<MemberBase>()
                .name("memberGItemReader")
                .dataSource(dataSource)
                .sql(SELECT_SQL)
                .rowMapper(new MemberRowMapper(MemberG::new))
                .fetchSize(CHUNK_SIZE)
                .verifyCursorPosition(false)
                .saveState(false)
                .build();
    }

    /**
     * 상태를 전이시키는 프로세서. <b>양쪽이 같은 빈을 쓴다.</b>
     *
     * @return 프로세서
     */
    @Bean
    public StatusTransitionItemProcessor memberGItemProcessor() {
        return new StatusTransitionItemProcessor(FROM_STATUS, TO_STATUS, CLOCK);
    }

    /**
     * 회원의 상태를 바꾸는 라이터. <b>양쪽이 같은 빈을 쓴다.</b>
     *
     * <p>프로파일별 구성은 이 라이터를 <b>무엇으로 감싸는가</b>만 정한다. before 는 발송기로,
     * after 는 Outbox 적재 라이터로 감싼다.
     *
     * @param dataSource 데이터 소스
     * @return 라이터
     */
    @Bean
    public JdbcBatchItemWriter<MemberBase> memberGStatusWriter(DataSource dataSource) {
        return new JdbcBatchItemWriterBuilder<MemberBase>()
                .dataSource(dataSource)
                .sql(UPDATE_SQL)
                .itemSqlParameterSourceProvider(OutboxJobCommonConfig::toStatusParameters)
                .build();
    }

    /**
     * Step 이 실제로 쓰는 라이터. 장애 주입기가 프로파일별 라이터를 감싼다.
     *
     * <p><b>{@code @StepScope} 인 이유는 5번과 같다.</b> 장애 지점을 Job 파라미터에서 읽어야 하고,
     * 커밋 건수 카운터가 Step 실행 하나 안에서만 유지되어야 한다.
     *
     * <p>장애 주입은 <b>before/after 공통</b>이다. 한쪽에만 심으면 "커밋 직전에 죽었다" 는 같은
     * 상황을 양쪽에 줄 수 없다.
     *
     * @param delegate       프로파일별 라이터
     * @param failAfterCount 이 건수를 커밋한 뒤 다음 청크를 쓰고 나서 실패시킨다
     * @return 라이터
     */
    @Bean
    @StepScope
    public FailAfterWriteItemWriter memberGItemWriter(
            @Qualifier("memberGDelegateWriter") ItemWriter<MemberBase> delegate,
            @Value("#{jobParameters['failAfterCount']}") String failAfterCount) {

        return new FailAfterWriteItemWriter(delegate,
                failAfterCount == null ? 0L : Long.parseLong(failAfterCount.trim()));
    }

    /**
     * 회원을 상태 UPDATE 파라미터로 변환한다.
     *
     * @param member 전이가 끝난 회원
     * @return 이름 있는 파라미터 소스
     */
    public static SqlParameterSource toStatusParameters(MemberBase member) {
        return new MapSqlParameterSource()
                .addValue("status", member.getStatus().name())
                .addValue("updatedAt", member.getUpdatedAt(), Types.TIMESTAMP)
                .addValue("id", member.getId());
    }
}
