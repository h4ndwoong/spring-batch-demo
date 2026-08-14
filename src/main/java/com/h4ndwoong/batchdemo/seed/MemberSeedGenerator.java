package com.h4ndwoong.batchdemo.seed;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.domain.MemberFactory;
import com.h4ndwoong.batchdemo.domain.MemberGrade;
import com.h4ndwoong.batchdemo.domain.MemberStatus;

import java.time.LocalDateTime;

/**
 * 회원 테스트 데이터를 인덱스로부터 결정론적으로 생성한다.
 *
 * <p><b>왜 {@link java.util.Random} 을 쓰지 않는가</b><br>
 * 난수 생성기는 <em>상태</em>를 가지므로 n번째 값을 얻으려면 앞의 n-1번을 모두 뽑아야 한다.
 * 그러면 (1) Step 이 중간에 실패하고 재시작할 때 이어서 생성한 값이 처음 실행과 달라지고,
 * (2) 1000번째 행만 검증하는 테스트를 쓸 수 없다.
 * 여기서는 {@code (seed, index, salt)} 를 해시(SplitMix64 finalizer)해서 값을 만들므로
 * <b>어느 지점에서 재시작해도 같은 index 는 항상 같은 행</b>이 된다.
 * 그 덕분에 {@link MemberSeedItemReader} 는 재시작 시 앞 구간을 다시 읽지 않아도 된다.
 *
 * <p>같은 {@code seed} 로 실행하면 언제나 같은 데이터가 나오므로, before/after 프로파일이
 * 문자 그대로 동일한 입력을 받는다. 이것이 측정 결과를 비교할 수 있는 전제다.
 *
 * <p>이 클래스는 {@link SeedTarget} 을 알지 못한다. {@code member_a} 는 시딩 대상이 아니지만
 * {@code insertJob} 이 적재할 데이터를 만드는 데 이 생성기를 그대로 재사용해야 하기 때문이다.
 */
public class MemberSeedGenerator {

    /** 생성되는 데이터의 기준 시각. 이 시각으로부터 과거 1년 범위에 {@code created_at} 이 분포한다. */
    public static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 1, 1, 0, 0);

    /** 기본 난수 시드. 명시하지 않으면 언제 실행해도 같은 데이터가 나온다. */
    public static final long DEFAULT_SEED = 20260814L;

    private static final long SECONDS_IN_YEAR = 365L * 24 * 60 * 60;
    private static final long MAX_POINT = 100_000L;
    private static final int ACTIVE_PERCENT = 95;

    private static final String[] SURNAMES = {"김", "이", "박", "최", "정", "강", "조", "윤", "장", "임"};
    private static final String[] GIVEN_NAMES = {"민준", "서연", "지호", "하은", "예준", "수아", "도윤", "지민", "건우", "채원"};

    private static final long SALT_NAME = 0x1L;
    private static final long SALT_GRADE = 0x2L;
    private static final long SALT_POINT = 0x3L;
    private static final long SALT_STATUS = 0x4L;
    private static final long SALT_CREATED_AT = 0x5L;
    private static final long SALT_REFERRER = 0x6L;

    private final MemberFactory factory;
    private final int corruptInterval;
    private final boolean selfReferencing;
    private final long seed;
    private final LocalDateTime baseTime;

    /**
     * 생성기를 만든다.
     *
     * @param factory         생성할 엔티티 타입. 어떤 테이블에 들어갈지를 결정한다
     * @param corruptInterval 오염 행 간격. {@code 200} 이면 200번째 행마다 오염된다. 오염을 원하지 않으면 {@code 0}
     * @param selfReferencing 각 행이 앞선 행을 {@code referrer_id} 로 가리켜야 하는지 여부
     * @param seed            난수 시드. 같은 시드는 같은 데이터를 만든다
     * @param baseTime        기준 시각. {@code created_at} 은 이 시각 이전 1년 범위에 분포한다
     */
    public MemberSeedGenerator(MemberFactory factory,
                               int corruptInterval,
                               boolean selfReferencing,
                               long seed,
                               LocalDateTime baseTime) {
        this.factory = factory;
        this.corruptInterval = corruptInterval;
        this.selfReferencing = selfReferencing;
        this.seed = seed;
        this.baseTime = baseTime;
    }

    /**
     * {@link SeedTarget} 의 데이터 요건으로 생성기를 만든다.
     *
     * @param target   시딩 대상
     * @param seed     난수 시드
     * @param baseTime 기준 시각
     * @return 해당 대상용 생성기
     */
    public static MemberSeedGenerator forTarget(SeedTarget target, long seed, LocalDateTime baseTime) {
        return new MemberSeedGenerator(target.factory(), target.corruptInterval(),
                target.isSelfReferencing(), seed, baseTime);
    }

    /**
     * {@code index} 번째 회원을 생성한다. <b>순번이 그대로 {@code id} 가 된다.</b>
     *
     * <p>{@code AUTO_INCREMENT} 에 맡기지 않고 {@code id} 를 직접 정하는 이유는
     * {@link com.h4ndwoong.batchdemo.domain.MemberFactory} 에 적었다. 그 덕분에
     * {@code referrer_id} 를 {@code 1..index-1} 범위로 정하면 <b>반드시 실재하는 행</b>을 가리킨다.
     * 대상 테이블이 비어 있어야 이 성질이 성립하므로 {@code seedJob} 은 시작 전에 이를 확인한다.
     *
     * @param index 1부터 시작하는 행 순번이며 그대로 식별자가 된다
     * @return 생성된 회원. 오염 대상 순번이면 잘못된 이메일 또는 음수 포인트를 가진다
     * @throws IllegalArgumentException {@code index} 가 1보다 작을 때
     * @see #isCorrupt(long)
     */
    public MemberBase generate(long index) {
        if (index < 1) {
            throw new IllegalArgumentException("index 는 1부터 시작한다: " + index);
        }

        String email = isCorruptedEmail(index) ? corruptedEmail(index) : normalEmail(index);
        long point = isCorruptedPoint(index) ? negativePoint(index) : normalPoint(index);

        return factory.create(index, email, name(index), grade(index), point, status(index),
                referrerId(index), false, null, createdAt(index), null);
    }

    /**
     * {@code index} 번째 행이 오염 행인지 여부.
     *
     * <p>오염은 두 종류가 번갈아 나타난다. 이메일 형식 오류와 음수 포인트를 한 행에 겹치지 않게 한
     * 이유는, 2번 문제에서 스킵된 행의 원인을 격리 테이블로 추적할 때 원인이 하나여야
     * 건수 대조가 가능하기 때문이다.
     *
     * @param index 1부터 시작하는 행 순번
     * @return 오염 행이면 {@code true}
     */
    public boolean isCorrupt(long index) {
        return corruptInterval > 0 && index % corruptInterval == 0;
    }

    private boolean isCorruptedEmail(long index) {
        return isCorrupt(index) && (index / corruptInterval) % 2 == 1;
    }

    private boolean isCorruptedPoint(long index) {
        return isCorrupt(index) && (index / corruptInterval) % 2 == 0;
    }

    private String normalEmail(long index) {
        return "user" + index + "@example.com";
    }

    private String corruptedEmail(long index) {
        return "invalid-email-" + index;
    }

    private long normalPoint(long index) {
        return bounded(index, SALT_POINT, MAX_POINT);
    }

    private long negativePoint(long index) {
        return -(1 + bounded(index, SALT_POINT, MAX_POINT));
    }

    private String name(long index) {
        long value = mix(index, SALT_NAME);
        String surname = SURNAMES[(int) Math.floorMod(value, SURNAMES.length)];
        String givenName = GIVEN_NAMES[(int) Math.floorMod(value >>> 8, GIVEN_NAMES.length)];
        return surname + givenName;
    }

    private MemberGrade grade(long index) {
        return MemberGrade.values()[(int) bounded(index, SALT_GRADE, MemberGrade.values().length)];
    }

    private MemberStatus status(long index) {
        return bounded(index, SALT_STATUS, 100) < ACTIVE_PERCENT ? MemberStatus.ACTIVE : MemberStatus.DORMANT;
    }

    private LocalDateTime createdAt(long index) {
        return baseTime.minusSeconds(bounded(index, SALT_CREATED_AT, SECONDS_IN_YEAR));
    }

    private Long referrerId(long index) {
        if (!selfReferencing || index == 1) {
            return null;
        }
        return 1 + bounded(index, SALT_REFERRER, index - 1);
    }

    private long bounded(long index, long salt, long bound) {
        return Math.floorMod(mix(index, salt), bound);
    }

    /**
     * SplitMix64 finalizer. {@code (seed, index, salt)} 를 잘 섞인 64비트 값으로 만든다.
     *
     * @param index 행 순번
     * @param salt  필드별 소금값. 같은 행의 서로 다른 필드가 상관관계를 갖지 않게 한다
     * @return 해시 값
     */
    private long mix(long index, long salt) {
        long z = seed + index * 0x9E3779B97F4A7C15L + salt * 0xC2B2AE3D27D4EB4FL;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
