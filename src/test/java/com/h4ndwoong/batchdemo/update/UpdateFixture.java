package com.h4ndwoong.batchdemo.update;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.domain.MemberF;
import com.h4ndwoong.batchdemo.domain.MemberGrade;
import com.h4ndwoong.batchdemo.seed.MemberSeedGenerator;
import com.h4ndwoong.batchdemo.support.GradePolicy;
import com.h4ndwoong.batchdemo.support.MemberTableSeeder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 6번 문제의 before/after 통합 테스트가 <b>함께 쓰는</b> 기대값.
 *
 * <p>4·5번의 fixture 와 같은 이유로 기대값을 상수로 박지 않고 계산한다. 실행 결과를 옮겨 적으면 그
 * 시험은 "지난번과 같다" 만 말한다. 여기서는 {@link MemberSeedGenerator} 로 같은 데이터를 만든 뒤
 * <b>배치를 거치지 않고 메모리에서</b> 등급을 다시 매긴다.
 *
 * <p><b>이 fixture 가 증명하는 것</b><br>
 * 두 프로파일이 같은 {@link #expectedChecksum()} 을 통과하면 서로 같은 결과를 냈다는 뜻이다.
 * 6번에서 이것이 특히 중요하다 — after 는 등급 규칙을 <b>SQL 의 {@code CASE} 식으로 옮겨 실행</b>
 * 하므로, 자바로 계산한 이 기대값과 맞아떨어져야 규칙이 이관 과정에서 변형되지 않았음이 선다.
 */
final class UpdateFixture {

    /** 통합 테스트가 다룰 행 수. 청크 크기와 슬라이스 크기의 배수로 잡아 경계가 깔끔하게 떨어진다. */
    static final long COUNT = 20_000L;

    /**
     * 통합 테스트에서 쓸 슬라이스 크기.
     *
     * <p>기본값({@value UpdateJobCommonConfig#DEFAULT_SLICE_SIZE})은 100만 건을 20개로 자르는 값이라
     * 2만 건짜리 축소판에서는 <b>슬라이스가 하나</b>가 되어 분할을 검증할 수 없다. 4개로 잘리도록
     * 줄여 둔다.
     */
    static final long SLICE_SIZE = 5_000L;

    /** {@code @SpringBootTest(properties = ...)} 에 그대로 넘길 표현. 애노테이션 인자라 상수여야 한다. */
    static final String SLICE_SIZE_PROPERTY = "update.slice-size=5000";

    private UpdateFixture() {
    }

    /**
     * {@code member_f} 를 테스트 데이터로 채운다.
     *
     * @param jdbcTemplate JDBC 템플릿
     */
    static void seed(JdbcTemplate jdbcTemplate) {
        MemberTableSeeder.seed(jdbcTemplate, "member_f", MemberF::new, COUNT, 0);
    }

    /**
     * 시딩된 포인트 분포에서 나오는 등급 정책. {@code GradePolicyLoader} 가 DB 에서 얻는 것과 같은 값이다.
     *
     * @return 정책
     */
    static GradePolicy policy() {
        List<MemberBase> members = members();
        long min = members.stream().mapToLong(MemberBase::getPoint).min().orElseThrow();
        long max = members.stream().mapToLong(MemberBase::getPoint).max().orElseThrow();
        return GradePolicy.ofRange(min, max);
    }

    /**
     * 등급이 실제로 바뀌는 행 수. <b>이 배치가 해야 할 일의 양</b>이다.
     *
     * <p>시드의 등급은 포인트와 무관한 해시라 약 4분의 3이 바뀐다. before 의 {@code WRITE_COUNT},
     * after 의 갱신 행 합계, 체크섬의 {@code changedRows} 가 모두 이 값이어야 한다.
     *
     * @return 행 수
     */
    static long changedCount() {
        GradePolicy policy = policy();
        return members().stream()
                .filter(member -> policy.gradeOf(member.getPoint()) != member.getGrade())
                .count();
    }

    /**
     * 재계산이 끝난 뒤의 지문. <b>before 와 after 가 똑같이 이 값이어야 한다.</b>
     *
     * @return 지문
     */
    static GradeRecalcChecksum expectedChecksum() {
        GradePolicy policy = policy();
        Map<MemberGrade, Long> distribution = new EnumMap<>(MemberGrade.class);
        long pointSum = 0;

        for (MemberBase member : members()) {
            distribution.merge(policy.gradeOf(member.getPoint()), 1L, Long::sum);
            pointSum += member.getPoint();
        }
        return new GradeRecalcChecksum(COUNT, changedCount(), pointSum, distribution);
    }

    /**
     * 시딩 직후의 지문. 아무것도 갱신되지 않은 상태다.
     *
     * @return 지문
     */
    static GradeRecalcChecksum seededChecksum() {
        Map<MemberGrade, Long> distribution = new EnumMap<>(MemberGrade.class);
        long pointSum = 0;

        for (MemberBase member : members()) {
            distribution.merge(member.getGrade(), 1L, Long::sum);
            pointSum += member.getPoint();
        }
        return new GradeRecalcChecksum(COUNT, 0, pointSum, distribution);
    }

    /**
     * {@link #SLICE_SIZE} 로 잘랐을 때 나오는 슬라이스 수. after 의 <b>왕복 횟수</b>이기도 하다.
     *
     * @return 슬라이스 수
     */
    static int sliceCount() {
        return IdSlice.of(1, COUNT, SLICE_SIZE).size();
    }

    /**
     * 시딩과 같은 데이터. {@code index - 1} 번째 원소가 {@code id = index} 인 행이다.
     *
     * @return 회원 목록. {@code id} 오름차순
     */
    static List<MemberBase> members() {
        MemberSeedGenerator generator = MemberTableSeeder.generator(MemberF::new, 0, false);
        List<MemberBase> members = new ArrayList<>((int) COUNT);
        for (long id = 1; id <= COUNT; id++) {
            members.add(generator.generate(id));
        }
        return members;
    }
}
