package com.h4ndwoong.batchdemo.seed;

import com.h4ndwoong.batchdemo.domain.MemberB;
import com.h4ndwoong.batchdemo.domain.MemberC;
import com.h4ndwoong.batchdemo.domain.MemberD;
import com.h4ndwoong.batchdemo.domain.MemberE;
import com.h4ndwoong.batchdemo.domain.MemberF;
import com.h4ndwoong.batchdemo.domain.MemberG;
import com.h4ndwoong.batchdemo.domain.MemberFactory;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * {@code seedJob} 이 시딩할 수 있는 대상 테이블과 그 데이터 요건.
 *
 * <p><b>{@code member_a} 가 없는 이유</b><br>
 * 1번 문제는 "비어 있는 {@code member_a} 에 100만 건을 적재"하는 것 자체가 측정 대상이다.
 * {@code insertJob} 이 데이터를 생성하며 적재하므로 미리 시딩하면 실습이 성립하지 않는다.
 * {@code insertJob} 은 이 클래스가 아니라 {@link MemberSeedGenerator} 를 직접 재사용한다.
 *
 * <p>기본 건수는 README 의 문제별 데이터 규모를 따른다. Job 파라미터 {@code count} 로 덮을 수 있다.
 */
public enum SeedTarget {

    /** 2번 문제. 오염 행이 섞여 있어야 skip/retry 실습이 성립한다. */
    MEMBER_B("member_b", 100_000L, 200, false, MemberB::new),

    /** 3번 문제. offset 페이징의 뒤 페이지 비용을 드러내려면 규모가 커야 한다. */
    MEMBER_C("member_c", 2_000_000L, 0, false, MemberC::new),

    /** 4번 문제. 모든 행이 실제 존재하는 추천인을 가리켜야 N+1 조회가 매번 성립한다. */
    MEMBER_D("member_d", 500_000L, 0, true, MemberD::new),

    /** 5번 문제. */
    MEMBER_E("member_e", 300_000L, 0, false, MemberE::new),

    /** 6번 문제. */
    MEMBER_F("member_f", 1_000_000L, 0, false, MemberF::new),

    /** 7번 문제. */
    MEMBER_G("member_g", 100_000L, 0, false, MemberG::new);

    private final String tableName;
    private final long defaultCount;
    private final int corruptInterval;
    private final boolean selfReferencing;
    private final MemberFactory factory;

    SeedTarget(String tableName,
               long defaultCount,
               int corruptInterval,
               boolean selfReferencing,
               MemberFactory factory) {
        this.tableName = tableName;
        this.defaultCount = defaultCount;
        this.corruptInterval = corruptInterval;
        this.selfReferencing = selfReferencing;
        this.factory = factory;
    }

    /**
     * 테이블 이름으로 대상을 찾는다. Job 파라미터로 받은 임의의 문자열이 SQL 에 직접 들어가지
     * 않도록, 시딩 대상은 반드시 이 조회를 통과해야 한다.
     *
     * @param tableName 테이블 이름. {@code member_b} 처럼 스키마상의 이름이며 대소문자를 구분하지 않는다
     * @return 해당 시딩 대상
     * @throws IllegalArgumentException {@code tableName} 이 {@code null} 이거나 시딩 대상이 아닐 때
     */
    public static SeedTarget from(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException(
                    "Job 파라미터 target 이 필요하다. 가능한 값: " + validTableNames());
        }
        return Arrays.stream(values())
                .filter(target -> target.tableName.equalsIgnoreCase(tableName.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "시딩할 수 없는 대상이다: " + tableName + ". 가능한 값: " + validTableNames()
                                + " (member_a 는 insertJob 이 직접 적재하므로 시딩 대상이 아니다)"));
    }

    private static String validTableNames() {
        return Arrays.stream(values())
                .map(SeedTarget::tableName)
                .collect(Collectors.joining(", "));
    }

    public String tableName() {
        return tableName;
    }

    public long defaultCount() {
        return defaultCount;
    }

    /**
     * 오염 행을 심을 간격. {@code 200} 이면 200번째 행마다 오염되므로 오염 건수는 {@code count / 200} 이다.
     * 건수가 아니라 간격으로 정의해서 {@code count} 를 줄여도 오염 비율이 유지된다.
     *
     * @return 오염 간격. 오염 행을 심지 않으면 {@code 0}
     */
    public int corruptInterval() {
        return corruptInterval;
    }

    /** 각 행이 같은 테이블의 앞선 행을 {@code referrer_id} 로 가리켜야 하는지 여부. */
    public boolean isSelfReferencing() {
        return selfReferencing;
    }

    public MemberFactory factory() {
        return factory;
    }
}
