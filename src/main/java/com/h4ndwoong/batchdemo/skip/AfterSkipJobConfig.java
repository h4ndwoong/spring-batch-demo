package com.h4ndwoong.batchdemo.skip;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.support.DatabaseWorkloadListener;
import com.h4ndwoong.batchdemo.support.TableSeededValidator;
import com.h4ndwoong.batchdemo.support.RunIdOnlyIncrementer;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.SkipListener;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

/**
 * 2번 문제(skip/retry, 오류 행 격리) <b>after</b> 구성. before 와 <em>같은 데이터</em>를 읽지만
 * 오류를 <em>분류해서</em> 다룬다.
 *
 * <p><b>예외를 세 부류로 나눈다.</b> 이것이 이 문제의 전부다.
 * <ol>
 *   <li><b>데이터 오류</b>({@link MemberValidationException}) → {@code skip}. 행 하나가 오염된 것이
 *       나머지 99,999건을 막을 이유가 되지 않는다. 대신 {@link ErrorRowIsolatingSkipListener} 가
 *       {@code member_b_error} 에 격리해 <b>어느 행이 왜 빠졌는지</b>를 남긴다.</li>
 *   <li><b>일시 오류</b>({@link TransientDataAccessException}) → {@code retry}. 데이터는 멀쩡하고
 *       인프라가 잠깐 흔들린 것이므로 스킵하면 정상 행을 잃는다. {@value #RETRY_LIMIT}번까지 다시 해 본다.</li>
 *   <li><b>그 밖의 전부</b> → 실패. 스킵 목록을 {@code Exception} 으로 넓히는 순간 코드 버그도
 *       데이터 오류로 둔갑해, 배치가 절반을 버리고도 {@code COMPLETED} 로 끝난다.</li>
 * </ol>
 *
 * <p><b>스킵은 그 자체로 개선이 아니다.</b> {@code .skip()} 만 붙이면 Step 은 초록불로 끝나지만
 * 500건이 조용히 사라진다. 실패는 눈에 띄고 유실은 눈에 띄지 않으므로, 이쪽이 before 보다 나쁠 수도
 * 있다. 격리 리스너까지 붙여야 "건너뛰었다" 가 "격리했고 추적할 수 있다" 가 된다.
 *
 * <p><b>{@value #SKIP_LIMIT} 이라는 상한의 의미</b><br>
 * 오염이 이 수를 넘으면 Step 은 실패한다. 개별 행의 문제가 아니라 <b>데이터 소스 자체가 깨졌다</b>는
 * 신호이기 때문이다. 상한 없는 스킵은 "전부 실패해도 성공으로 끝나는 배치" 와 같은 말이다.
 *
 * <p><b>공짜가 아니다 — 다만 청구서는 예상과 다른 곳으로 온다.</b> 1000건·오염 5건으로 실측한
 * 결과는 이렇다 ({@code AfterSkipJobTest} 가 이 값을 고정한다).
 * <pre>
 *   commit = 11 (청크 10 + 1)   ← 스킵이 없을 때와 같다
 *   rollback = 5                ← 오염 1건 = 청크 롤백 1회
 *   read = 1000                 ← 재처리는 캐시된 청크로 하므로 다시 읽지 않는다
 * </pre>
 * 가공 단계에서 스킵이 나면 그 청크는 <b>통째로 롤백된 뒤 다시 처리</b>된다. 커밋 횟수가 늘지
 * 않는 이유는 재처리된 청크가 결국 한 번 커밋되기 때문이다. 즉 대가는 커밋 폭증이 아니라
 * <b>롤백 한 번과 최대 {@value SkipJobCommonConfig#CHUNK_SIZE}건의 헛된 재검증</b>이다.
 * 오염이 200행마다 있고 청크가 100이므로 청크의 절반이 이 경로를 탄다.
 *
 * <p>이 비용을 더 줄이는 다음 지렛대는 {@code .noRollback(MemberValidationException.class)} 다.
 * 검증 실패는 트랜잭션을 더럽히지 않으므로 롤백 없이 그 행만 건너뛸 수 있다. 여기서는 쓰지
 * 않는다 — 프로세서가 언젠가 부수 효과를 갖게 되면 조용히 틀리는 설정이고, 2번 문제의 목적은
 * 우선 <b>롤백과 재처리라는 대가를 눈으로 보는 것</b>이기 때문이다.
 *
 * <p><b>실행</b>
 * <pre>{@code
 * # before 를 돌린 뒤라면 처리 표시를 되돌린다: UPDATE member_b SET processed = 0, updated_at = NULL;
 * ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.profiles.active=after --spring.batch.job.name=skipJob'
 *
 * # 재시도 경로 확인 (50001번 행이 속한 청크의 쓰기를 2번 실패시킨다 → 재시도로 회복)
 * ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.profiles.active=after --spring.batch.job.name=skipJob' faultAtId=50001 faultTimes=2
 *
 * # 분류되지 않은 예외는 스킵되지 않는다 (Step 이 실패해야 정상이다)
 * ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.profiles.active=after --spring.batch.job.name=skipJob' faultAtId=50001 faultKind=FATAL
 * }</pre>
 *
 * <p><b>측정</b>
 * <pre>{@code
 * SELECT s.STEP_NAME, s.STATUS, s.READ_COUNT, s.WRITE_COUNT,
 *        s.PROCESS_SKIP_COUNT, s.ROLLBACK_COUNT, s.COMMIT_COUNT
 * FROM BATCH_STEP_EXECUTION s ORDER BY s.STEP_EXECUTION_ID DESC;
 *
 * -- 대사: 읽은 수 = 쓴 수 + 스킵 수, 스킵 수 = 격리 행 수
 * SELECT step_execution_id, phase, COUNT(*) FROM member_b_error GROUP BY 1, 2;
 * SELECT SUBSTRING_INDEX(message, ':', 1) AS rule, COUNT(*)
 * FROM member_b_error GROUP BY 1;   -- EMAIL_FORMAT 250 / NEGATIVE_POINT 250
 * }</pre>
 */
@Configuration
@Profile("after")
public class AfterSkipJobConfig {

    /**
     * 스킵 상한. 이 수를 넘으면 데이터 소스를 의심해야 한다.
     *
     * <p>10만 건 중 오염 500건을 예상하는 실습에서 1000은 "예상의 두 배까지는 견딘다" 는 뜻이다.
     * 무제한이 아니라는 것이 핵심이다.
     */
    public static final int SKIP_LIMIT = 1_000;

    /**
     * 청크 하나당 재시도 상한(최초 시도 포함).
     *
     * <p>3을 넘겨 재시도해도 회복되지 않는다면 그것은 더 이상 "일시" 장애가 아니다.
     */
    public static final int RETRY_LIMIT = 3;

    /** 스킵 시각의 출처. {@link SkipJobCommonConfig} 와 같은 이유로 빈으로 두지 않는다. */
    private static final Clock CLOCK = Clock.systemDefaultZone();

    /**
     * 2번 문제 Job. before 와 이름이 같고 리스너 등록 순서의 의미도 같다.
     *
     * @param jobRepository    Job 저장소
     * @param skipStep         처리 Step
     * @param workloadListener DB 작업량을 기록하는 리스너
     * @param seededValidator  {@code member_b} 에 데이터가 있는지 확인하는 리스너
     * @return {@code skipJob}
     */
    @Bean
    public Job skipJob(JobRepository jobRepository,
                       Step skipStep,
                       DatabaseWorkloadListener workloadListener,
                       @Qualifier("memberBSeededValidator") TableSeededValidator seededValidator) {
        return new JobBuilder("skipJob", jobRepository)
                .incrementer(new RunIdOnlyIncrementer())
                .listener(workloadListener)
                .listener(seededValidator)
                .start(skipStep)
                .build();
    }

    /**
     * 처리 Step. before 와 이름·리더·프로세서·라이터·청크 크기가 모두 같고, {@code faultTolerant()}
     * 블록만 다르다.
     *
     * <p><b>리스너를 두 번 등록하는 이유</b><br>
     * {@link ErrorRowIsolatingSkipListener} 는 {@link SkipListener} 이자
     * {@link StepExecutionListener} 다. 앞의 자격으로는 스킵을 받아 격리하고, 뒤의 자격으로는
     * 격리 기록에 남길 Step 실행 식별자를 얻는다. 캐스팅 없이 넘기면 두 오버로드 중 어느 것을
     * 부를지 정할 수 없어 컴파일되지 않는다.
     *
     * @param jobRepository        Job 저장소
     * @param transactionManager   트랜잭션 관리자
     * @param memberBItemReader    {@code member_b} 커서 리더
     * @param memberBItemProcessor 검증·가공 프로세서
     * @param memberBItemWriter    장애 주입기로 감싼 UPDATE 라이터
     * @param isolatingListener    스킵된 행을 격리하는 리스너
     * @return {@code skipStep}
     */
    @Bean
    public Step skipStep(JobRepository jobRepository,
                         PlatformTransactionManager transactionManager,
                         JdbcCursorItemReader<MemberBase> memberBItemReader,
                         MemberValidatingItemProcessor memberBItemProcessor,
                         FaultInjectingItemWriter memberBItemWriter,
                         ErrorRowIsolatingSkipListener isolatingListener) {
        return new StepBuilder("skipStep", jobRepository)
                .<MemberBase, MemberBase>chunk(SkipJobCommonConfig.CHUNK_SIZE, transactionManager)
                .reader(memberBItemReader)
                .processor(memberBItemProcessor)
                .writer(memberBItemWriter)
                .faultTolerant()
                .skip(MemberValidationException.class)
                .skipLimit(SKIP_LIMIT)
                .retry(TransientDataAccessException.class)
                .retryLimit(RETRY_LIMIT)
                .listener((SkipListener<MemberBase, MemberBase>) isolatingListener)
                .listener((StepExecutionListener) isolatingListener)
                .build();
    }

    /**
     * 격리 기록 저장소.
     *
     * @param jdbcTemplate JDBC 템플릿
     * @return 저장소
     */
    @Bean
    public ErrorRowRecorder errorRowRecorder(JdbcTemplate jdbcTemplate) {
        return new ErrorRowRecorder(jdbcTemplate);
    }

    /**
     * 스킵된 행을 격리하는 리스너. <b>after 에만 있다.</b> before 는 스킵 자체를 하지 않으므로
     * 격리할 것도 없다.
     *
     * @param errorRowRecorder 격리 기록 저장소
     * @return 리스너
     */
    @Bean
    public ErrorRowIsolatingSkipListener errorRowIsolatingSkipListener(ErrorRowRecorder errorRowRecorder) {
        return new ErrorRowIsolatingSkipListener(errorRowRecorder, CLOCK);
    }
}
