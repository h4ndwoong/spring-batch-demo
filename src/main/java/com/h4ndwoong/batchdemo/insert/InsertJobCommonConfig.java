package com.h4ndwoong.batchdemo.insert;

import com.h4ndwoong.batchdemo.seed.MemberSeedGenerator;
import com.h4ndwoong.batchdemo.seed.MemberSeedItemReader;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 1번 문제에서 <b>before 와 after 가 공유해야 하는</b> 구성.
 *
 * <p>여기 있는 것은 프로파일에 따라 달라져서는 안 되는 것들이다. 특히 리더가 그렇다. 입력 데이터가
 * 조금이라도 다르면 두 측정치는 같은 축에 놓이지 않는다. 프로파일별 구성에 리더를 각각 두면
 * 언젠가 한쪽만 바뀐다.
 *
 * <p>프로파일별 구성({@link BeforeInsertJobConfig}, {@link AfterInsertJobConfig})에는 <b>차이 그 자체</b>만
 * 남긴다. 청크 크기, 라이터, 인덱스 생성 시점 세 가지다.
 */
@Configuration
public class InsertJobCommonConfig {

    /**
     * 적재할 회원을 만들어 내는 리더.
     *
     * <p>반환 타입을 인터페이스가 아니라 구현 클래스로 선언해야 {@link StepScope} 프록시가
     * {@code ItemStream} 을 구현해 재시작 지점이 기록된다. 이유는
     * {@link com.h4ndwoong.batchdemo.seed.SeedJobConfig} 에 적었다.
     *
     * @param count 적재 건수. 없으면 {@link MemberAItemReaderFactory#DEFAULT_COUNT}
     * @param seed  난수 시드. 없으면 {@link MemberSeedGenerator#DEFAULT_SEED}
     * @return 회원 생성 리더
     */
    @Bean
    @StepScope
    public MemberSeedItemReader memberAItemReader(@Value("#{jobParameters['count']}") String count,
                                                 @Value("#{jobParameters['seed']}") String seed) {
        long rows = count == null ? MemberAItemReaderFactory.DEFAULT_COUNT : Long.parseLong(count);
        long seedValue = seed == null ? MemberSeedGenerator.DEFAULT_SEED : Long.parseLong(seed);
        return MemberAItemReaderFactory.of(rows, seedValue);
    }

    /**
     * {@code member_a} 가 비어 있는지 확인하는 리스너. 빈 테이블 적재라는 전제는 양쪽 공통이다.
     *
     * @param jdbcTemplate JDBC 템플릿
     * @return 리스너
     */
    @Bean
    public MemberAEmptyValidator memberAEmptyValidator(JdbcTemplate jdbcTemplate) {
        return new MemberAEmptyValidator(jdbcTemplate);
    }

    /**
     * 보조 인덱스 생성기. <b>무엇을</b> 만들지는 공통이고, <b>언제</b> 만들지가 프로파일의 차이다.
     *
     * @param jdbcTemplate JDBC 템플릿
     * @return 인덱스 생성기
     */
    @Bean
    public MemberAIndexCreator memberAIndexCreator(JdbcTemplate jdbcTemplate) {
        return new MemberAIndexCreator(jdbcTemplate);
    }
}
