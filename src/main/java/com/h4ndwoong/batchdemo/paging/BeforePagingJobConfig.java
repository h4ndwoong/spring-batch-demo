package com.h4ndwoong.batchdemo.paging;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.support.DatabaseWorkloadListener;
import com.h4ndwoong.batchdemo.support.TableSeededValidator;
import com.h4ndwoong.batchdemo.support.RunIdOnlyIncrementer;
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
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 3번 문제(offset 페이징 함정) <b>before</b> 구성. 페이지를 {@code LIMIT ? OFFSET ?} 로 가져온다.
 *
 * <p><b>재현하는 증상</b><br>
 * {@code member_c} 200만 건을 1,000행씩 2,000페이지로 읽는다. 페이지 번호가 커질수록 DB 가 앞
 * 레코드를 읽고 버리는 비용이 커져 <b>페이지당 시간이 선형으로 증가</b>한다. 전체 스캔량은
 * 약 20억 행으로, 키셋(200만 행)의 1,000배다.
 *
 * <p><b>이 구성이 "잘못 짠 코드" 처럼 보이지 않는다는 점이 중요하다.</b> SQL 은 정상이고, 정렬도
 * 있고, 인덱스도 탄다. 결과도 정확하다 — after 와 체크섬이 완전히 같다. 틀린 것은 <b>페이지를
 * 세는 방식</b> 하나뿐이며, 그 대가는 데이터가 적을 때는 보이지도 않는다.
 *
 * <p><b>애플리케이션에서는 아무 이상이 없다.</b> 쿼리 수도, 커밋 수도, 읽은 건수도 after 와 같다.
 * 차이는 쿼리 하나가 읽는 행 수에만 있어서 {@code Handler_read_next} 를 봐야 드러난다
 * ({@link DatabaseWorkloadListener} 가 기록한다).
 *
 * <p><b>실행</b>
 * <pre>{@code
 * # 먼저 시딩 (한 번만, 200만 건):
 * ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.batch.job.name=seedJob target=member_c'
 *
 * # 전체 순회 (오래 걸린다)
 * ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.profiles.active=before --spring.batch.job.name=pagingJob'
 *
 * # 앞 200페이지만 (기울기 확인용. after 에도 같은 값을 준다)
 * ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.profiles.active=before --spring.batch.job.name=pagingJob pages=200'
 * }</pre>
 *
 * <p><b>측정</b>
 * <pre>{@code
 * # 페이지별 시간 그래프
 * ./gradlew bootRun ... | grep -o 'PAGE_TIMING,.*' > data/before.csv
 *
 * SELECT s.STEP_NAME, s.STATUS, s.READ_COUNT, s.WRITE_COUNT, s.COMMIT_COUNT,
 *        TIMESTAMPDIFF(SECOND, s.START_TIME, s.END_TIME) AS SECONDS
 * FROM BATCH_STEP_EXECUTION s ORDER BY s.STEP_EXECUTION_ID DESC;
 * }</pre>
 */
@Configuration
@Profile("before")
public class BeforePagingJobConfig {

    /**
     * 3번 문제 Job. Step 은 {@code pagingStep} 하나뿐이다.
     *
     * <p>리스너 등록 순서의 의미는 1·2번 문제와 같다. {@link DatabaseWorkloadListener} 를 맨 앞에
     * 두어 측정 범위가 Job 전체를 덮게 한다.
     *
     * @param jobRepository    Job 저장소
     * @param pagingStep       순회 Step
     * @param workloadListener DB 작업량을 기록하는 리스너
     * @param seededValidator  {@code member_c} 에 데이터가 있는지 확인하는 리스너
     * @return {@code pagingJob}
     */
    @Bean
    public Job pagingJob(JobRepository jobRepository,
                         Step pagingStep,
                         DatabaseWorkloadListener workloadListener,
                         @Qualifier("memberCSeededValidator") TableSeededValidator seededValidator) {
        return new JobBuilder("pagingJob", jobRepository)
                .incrementer(new RunIdOnlyIncrementer())
                .listener(workloadListener)
                .listener(seededValidator)
                .start(pagingStep)
                .build();
    }

    /**
     * 순회 Step. after 와 이름·청크 크기·라이터·측정 장치가 모두 같고 <b>리더만 다르다</b>.
     *
     * <p>프로세서가 없다. 3번 문제의 측정 대상은 읽기 경로이므로 가공 단계를 두면 그 비용이 섞인다.
     * 라이터도 DB 에 쓰지 않는다 ({@link TraversalChecksumItemWriter} 참고).
     *
     * @param jobRepository      Job 저장소
     * @param transactionManager 트랜잭션 관리자
     * @param memberCItemReader  offset 페이징 리더
     * @param memberCItemWriter  순회 결과를 세는 라이터
     * @param pageTimingRecorder 페이지별 소요 시간 측정 장치
     * @return {@code pagingStep}
     */
    @Bean
    public Step pagingStep(JobRepository jobRepository,
                           PlatformTransactionManager transactionManager,
                           OffsetPagingItemReader memberCItemReader,
                           TraversalChecksumItemWriter memberCItemWriter,
                           PageTimingRecorder pageTimingRecorder) {
        return new StepBuilder("pagingStep", jobRepository)
                .<MemberBase, MemberBase>chunk(PagingJobCommonConfig.CHUNK_SIZE, transactionManager)
                .reader(memberCItemReader)
                .writer(memberCItemWriter)
                .listener(pageTimingRecorder)
                .build();
    }

    /**
     * offset 페이징 리더. <b>before 의 전부</b>다.
     *
     * <p>{@code @StepScope} 인 이유는 {@code pages} 파라미터를 Job 파라미터에서 읽어야 하고,
     * 리더가 페이지 위치라는 상태를 들고 있어 Step 실행마다 새로 시작해야 하기 때문이다.
     *
     * @param jdbcTemplate       JDBC 템플릿
     * @param memberCRowMapper   행 매퍼
     * @param pageTimingRecorder 측정 장치
     * @param pages              읽을 페이지 수. 없으면 전체
     * @return 리더
     */
    @Bean
    @StepScope
    public OffsetPagingItemReader memberCItemReader(JdbcTemplate jdbcTemplate,
                                                    RowMapper<MemberBase> memberCRowMapper,
                                                    PageTimingRecorder pageTimingRecorder,
                                                    @Value("#{jobParameters['pages']}") String pages) {
        OffsetPagingItemReader reader = new OffsetPagingItemReader(
                jdbcTemplate,
                PagingJobCommonConfig.SELECT_FROM,
                memberCRowMapper,
                PagingJobCommonConfig.PAGE_SIZE,
                pageTimingRecorder);
        reader.setMaxItemCount(PagingJobCommonConfig.maxItemCount(pages));
        return reader;
    }
}
