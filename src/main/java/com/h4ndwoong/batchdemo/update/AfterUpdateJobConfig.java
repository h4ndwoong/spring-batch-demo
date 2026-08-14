package com.h4ndwoong.batchdemo.update;

import com.h4ndwoong.batchdemo.support.DatabaseWorkloadListener;
import com.h4ndwoong.batchdemo.support.GradePolicyLoader;
import com.h4ndwoong.batchdemo.support.RunIdOnlyIncrementer;
import com.h4ndwoong.batchdemo.support.TableSeededValidator;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 6번 문제(대량 UPDATE 쓰기 경로) <b>after</b> 구성. 구간마다 집합 UPDATE 한 문장으로 끝낸다.
 *
 * <p><b>before 와 다른 것은 읽기와 쓰기의 모양뿐이다.</b> Job 이름, Step 이름, 등급 규칙, 대상
 * 범위, 갱신할 행, 측정 장치가 모두 같다.
 * <pre>
 *   리더    회원 100만 행 (커서)          →  <b>id 구간 20개</b> (키 범위 분할)
 *   가공    등급 계산 + 무변경 필터        →  <b>없다</b> (규칙이 SQL 의 CASE 로 넘어갔다)
 *   라이터  UPDATE ... WHERE id = ?       →  UPDATE ... WHERE id BETWEEN ? AND ?
 *           행마다 1문장                    <b>AND grade &lt;&gt; CASE ...</b>
 *   왕복    ≈ 750,000                     →  <b>20</b>
 * </pre>
 *
 * <p><b>애플리케이션이 회원 행을 한 건도 만지지 않는다.</b> 1~5번의 after 는 모두 "같은 데이터를 더
 * 잘 다루는" 개선이었지만, 여기서는 데이터가 <b>서버 밖으로 나오지 않는다.</b> 4번이 "같은 일을 덜
 * 나눠 보내기" 였다면 6번은 <b>"일을 데이터가 있는 곳에서 하기"</b> 다.
 *
 * <p><b>그래서 규칙이 SQL 로 이사한다.</b> 등급 산정이 자바가 아니라 {@code CASE} 식에서 일어나므로
 * 규칙이 두 곳에 존재할 위험이 생기고, 그것을 {@link GradeCaseExpression} 이 <b>생성</b>으로 막는다.
 * 이 문제에서 가장 조심해야 할 실패는 느려지는 것이 아니라 <b>등급만 조용히 틀리는 것</b>이다.
 *
 * <p><b>공짜가 아니다. 청구서는 락으로 온다.</b> before 는 1,000행마다 커밋해 락을 놓아주지만,
 * 여기서는 슬라이스 하나가 통째로 한 트랜잭션이다. 슬라이스 5만이면 그 구간을 문장이 끝날 때까지
 * 잠근다. <b>1~5번과 달리 after 가 지는 항목이 있고</b>, {@code update.slice-size} 가 그 다이얼이다.
 * <pre>
 *   slice-size = 0        왕복 1회      한 문장이 100만 행을 잠근다      ← 락 최악
 *   slice-size = 50,000   왕복 20회     한 문장이 5만 행을 잠근다        ← 기본값
 *   slice-size = 1,000    왕복 1,000회  before 와 같은 락 단위           ← 왕복 최악에 근접
 * </pre>
 * 최적점은 "가능한 최대" 가 아니라 두 곡선이 교차하는 지점이며, 그 판단은 배치가 도는 시간대에
 * 그 테이블을 누가 함께 쓰는가에 달려 있다.
 *
 * <p><b>100만 건 실측</b> (갱신 대상 749,719행)
 * <pre>
 *   Step 시간    37.8s  →  <b>8.1s</b> (4.7× ↓)
 *   Step 통계    READ/WRITE 1,000,000/749,719  →  <b>20/20</b>
 *   문장 수      COM_UPDATE 751,726  →  <b>46</b> (16,342× ↓)
 *   갱신 행      HANDLER_UPDATE 751,726  →  749,745   <b>같다</b> — 이것이 성립해야 개선이다
 *   스캔 행      HANDLER_READ_NEXT 1,000,002  →  1,000,002   동일
 *   체크섬       <b>완전히 같다</b>
 *   락 유지      ~37.7ms(청크)  →  <b>401.7ms</b>(슬라이스)   <b>10.6× ↑ — 지는 항목</b>
 *   재실행       8.5s (100만 행을 읽어 전부 버린다)  →  <b>1.1s</b> (갱신 0행)
 *   slice-size=0 문장 8개, 시간 8.4s(늘었다), 락 <b>7,866ms</b> — 다이얼을 끝까지 돌리면 손해만 남는다
 * </pre>
 *
 * <p><b>실행</b>
 * <pre>{@code
 * # before 를 돌린 뒤라면 반드시 재시딩한다 (before 가 grade 를 파괴했다):
 * #   TRUNCATE TABLE member_f;
 * #   ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.batch.job.name=seedJob target=member_f'
 *
 * ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.profiles.active=after --spring.batch.job.name=updateJob'
 *
 * # 슬라이스 크기 축 (락 유지 시간 대 왕복)
 * ./gradlew bootRun --args='... --spring.profiles.active=after --spring.batch.job.name=updateJob --update.slice-size=0'
 *
 * # 슬라이스별 문장 시간 그래프의 원자료
 * ./gradlew bootRun --args='...' | grep -o 'SLICE_UPDATE,.*' > data/update-after.csv
 * }</pre>
 */
@Configuration
@Profile("after")
public class AfterUpdateJobConfig {

    /**
     * 청크 크기. <b>1 이어야 한다.</b>
     *
     * <p>한 슬라이스 = 한 문장 = 한 트랜잭션이라는 등식이 6번의 락 이야기 전체를 지탱한다. 이 값을
     * 키우면 여러 슬라이스가 한 트랜잭션에 묶여 <b>슬라이스 크기를 줄인 효과가 사라진다</b> —
     * 실제로 그것이 "왜 잘게 쪼갰는데도 락이 오래 잡히죠" 의 답이다.
     */
    public static final int CHUNK_SIZE = 1;

    /**
     * 6번 문제 Job. before 와 이름이 같고 리스너 등록 순서의 의미도 같다.
     *
     * @param jobRepository    Job 저장소
     * @param updateStep       등급 재계산 Step
     * @param workloadListener DB 작업량을 기록하는 리스너
     * @param seededValidator  {@code member_f} 에 데이터가 있는지 확인하는 리스너
     * @param indexListener    {@code (grade, point)} 인덱스 상태를 맞추는 리스너
     * @return {@code updateJob}
     */
    @Bean
    public Job updateJob(JobRepository jobRepository,
                         Step updateStep,
                         DatabaseWorkloadListener workloadListener,
                         @Qualifier("memberFSeededValidator") TableSeededValidator seededValidator,
                         GradePointIndexListener indexListener) {
        return new JobBuilder("updateJob", jobRepository)
                .incrementer(new RunIdOnlyIncrementer())
                .listener(workloadListener)
                .listener(seededValidator)
                .listener(indexListener)
                .start(updateStep)
                .build();
    }

    /**
     * 등급 재계산 Step. before 와 이름이 같아야 메타데이터를 한 축에서 읽을 수 있다.
     *
     * <p><b>프로세서가 없다.</b> 가공할 아이템이 회원이 아니라 일감이기 때문이고, 등급 규칙은
     * 라이터의 SQL 안으로 옮겨 갔기 때문이다. Step 구성만 보면 6번의 개선이 무엇인지 드러난다 —
     * 애플리케이션에서 사라진 단계가 서버에서 한 문장이 되었다.
     *
     * @param jobRepository      Job 저장소
     * @param transactionManager 트랜잭션 관리자
     * @param memberFSliceReader {@code id} 구간을 발행하는 리더
     * @param memberFSliceWriter 집합 UPDATE 라이터
     * @param reporter           등급 분포 지문 리포터
     * @param sliceRecorder      슬라이스별 시간·행수 기록기
     * @return {@code updateStep}
     */
    @Bean
    public Step updateStep(JobRepository jobRepository,
                           PlatformTransactionManager transactionManager,
                           IdRangeSliceItemReader memberFSliceReader,
                           SetBasedGradeUpdateItemWriter memberFSliceWriter,
                           GradeRecalcReporter reporter,
                           SliceUpdateRecorder sliceRecorder) {
        return new StepBuilder("updateStep", jobRepository)
                .<IdSlice, IdSlice>chunk(CHUNK_SIZE, transactionManager)
                .reader(memberFSliceReader)
                .writer(memberFSliceWriter)
                .listener(reporter)
                .listener(sliceRecorder)
                .build();
    }

    /**
     * {@code id} 구간을 발행하는 리더.
     *
     * <p>{@code @StepScope} 인 이유는 두 가지다. 슬라이스 크기를 프로퍼티에서 읽어야 하고, 발행
     * 상태를 필드로 들고 있으므로 Step 실행마다 새 인스턴스여야 한다.
     *
     * @param jdbcTemplate JDBC 템플릿
     * @param sliceSize    {@code update.slice-size}. 0 이면 분할하지 않는다
     * @return 리더
     */
    @Bean
    @StepScope
    public IdRangeSliceItemReader memberFSliceReader(
            JdbcTemplate jdbcTemplate,
            @Value("${update.slice-size:" + UpdateJobCommonConfig.DEFAULT_SLICE_SIZE + "}") long sliceSize) {
        return new IdRangeSliceItemReader(jdbcTemplate, UpdateJobCommonConfig.TABLE, sliceSize);
    }

    /**
     * 집합 UPDATE 라이터.
     *
     * <p><b>{@code @StepScope} 가 "Step 시작 시 정책 1회 로딩" 을 구현한다.</b> before 의 프로세서와
     * 같은 자리, 같은 로더, 같은 값이다 — 그래야 두 프로파일의 등급 산정이 문자 그대로 같은 규칙에서
     * 나온다. 다른 점은 그 값이 자바 코드로 가느냐 SQL 문장으로 가느냐뿐이다.
     *
     * @param jdbcTemplate      JDBC 템플릿
     * @param gradePolicyLoader 정책 로더
     * @param sliceRecorder     슬라이스별 측정치를 받는 기록기
     * @return 라이터
     */
    @Bean
    @StepScope
    public SetBasedGradeUpdateItemWriter memberFSliceWriter(
            JdbcTemplate jdbcTemplate,
            @Qualifier("memberFGradePolicyLoader") GradePolicyLoader gradePolicyLoader,
            SliceUpdateRecorder sliceRecorder) {
        return new SetBasedGradeUpdateItemWriter(
                new NamedParameterJdbcTemplate(jdbcTemplate),
                UpdateJobCommonConfig.TABLE,
                GradeCaseExpression.of(gradePolicyLoader.load()),
                UpdateJobCommonConfig.CLOCK,
                sliceRecorder);
    }

    /**
     * 슬라이스별 시간과 갱신 행 수를 모으는 측정 장치. <b>after 에만 있다.</b>
     *
     * <p>싱글턴이다. Step 이 끝난 뒤 테스트가 같은 인스턴스에서 보고를 읽어야 하기 때문이며,
     * 매 Step 시작 시 기록을 비운다 (3번의 {@code PageTimingRecorder} 와 같다).
     *
     * @return 기록기
     */
    @Bean
    public SliceUpdateRecorder sliceUpdateRecorder() {
        return new SliceUpdateRecorder();
    }
}
