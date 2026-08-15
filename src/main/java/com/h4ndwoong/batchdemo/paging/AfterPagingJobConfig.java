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
 * 3번 문제(offset 페이징 함정) <b>after</b> 구성. 페이지를 {@code WHERE id > ? ... LIMIT ?} 로 가져온다.
 *
 * <p><b>before 와 다른 것은 리더 빈 하나뿐이다.</b> Job 이름, Step 이름, 청크 크기, 페이지 크기,
 * 행 매퍼, 라이터, 측정 장치가 모두 같다. 3번 문제의 주장이 "페이지 획득 방식만 바꿔도 이만큼
 * 달라진다" 이므로 다른 것이 하나라도 더 바뀌면 그 주장이 성립하지 않는다.
 *
 * <p><b>기대되는 결과</b>
 * <pre>
 *   체크섬        before 와 완전히 같다   ← 같은 일을 했다는 증거. 이게 먼저다
 *   READ_COUNT   before 와 같다
 *   COM_SELECT   before 와 <b>같다</b>          ← 왕복은 줄지 않는다
 *   HANDLER_READ_NEXT  before 의 약 1/1000  ← 줄어든 것은 쿼리당 읽은 행 수다
 *   페이지별 시간  평탄 (배율 ≈ 1.0)        ← before 는 페이지 번호에 비례해 증가
 * </pre>
 *
 * <p><b>1번 문제와 개선의 성격이 다르다.</b> 1번은 왕복을 1,000배 줄여서 빨라졌지만, 여기서는
 * 왕복이 그대로다. "쿼리를 덜 보내는 것" 과 "쿼리가 일을 덜 하게 하는 것" 은 다른 지렛대이며,
 * 3번은 후자다. 그래서 애플리케이션 지표(쿼리 수, 커밋 수, 처리 건수)만 보고 있으면 이 문제는
 * <b>보이지 않는다</b>.
 *
 * <p><b>실행</b>
 * <pre>{@code
 * ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.profiles.active=after --spring.batch.job.name=pagingJob'
 *
 * # before 와 같은 구간으로 비교
 * ./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.profiles.active=after --spring.batch.job.name=pagingJob pages=200'
 * }</pre>
 *
 * @see KeysetPagingItemReader 키셋 페이징의 전제(유니크한 정렬 키, 인덱스, 임의 페이지 접근 불가)
 */
@Configuration
@Profile("after")
public class AfterPagingJobConfig {

    /**
     * 3번 문제 Job. before 와 이름이 같고 리스너 등록 순서의 의미도 같다.
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
     * 순회 Step. before 와 이름이 같아야 같은 축에서 비교할 수 있다.
     *
     * <p>before 와의 차이는 주입받는 리더의 타입 하나뿐이다.
     *
     * @param jobRepository      Job 저장소
     * @param transactionManager 트랜잭션 관리자
     * @param memberCItemReader  키셋 페이징 리더
     * @param memberCItemWriter  순회 결과를 세는 라이터
     * @param pageTimingRecorder 페이지별 소요 시간 측정 장치
     * @return {@code pagingStep}
     */
    @Bean
    public Step pagingStep(JobRepository jobRepository,
                           PlatformTransactionManager transactionManager,
                           KeysetPagingItemReader memberCItemReader,
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
     * 키셋 페이징 리더. <b>after 의 전부</b>다.
     *
     * <p>{@code @StepScope} 인 이유는 before 와 같다. 여기서는 하나 더 있다 — 이 리더는
     * 마지막으로 읽은 {@code id} 를 들고 있으므로 Step 실행마다 새 인스턴스여야 안전하다.
     *
     * @param jdbcTemplate       JDBC 템플릿
     * @param memberCRowMapper   행 매퍼
     * @param pageTimingRecorder 측정 장치
     * @param pages              읽을 페이지 수. 없으면 전체
     * @return 리더
     */
    @Bean
    @StepScope
    public KeysetPagingItemReader memberCItemReader(JdbcTemplate jdbcTemplate,
                                                    RowMapper<MemberBase> memberCRowMapper,
                                                    PageTimingRecorder pageTimingRecorder,
                                                    @Value("#{jobParameters['pages']}") String pages) {
        KeysetPagingItemReader reader = new KeysetPagingItemReader(
                jdbcTemplate,
                PagingJobCommonConfig.SELECT_FROM,
                memberCRowMapper,
                PagingJobCommonConfig.PAGE_SIZE,
                pageTimingRecorder);
        reader.setMaxItemCount(PagingJobCommonConfig.maxItemCount(pages));
        return reader;
    }
}
