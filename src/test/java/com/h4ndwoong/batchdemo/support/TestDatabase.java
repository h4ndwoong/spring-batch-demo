package com.h4ndwoong.batchdemo.support;

/**
 * 통합 테스트가 쓰는 데이터베이스 설정. {@code @SpringBootTest(properties = ...)} 에 그대로 넘긴다.
 *
 * <p><b>왜 실습 DB({@code batch_demo})를 쓰지 않는가</b><br>
 * 테스트가 Job 을 실행하면 {@code BATCH_JOB_EXECUTION} 등 메타데이터 테이블에 실행 기록이 쌓인다.
 * 이 프로젝트는 before/after 의 커밋 횟수와 Step 통계를 메타데이터 테이블에서 직접 읽어 비교하는
 * 실습이므로, 테스트 실행 기록이 섞이면 관측을 방해한다. 그래서 별도 DB 를 쓴다.
 *
 * <p>{@code createDatabaseIfNotExist=true} 로 DB 가 없으면 만들고,
 * {@code initialize-schema=always} 와 {@code sql.init.mode=always} 로 메타데이터 테이블과
 * 도메인 테이블을 그 안에 생성한다. 실행 중인 MariaDB 만 있으면 사전 준비가 필요 없다.
 *
 * <p>상수여야 하는 이유는 애노테이션 인자로 쓰이기 때문이다.
 */
public final class TestDatabase {

    /** 실습 DB 와 분리된 테스트 전용 DB. 없으면 생성된다. */
    public static final String URL =
            "spring.datasource.url=jdbc:mariadb://localhost:3307/batch_demo_test?createDatabaseIfNotExist=true";

    /** Spring Batch 메타데이터 테이블을 생성한다. */
    public static final String BATCH_SCHEMA = "spring.batch.jdbc.initialize-schema=always";

    /** {@code schema.sql} 을 실행해 도메인 테이블을 생성한다. */
    public static final String DOMAIN_SCHEMA = "spring.sql.init.mode=always";

    /** 엔티티 매핑이 실제 테이블과 일치하는지 기동 시점에 검증한다. */
    public static final String VALIDATE_MAPPING = "spring.jpa.hibernate.ddl-auto=validate";

    private TestDatabase() {
    }
}
