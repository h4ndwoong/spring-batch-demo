package com.h4ndwoong.batchdemo.support;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.domain.MemberFactory;
import com.h4ndwoong.batchdemo.seed.MemberSeedGenerator;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * 테스트가 대상 테이블을 직접 채운다. {@code seedJob} 을 실행하지 않고 같은 데이터를 만드는 지름길이다.
 *
 * <p><b>Job 을 쓰지 않는 이유</b><br>
 * 검증 대상 Job 의 <em>입력</em>을 준비하는 일이므로, 여기서 또 다른 Job 을 돌리면 실패했을 때
 * "준비가 깨진 것" 과 "검증 대상이 깨진 것" 을 구분하기 어려워진다. 배치 메타데이터도 불필요하게
 * 쌓인다. 데이터는 {@link MemberSeedGenerator} 를 그대로 써서 실행 환경과 동일하게 만든다.
 */
public final class MemberTableSeeder {

    private static final int BATCH_SIZE = 1_000;

    private static final String INSERT_SQL_TEMPLATE = """
            INSERT INTO %s (id, email, name, grade, point, status, referrer_id,
                            processed, idempotency_key, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private MemberTableSeeder() {
    }

    /**
     * 대상 테이블을 비우고 지정한 건수를 채운다.
     *
     * @param jdbcTemplate    JDBC 템플릿
     * @param table           대상 테이블 이름. 테스트 코드가 정하는 상수만 넘긴다
     * @param factory         생성할 엔티티 타입
     * @param count           생성 건수
     * @param corruptInterval 오염 간격. {@code 200} 이면 200번째 행마다 오염된다. 오염이 없으면 {@code 0}
     */
    public static void seed(JdbcTemplate jdbcTemplate,
                            String table,
                            MemberFactory factory,
                            long count,
                            int corruptInterval) {
        jdbcTemplate.execute("TRUNCATE TABLE " + table);

        MemberSeedGenerator generator = new MemberSeedGenerator(
                factory, corruptInterval, false,
                MemberSeedGenerator.DEFAULT_SEED, MemberSeedGenerator.BASE_TIME);

        String sql = INSERT_SQL_TEMPLATE.formatted(table);
        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        for (long index = 1; index <= count; index++) {
            batch.add(toParameters(generator.generate(index)));
            if (batch.size() == BATCH_SIZE) {
                jdbcTemplate.batchUpdate(sql, batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            jdbcTemplate.batchUpdate(sql, batch);
        }
    }

    /**
     * 오염된 행의 식별자 목록. 검증 결과와 대조하는 데 쓴다.
     *
     * @param count           생성 건수
     * @param corruptInterval 오염 간격
     * @return 오염 행 식별자. 오름차순
     */
    public static List<Long> corruptIds(long count, int corruptInterval) {
        List<Long> ids = new ArrayList<>();
        if (corruptInterval <= 0) {
            return ids;
        }
        for (long id = corruptInterval; id <= count; id += corruptInterval) {
            ids.add(id);
        }
        return ids;
    }

    private static Object[] toParameters(MemberBase member) {
        return new Object[]{
                member.getId(),
                member.getEmail(),
                member.getName(),
                member.getGrade().name(),
                member.getPoint(),
                member.getStatus().name(),
                member.getReferrerId(),
                member.isProcessed(),
                member.getIdempotencyKey(),
                Timestamp.valueOf(member.getCreatedAt()),
                member.getUpdatedAt() == null ? null : Timestamp.valueOf(member.getUpdatedAt())
        };
    }
}
