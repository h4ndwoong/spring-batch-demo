package com.h4ndwoong.batchdemo.support;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 측정 도구 구성. 문제·프로파일과 무관하다.
 *
 * <p>"측정 없는 개선은 인정하지 않는다" 가 실습 규칙이므로, 측정 장치는 before/after 어느 쪽에도
 * 속하지 않고 <b>양쪽에 똑같이</b> 붙어야 한다. 프로파일별 구성에 두면 한쪽만 재는 사고가 난다.
 *
 * <p>등록은 각 Job 이 한다. {@code JobBuilder.listener(...)} 로 <b>가장 먼저</b> 붙이면 측정 범위가
 * Job 전체를 덮는다. 이유는 {@link DatabaseWorkloadListener} 에 적었다.
 */
@Configuration
public class MeasurementConfig {

    /**
     * Job 전후의 DB 작업량을 기록하는 리스너.
     *
     * @param jdbcTemplate JDBC 템플릿
     * @return 리스너
     */
    @Bean
    public DatabaseWorkloadListener databaseWorkloadListener(JdbcTemplate jdbcTemplate) {
        return new DatabaseWorkloadListener(jdbcTemplate);
    }
}
