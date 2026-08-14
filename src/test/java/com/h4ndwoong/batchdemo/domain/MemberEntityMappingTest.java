package com.h4ndwoong.batchdemo.domain;

import com.h4ndwoong.batchdemo.support.TestDatabase;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * 7개 엔티티가 {@code schema.sql} 의 테이블과 실제로 일치하는지 검증한다.
 *
 * <p>검증 방식은 두 겹이다.
 * <ul>
 *   <li>{@code spring.jpa.hibernate.ddl-auto=validate} — 컨텍스트가 뜨는 것 자체가
 *       7개 엔티티의 모든 컬럼이 실제 테이블에 존재하고 타입이 호환된다는 뜻이다.</li>
 *   <li>영속화 후 물리 테이블을 직접 조회 — 각 엔티티가 <em>의도한 테이블</em>에 들어가는지는
 *       {@code validate} 로는 알 수 없다. 7개 스키마가 동일하므로 매핑이 뒤바뀌어도
 *       {@code validate} 는 통과하기 때문이다.</li>
 * </ul>
 *
 * <p>실행 중인 MariaDB 가 필요하다. {@link TestDatabase} 의 별도 DB 에 {@code schema.sql} 을
 * 적용하므로 실습 DB 를 건드리지 않는다. 모든 테스트는 롤백된다.
 */
@SpringBootTest(properties = {
        TestDatabase.URL,
        TestDatabase.BATCH_SCHEMA,
        TestDatabase.DOMAIN_SCHEMA,
        TestDatabase.VALIDATE_MAPPING
})
@Transactional
class MemberEntityMappingTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 8, 14, 12, 30);

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    static Stream<Arguments> 엔티티와_테이블() {
        return Stream.of(
                arguments("member_a", new MemberA("a@example.com", "회원A", MemberGrade.BRONZE, 100L,
                        MemberStatus.ACTIVE, null, CREATED_AT)),
                arguments("member_b", new MemberB("b@example.com", "회원B", MemberGrade.SILVER, 200L,
                        MemberStatus.ACTIVE, null, CREATED_AT)),
                arguments("member_c", new MemberC("c@example.com", "회원C", MemberGrade.GOLD, 300L,
                        MemberStatus.DORMANT, null, CREATED_AT)),
                arguments("member_d", new MemberD("d@example.com", "회원D", MemberGrade.VIP, 400L,
                        MemberStatus.ACTIVE, 1L, CREATED_AT)),
                arguments("member_e", new MemberE("e@example.com", "회원E", MemberGrade.BRONZE, 500L,
                        MemberStatus.ACTIVE, null, CREATED_AT)),
                arguments("member_f", new MemberF("f@example.com", "회원F", MemberGrade.SILVER, 600L,
                        MemberStatus.WITHDRAWN, null, CREATED_AT)),
                arguments("member_g", new MemberG("g@example.com", "회원G", MemberGrade.GOLD, 700L,
                        MemberStatus.ACTIVE, null, CREATED_AT))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("엔티티와_테이블")
    @DisplayName("엔티티는 의도한 테이블에 저장된다")
    void 엔티티는_의도한_테이블에_저장된다(String tableName, MemberBase member) {
        entityManager.persist(member);
        entityManager.flush();

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + tableName + " WHERE id = ?", Long.class, member.getId());

        assertThat(member.getId()).as("IDENTITY 전략으로 생성 키가 채워져야 한다").isNotNull();
        assertThat(count).isEqualTo(1L);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("엔티티와_테이블")
    @DisplayName("enum 은 문자열로, processed 는 TINYINT(1) 로 왕복한다")
    void 저장한_값이_그대로_복원된다(String tableName, MemberBase member) {
        member.markProcessed("key-" + tableName, UPDATED_AT);
        entityManager.persist(member);
        entityManager.flush();
        entityManager.clear();

        MemberBase found = entityManager.find(member.getClass(), member.getId());

        assertThat(found.getEmail()).isEqualTo(member.getEmail());
        assertThat(found.getName()).isEqualTo(member.getName());
        assertThat(found.getGrade()).isEqualTo(member.getGrade());
        assertThat(found.getPoint()).isEqualTo(member.getPoint());
        assertThat(found.getStatus()).isEqualTo(member.getStatus());
        assertThat(found.getReferrerId()).isEqualTo(member.getReferrerId());
        assertThat(found.isProcessed()).isTrue();
        assertThat(found.getIdempotencyKey()).isEqualTo("key-" + tableName);
        assertThat(found.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(found.getUpdatedAt()).isEqualTo(UPDATED_AT);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("엔티티와_테이블")
    @DisplayName("grade 와 status 는 ordinal 이 아닌 문자열로 저장된다")
    void enum_은_문자열로_저장된다(String tableName, MemberBase member) {
        entityManager.persist(member);
        entityManager.flush();

        String grade = jdbcTemplate.queryForObject(
                "SELECT grade FROM " + tableName + " WHERE id = ?", String.class, member.getId());
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM " + tableName + " WHERE id = ?", String.class, member.getId());

        assertThat(grade).isEqualTo(member.getGrade().name());
        assertThat(status).isEqualTo(member.getStatus().name());
    }
}
