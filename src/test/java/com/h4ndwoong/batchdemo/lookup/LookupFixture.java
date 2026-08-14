package com.h4ndwoong.batchdemo.lookup;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.domain.MemberD;
import com.h4ndwoong.batchdemo.domain.MemberGrade;
import com.h4ndwoong.batchdemo.seed.MemberSeedGenerator;
import com.h4ndwoong.batchdemo.support.GradePolicy;
import com.h4ndwoong.batchdemo.support.GradePolicyLoader;
import com.h4ndwoong.batchdemo.support.MemberTableSeeder;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 4번 문제의 before/after 통합 테스트가 <b>함께 쓰는</b> 기대값.
 *
 * <p>두 테스트는 프로파일이 달라 한 컨텍스트에 담을 수 없다. 그래서 "before 와 after 의 산정
 * 결과가 같다" 를 한 테스트 안에서 직접 비교할 수 없고, 대신 <b>양쪽이 같은 기대값과 대조</b>하게
 * 한다. 두 테스트가 같은 {@link #checksum(long)} 을 통과하면 서로 같다는 뜻이다.
 *
 * <p><b>기대값을 상수로 박지 않고 계산하는 이유</b><br>
 * 실행 결과를 그대로 상수로 옮겨 적으면 그 시험은 "지난번과 같다" 만 말한다. 여기서는
 * {@link MemberSeedGenerator} 로 같은 데이터를 만든 뒤 <b>배치를 거치지 않고 메모리에서 곧장</b>
 * 등급을 산정해 기대값을 얻는다. 산정 규칙 자체({@link GradePolicy}, {@link ReferrerBonus})는
 * 운영 코드의 것을 그대로 쓰므로 규칙을 다시 구현하지는 않는다 — 검증 대상은 규칙이 아니라
 * <b>리더·조회 전략·프로세서·라이터가 그 규칙을 50만 번 올바르게 이어 붙였는가</b>이기 때문이다.
 * 조회를 청크로 묶다가 엉뚱한 추천인을 붙이면 여기서 걸린다.
 */
final class LookupFixture {

    /** 통합 테스트가 처리할 행 수. 청크 크기의 배수로 잡아 청크 수가 딱 떨어지게 한다. */
    static final long COUNT = 20_000L;

    /** {@code limit} 파라미터 검증에 쓸 행 수. */
    static final long PARTIAL_COUNT = 5_000L;

    /** 추천인이 없는 행 수. {@code id=1} 하나뿐이다 — 앞선 행이 없으므로 가리킬 대상이 없다. */
    static final long ROWS_WITHOUT_REFERRER = 1L;

    private LookupFixture() {
    }

    /**
     * {@code member_d} 를 테스트 데이터로 채운다.
     *
     * @param jdbcTemplate JDBC 템플릿
     */
    static void seed(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        MemberTableSeeder.seed(jdbcTemplate, "member_d", MemberD::new, COUNT, 0, true);
    }

    /**
     * 앞 {@code processed} 건을 산정했을 때 나와야 하는 지문.
     *
     * <p>등급 정책은 <b>테이블 전체</b>의 포인트 분포에서 나온다. {@code limit} 으로 일부만
     * 처리해도 정책은 달라지지 않는다 — 정책 로딩이 Step 시작 시 1회이고 대상이 테이블 전체이기
     * 때문이다. 기대값도 같은 방식으로 만든다.
     *
     * @param processed 산정한 행 수
     * @return 기대 지문
     */
    static GradeDecisionChecksum checksum(long processed) {
        List<MemberBase> members = members();
        GradePolicy policy = policy(members);

        long changed = 0;
        long effectivePointSum = 0;
        Map<MemberGrade, Long> distribution = new EnumMap<>(MemberGrade.class);

        for (int index = 0; index < processed; index++) {
            MemberBase member = members.get(index);
            long bonus = member.getReferrerId() == null
                    ? ReferrerBonus.none()
                    : ReferrerBonus.of(members.get(member.getReferrerId().intValue() - 1).getGrade());

            long effectivePoint = member.getPoint() + bonus;
            MemberGrade newGrade = policy.gradeOf(effectivePoint);

            if (newGrade != member.getGrade()) {
                changed++;
            }
            effectivePointSum += effectivePoint;
            distribution.merge(newGrade, 1L, Long::sum);
        }

        return new GradeDecisionChecksum(processed, changed, effectivePointSum, distribution);
    }

    /**
     * 시딩과 같은 데이터. {@code index - 1} 번째 원소가 {@code id = index} 인 행이다.
     *
     * @return 회원 목록. {@code id} 오름차순
     */
    static List<MemberBase> members() {
        MemberSeedGenerator generator = MemberTableSeeder.generator(MemberD::new, 0, true);
        List<MemberBase> members = new ArrayList<>((int) COUNT);
        for (long id = 1; id <= COUNT; id++) {
            members.add(generator.generate(id));
        }
        return members;
    }

    /**
     * 시딩된 포인트 분포에서 나오는 등급 정책. {@link GradePolicyLoader} 가 DB 에서 얻는 것과 같은 값이다.
     *
     * @param members 회원 목록
     * @return 정책
     */
    static GradePolicy policy(List<MemberBase> members) {
        long min = members.stream().mapToLong(MemberBase::getPoint).min().orElseThrow();
        long max = members.stream().mapToLong(MemberBase::getPoint).max().orElseThrow();
        return GradePolicy.ofRange(min, max);
    }
}
