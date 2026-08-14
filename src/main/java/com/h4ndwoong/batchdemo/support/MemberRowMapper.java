package com.h4ndwoong.batchdemo.support;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.domain.MemberFactory;
import com.h4ndwoong.batchdemo.domain.MemberGrade;
import com.h4ndwoong.batchdemo.domain.MemberStatus;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * 공통 회원 스키마의 한 행을 {@link MemberBase} 로 복원한다. JDBC 기반 리더가 쓴다.
 *
 * <p>어느 테이블에서 읽는지는 {@link MemberFactory} 가 결정한다. {@code member_a} ~ {@code member_g}
 * 는 컬럼 구조가 같고 <em>타입만</em> 다르므로, 매퍼를 테이블마다 만들면 같은 코드가 일곱 벌이 된다.
 * 팩터리는 이미 7개 구현({@code MemberB::new} 등)을 가진 인터페이스라 새 추상화가 아니라
 * 기존 추상화의 재사용이다.
 *
 * <p><b>새 인스턴스를 만들지, 영속 엔티티를 조회할지</b><br>
 * 여기서 만든 인스턴스는 영속 상태가 아니다. 2번 문제의 쓰기 경로가
 * {@code JdbcBatchItemWriter} 라서 영속성 컨텍스트가 필요 없고, 오히려 없는 편이 낫다.
 * 10만 건을 읽는 동안 1차 캐시에 엔티티가 쌓이지 않기 때문이다.
 *
 * @see MemberBase#MemberBase(Long, String, String, MemberGrade, long, MemberStatus, Long, boolean, String, LocalDateTime, LocalDateTime)
 */
public class MemberRowMapper implements RowMapper<MemberBase> {

    private final MemberFactory factory;

    /**
     * 매퍼를 만든다.
     *
     * @param factory 복원할 엔티티 타입. 어느 테이블의 행인지를 결정한다
     */
    public MemberRowMapper(MemberFactory factory) {
        this.factory = factory;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code grade} 와 {@code status} 는 {@code @Enumerated(STRING)} 과 같은 문자열 표현을
     * 여기서 되돌린다. {@code referrer_id} 는 {@code NULL} 이 0으로 읽히지 않도록
     * {@link ResultSet#getObject(String, Class)} 로 읽는다.
     */
    @Override
    public MemberBase mapRow(ResultSet rs, int rowNum) throws SQLException {
        return factory.create(
                rs.getLong("id"),
                rs.getString("email"),
                rs.getString("name"),
                MemberGrade.valueOf(rs.getString("grade")),
                rs.getLong("point"),
                MemberStatus.valueOf(rs.getString("status")),
                rs.getObject("referrer_id", Long.class),
                rs.getBoolean("processed"),
                rs.getString("idempotency_key"),
                toLocalDateTime(rs.getTimestamp("created_at")),
                toLocalDateTime(rs.getTimestamp("updated_at")));
    }

    private static LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
