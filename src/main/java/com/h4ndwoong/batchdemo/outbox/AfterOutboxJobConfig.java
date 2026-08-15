package com.h4ndwoong.batchdemo.outbox;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.support.DatabaseWorkloadListener;
import com.h4ndwoong.batchdemo.support.RunIdOnlyIncrementer;
import com.h4ndwoong.batchdemo.support.TableSeededValidator;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.batch.item.support.CompositeItemWriter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.sql.Types;
import java.util.List;

/**
 * 7번 문제(외부 통보와 트랜잭션 경계) <b>after</b> 구성. 발송 요청을 커밋하고, 커밋 이후에 보낸다.
 *
 * <p><b>before 와 다른 것은 라이터 하나와 Step 하나다.</b> Job 이름, 첫 Step 이름, 리더, 프로세서,
 * 청크 크기, 회원 테이블에 나가는 UPDATE, 장애 주입, 측정 장치가 모두 같다.
 * <pre>
 *   before  outboxStep  [읽기 → 전이 → <b>발송</b> + UPDATE]
 *   after   outboxStep  [읽기 → 전이 → <b>outbox INSERT</b> + UPDATE]
 *           relayStep   [PENDING 읽기 → <b>발송</b> + SENT 표시]        ← 커밋 이후
 * </pre>
 *
 * <p><b>라이터를 고쳐서는 풀리지 않는 문제였다.</b> 라이터는 커밋 시점을 알지 못하므로, 그 안에서
 * 무엇을 어떤 순서로 하든 "커밋된 뒤에 보낸다" 를 만들 수 없다. 그래서 개선은 라이터가 아니라
 * <b>Step 구성</b>에서 온다 — 보낼 자리를 하나 더 만드는 것이다. 6번의 개선이 "일을 데이터가 있는
 * 곳에서 하기" 였다면, 7번은 <b>"일을 트랜잭션이 끝난 자리에서 하기"</b> 다.
 *
 * <p><b>Outbox 는 알림을 트랜잭션에 넣지 않는다. 알림을 보내겠다는 약속을 넣는다.</b> 상태 변경과
 * 발송 요청의 적재가 같은 트랜잭션에서 커밋되므로 둘은 함께 참이거나 함께 거짓이다. 커밋이 실패하면
 * 적재도 사라지고, 그래서 보낼 약속 자체가 없다 — <b>유령 알림이 생길 자리가 없다.</b>
 *
 * <p><b>공짜가 아니다. 청구서는 지연과 쓰기량으로 온다.</b> 1~5번의 after 는 모든 항목에서 이겼고,
 * 6번에서 처음으로 지는 항목(락 유지 시간)이 나왔다. 7번의 after 가 내주는 것은 셋이다.
 * <ul>
 *   <li><b>지연</b> — 알림이 즉시 나가지 않는다. 실행 1이 실패하면 커밋된 5만 건의 알림은 다음
 *       실행까지 나가지 못한다. <b>유실이 아니라 지연</b>이며, 그 구분이 이 패턴이 파는 물건이다.</li>
 *   <li><b>쓰기량</b> — 회원 UPDATE 에 더해 Outbox INSERT 와 SENT 표시가 붙는다. 행 기준으로 쓰기가
 *       세 배다. INSERT 는 1번의 교훈({@code rewriteBatchedStatements})으로, 표시는 6번의
 *       교훈(집합 UPDATE)으로 되받는다 — <b>앞의 여섯 문제가 여기서 이자를 낸다.</b></li>
 *   <li><b>exactly-once 가 아니다</b> — 릴레이의 발송과 표시는 원자적이지 않다. 중복의 상한은
 *       릴레이 청크 크기이며, 최종 방어선은 수신자다
 *       ({@link NotificationDispatchingItemWriter} 참고).</li>
 * </ul>
 *
 * <p><b>기대되는 결과</b>
 * <pre>
 *   실행 1 (failAfterCount=F)   FAILED,  상태 F 건 커밋 / outbox F 건 PENDING / <b>발송 0</b>
 *   실행 2 (재실행)             COMPLETED, 남은 것 적재 후 릴레이가 전부 발송
 *
 *   유령 알림     <b>0</b>            적재도 함께 롤백되므로 보낼 약속이 없다
 *   중복 발송     <b>0</b>            릴레이는 PENDING 만 집는다
 *   누적 발송     <b>= 대상 수</b>    정확히 한 번씩
 * </pre>
 *
 * <p><b>실무에서 릴레이는 별도 프로세스다.</b> 여기서 같은 Job 의 두 번째 Step 으로 둔 것은 실습이
 * 한 명령으로 재현 가능해야 하기 때문이다. 그 대가로 성질이 하나 생긴다 — <b>{@code outboxStep} 이
 * 실패하면 릴레이가 아예 돌지 않는다.</b> 커밋된 발송 요청은 다음 실행까지 기다린다. 별도 프로세스
 * 였다면 그 사이에 나갔을 것이고, 그것이 지연을 줄이는 정상적인 운영 형태다.
 *
 * <p><b>실행</b>
 * <pre>{@code
 * # before 를 돌린 뒤라면 반드시 재시딩한다 (before 가 status 를 파괴했다):
 * #   TRUNCATE TABLE member_g; TRUNCATE TABLE member_g_outbox;
 * #   ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.batch.job.name=seedJob target=member_g'
 *
 * ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.profiles.active=after --spring.batch.job.name=outboxJob failAfterCount=50000,java.lang.Long,false'
 * ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.profiles.active=after --spring.batch.job.name=outboxJob'
 *
 * # 부록 - 릴레이 커밋 단위 축 (중복의 상한)
 * ./gradlew bootRun --args='... --spring.profiles.active=after --spring.batch.job.name=outboxJob --outbox.relay-chunk-size=100'
 *
 * # 부록 - 발송 실패 주입 (at-least-once 재현)
 * ./gradlew bootRun --args='... --spring.profiles.active=after --spring.batch.job.name=outboxJob --outbox.send-fail-after=500'
 * }</pre>
 */
@Configuration
@Profile("after")
public class AfterOutboxJobConfig {

    /**
     * 발송 요청을 적재하는 INSERT. <b>상태 UPDATE 와 같은 트랜잭션에서 커밋된다.</b>
     *
     * <p>{@code status} 를 {@link OutboxStatus#PENDING} 으로 못박는 것이 요점이다. 적재 시점에
     * 아직 나가지 않았다는 사실이 데이터에 남고, 릴레이는 그 표시만 보고 집는다.
     */
    private static final String OUTBOX_INSERT_SQL = """
            INSERT INTO member_g_outbox (member_id, event_type, payload, idempotency_key,
                                         status, retry_count, created_at)
            VALUES (:memberId, :eventType, :payload, :idempotencyKey, '%s', 0, :createdAt)"""
            .formatted(OutboxStatus.PENDING);

    /**
     * 아직 나가지 않은 발송 요청을 읽는다.
     *
     * <p>{@code idx_member_g_outbox_poll (status, id)} 이 이 조회의 경로다. 스키마에 이 인덱스가
     * 테이블과 함께 정의되어 있는 이유가 여기 있다 — 1·5·6번의 인덱스와 달리 <b>이것은 before/after
     * 의 비교 대상이 아니라 릴레이가 존재하기 위한 전제</b>다.
     */
    private static final String PENDING_SELECT_SQL = """
            SELECT id, member_id, event_type, payload, idempotency_key, created_at
            FROM member_g_outbox
            WHERE status = '%s'
            ORDER BY id""".formatted(OutboxStatus.PENDING);

    /**
     * 7번 문제 Job. before 와 이름이 같고 리스너 등록 순서의 의미도 같다.
     *
     * <p><b>Step 이 둘인 것이 개선의 전부다.</b> "문제 1개 = Job 1개" 규칙은 서로 다른 문제의 Step 을
     * 한 Job 에 묶지 말라는 뜻이고, 여기 둘은 같은 문제의 앞뒤다. 발송이 트랜잭션 밖으로 나갔다는
     * 사실이 Job 구성에 그대로 드러난다.
     *
     * @param jobRepository    Job 저장소
     * @param outboxStep       상태 전이와 발송 요청 적재 Step
     * @param relayStep        커밋 이후 발송 Step
     * @param workloadListener DB 작업량을 기록하는 리스너
     * @param seededValidator  {@code member_g} 에 데이터가 있는지 확인하는 리스너
     * @param deliveryReporter 알림 전달 지문 리포터
     * @return {@code outboxJob}
     */
    @Bean
    public Job outboxJob(JobRepository jobRepository,
                         Step outboxStep,
                         Step relayStep,
                         DatabaseWorkloadListener workloadListener,
                         @Qualifier("memberGSeededValidator") TableSeededValidator seededValidator,
                         NotificationDeliveryReporter deliveryReporter) {
        return new JobBuilder("outboxJob", jobRepository)
                .incrementer(new RunIdOnlyIncrementer())
                .listener(workloadListener)
                .listener(seededValidator)
                .listener(deliveryReporter)
                .start(outboxStep)
                .next(relayStep)
                .build();
    }

    /**
     * 상태를 전이시키며 발송 요청을 적재하는 Step. before 와 이름이 같아야 같은 축에서 비교할 수 있다.
     *
     * <p>before 의 {@code outboxStep} 과 리더·프로세서·청크 크기·장애 주입이 전부 같다. Step 통계도
     * 같은 값이 나온다 ({@code READ = WRITE = }대상 수). <b>6번과 달리 7번은 Step 통계로 두 프로파일을
     * 구분할 수 없다</b> — 차이가 통계에 잡히지 않는 곳에 있기 때문이다.
     *
     * @param jobRepository        Job 저장소
     * @param transactionManager   트랜잭션 관리자
     * @param memberGItemReader    커서 리더
     * @param memberGItemProcessor 상태 전이 프로세서
     * @param memberGItemWriter    장애 주입기로 감싼 라이터
     * @return {@code outboxStep}
     */
    @Bean
    public Step outboxStep(JobRepository jobRepository,
                           PlatformTransactionManager transactionManager,
                           JdbcCursorItemReader<MemberBase> memberGItemReader,
                           StatusTransitionItemProcessor memberGItemProcessor,
                           FailAfterWriteItemWriter memberGItemWriter) {
        return new StepBuilder("outboxStep", jobRepository)
                .<MemberBase, MemberBase>chunk(OutboxJobCommonConfig.CHUNK_SIZE, transactionManager)
                .reader(memberGItemReader)
                .processor(memberGItemProcessor)
                .writer(memberGItemWriter)
                .build();
    }

    /**
     * 커밋 이후에 실제로 발송하는 Step. <b>after 에만 있다.</b>
     *
     * <p>이 Step 이 시작될 때 {@code outboxStep} 의 트랜잭션은 전부 끝나 있다. 여기서 읽는
     * {@code PENDING} 행은 <b>커밋된 사실</b>이고, 그래서 여기서 보내는 알림은 유령이 될 수 없다.
     *
     * <p><b>청크 크기가 다이얼이다.</b> 발송과 {@code SENT} 표시는 원자적이지 않으므로, 중간에 죽으면
     * 그 청크가 통째로 재발송된다. 작게 잡으면 중복 상한이 낮아지고 커밋 횟수가 늘어난다 — 6번의
     * 슬라이스 크기와 같은 모양의 교환이다.
     *
     * @param jobRepository      Job 저장소
     * @param transactionManager 트랜잭션 관리자
     * @param pendingOutboxReader 미발송 요청 리더
     * @param outboxRelayWriter  발송 라이터
     * @param relayChunkSize     {@code outbox.relay-chunk-size}. <b>재발송 중복의 상한</b>
     * @return {@code relayStep}
     */
    @Bean
    public Step relayStep(JobRepository jobRepository,
                          PlatformTransactionManager transactionManager,
                          JdbcCursorItemReader<OutboxMessage> pendingOutboxReader,
                          NotificationDispatchingItemWriter outboxRelayWriter,
                          @Value("${outbox.relay-chunk-size:"
                                  + OutboxJobCommonConfig.CHUNK_SIZE + "}") int relayChunkSize) {
        return new StepBuilder("relayStep", jobRepository)
                .<OutboxMessage, OutboxMessage>chunk(relayChunkSize, transactionManager)
                .reader(pendingOutboxReader)
                .writer(outboxRelayWriter)
                .build();
    }

    /**
     * 상태 변경과 발송 요청 적재를 한 트랜잭션에 묶는 라이터. <b>after 의 전부가 여기 있다.</b>
     *
     * <p>두 라이터를 순서대로 부를 뿐 새 클래스를 만들지 않는다. <b>원자성은 코드가 아니라 Step
     * 트랜잭션이 만든다</b> — 이 사실이 7번의 요점이므로, 원자성을 책임지는 것처럼 보이는 클래스를
     * 새로 두면 오히려 오해를 심는다. before 의 {@link NotifyingItemWriter} 가 두 줄을 나란히 두고도
     * 원자적이지 않았던 것과 대조된다.
     *
     * @param outboxWriter Outbox 적재 라이터
     * @param statusWriter 상태를 바꾸는 라이터. <b>before 와 같은 빈</b>이다
     * @return 라이터
     */
    @Bean
    public ItemWriter<MemberBase> memberGDelegateWriter(
            @Qualifier("memberGOutboxWriter") JdbcBatchItemWriter<MemberBase> outboxWriter,
            @Qualifier("memberGStatusWriter") JdbcBatchItemWriter<MemberBase> statusWriter) {
        return new CompositeItemWriter<>(List.of(outboxWriter, statusWriter));
    }

    /**
     * 발송 요청을 적재하는 라이터.
     *
     * <p>메시지는 {@link StatusChangedNotification} 이 만든다 — <b>before 가 발송하는 것과 문자 그대로
     * 같은 값</b>이다. 만드는 자리가 하나여야 "같은 알림을 다르게 보냈다" 가 성립한다.
     *
     * @param dataSource 데이터 소스
     * @return 라이터
     */
    @Bean
    public JdbcBatchItemWriter<MemberBase> memberGOutboxWriter(DataSource dataSource) {
        return new JdbcBatchItemWriterBuilder<MemberBase>()
                .dataSource(dataSource)
                .sql(OUTBOX_INSERT_SQL)
                .itemSqlParameterSourceProvider(AfterOutboxJobConfig::toOutboxParameters)
                .build();
    }

    /**
     * 미발송 요청을 {@code id} 순으로 읽는 커서 리더.
     *
     * <p>{@code saveState} 를 끄는 이유는 회원 리더와 같다. 결과셋이 자기 쓰기({@code SENT} 표시)에
     * 따라 줄어들므로 프레임워크의 위치 기억과 함께 쓸 수 없다.
     *
     * @param dataSource 데이터 소스
     * @return 리더
     */
    @Bean
    public JdbcCursorItemReader<OutboxMessage> pendingOutboxReader(DataSource dataSource) {
        return new JdbcCursorItemReaderBuilder<OutboxMessage>()
                .name("pendingOutboxReader")
                .dataSource(dataSource)
                .sql(PENDING_SELECT_SQL)
                .rowMapper(new OutboxMessageRowMapper())
                .fetchSize(OutboxJobCommonConfig.CHUNK_SIZE)
                .verifyCursorPosition(false)
                .saveState(false)
                .build();
    }

    /**
     * 발송하고 {@code SENT} 로 표시하는 라이터.
     *
     * <p>발송기는 before 의 라이터가 쓰는 것과 <b>같은 빈</b>이다. 그래야 발송 수를 한 축에서 잰다.
     *
     * @param sender       알림 발송기
     * @param jdbcTemplate JDBC 템플릿
     * @return 라이터
     */
    @Bean
    public NotificationDispatchingItemWriter outboxRelayWriter(NotificationSender sender,
                                                               JdbcTemplate jdbcTemplate) {
        return new NotificationDispatchingItemWriter(sender,
                new NamedParameterJdbcTemplate(jdbcTemplate),
                OutboxJobCommonConfig.CLOCK);
    }

    /**
     * 회원을 Outbox INSERT 파라미터로 변환한다.
     *
     * @param member 전이가 끝난 회원
     * @return 이름 있는 파라미터 소스
     */
    private static SqlParameterSource toOutboxParameters(MemberBase member) {
        NotificationMessage message = StatusChangedNotification.of(member);
        return new MapSqlParameterSource()
                .addValue("memberId", message.memberId())
                .addValue("eventType", message.eventType())
                .addValue("payload", message.payload())
                .addValue("idempotencyKey", message.idempotencyKey())
                .addValue("createdAt", message.createdAt(), Types.TIMESTAMP);
    }
}
