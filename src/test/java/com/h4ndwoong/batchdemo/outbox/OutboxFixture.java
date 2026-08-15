package com.h4ndwoong.batchdemo.outbox;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.domain.MemberG;
import com.h4ndwoong.batchdemo.seed.MemberSeedGenerator;
import com.h4ndwoong.batchdemo.support.MemberTableSeeder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 7번 문제의 before/after 통합 테스트가 <b>함께 쓰는</b> 기대값.
 *
 * <p>4~6번의 fixture 와 같은 이유로 기대값을 상수로 박지 않고 계산한다. 실행 결과를 옮겨 적으면 그
 * 시험은 "지난번과 같다" 만 말한다. 여기서는 {@link MemberSeedGenerator} 로 같은 데이터를 만든 뒤
 * <b>배치를 거치지 않고 메모리에서</b> 대상과 기대 멱등키를 계산한다.
 *
 * <p><b>7번의 기대값은 두 종류다.</b> DB 쪽 기대값(대상 수, 상태 분포)은 5·6번과 같은 성격이지만,
 * 발송 쪽 기대값은 <b>실패가 어디서 났는가에 의존한다</b> — 유령 알림도 중복 발송도 정확히
 * "실패한 청크 하나" 만큼이다. 그래서 {@link #phantomCount()} 가 청크 크기와 같다.
 */
final class OutboxFixture {

    /** 통합 테스트가 다룰 행 수. 청크 크기의 배수로 잡아 경계가 깔끔하게 떨어진다. */
    static final long COUNT = 20_000L;

    /**
     * 장애를 심을 지점. 이만큼 커밋한 뒤 <b>다음 청크를 쓰고 나서</b> 실패한다.
     *
     * <p>청크 크기({@value OutboxJobCommonConfig#CHUNK_SIZE})의 배수여야 "정확히 N 건이 커밋된
     * 상태" 가 만들어진다. 대상 회원 수보다 작아야 실패 뒤에 할 일이 남는다.
     */
    static final long FAIL_AFTER = 10_000L;

    /**
     * 발송 로그를 잠재우는 프로퍼티. {@code @SpringBootTest(properties = ...)} 에 그대로 넘긴다.
     *
     * <p>{@link LoggingNotificationSender} 는 발송 한 건에 한 줄을 찍는다. CLI 실행에서는 그것이
     * 프로세스를 넘는 유일한 원자료지만, 테스트에서는 메서드마다 2만 줄이 되고 <b>중복 집계는
     * {@link NotificationRecorder} 가 이미 알고 있다.</b>
     */
    static final String SENDER_LOG_LEVEL =
            "logging.level.com.h4ndwoong.batchdemo.outbox.LoggingNotificationSender=WARN";

    private OutboxFixture() {
    }

    /**
     * {@code member_g} 를 테스트 데이터로 채운다.
     *
     * @param jdbcTemplate JDBC 템플릿
     */
    static void seed(JdbcTemplate jdbcTemplate) {
        MemberTableSeeder.seed(jdbcTemplate, OutboxJobCommonConfig.TABLE, MemberG::new, COUNT, 0);
    }

    /**
     * 상태 전이와 알림 발송의 대상이 되는 활성 회원 수. 시드에서 약 95% 다.
     *
     * <p><b>7번의 모든 대사식이 이 값 위에 선다.</b> 정상 실행의 발송 수도, 재실행 뒤의 고유
     * 수신자 수도 이 값이어야 한다.
     *
     * @return 대상 회원 수
     */
    static long targetCount() {
        return members().stream()
                .filter(member -> member.getStatus() == OutboxJobCommonConfig.FROM_STATUS)
                .count();
    }

    /**
     * 시드에 이미 휴면인 회원 수. <b>이 배치가 건드리면 안 되는 행</b>이다.
     *
     * @return 휴면 회원 수
     */
    static long seededDormantCount() {
        return COUNT - targetCount();
    }

    /**
     * 유령 알림 / 중복 발송의 크기. <b>실패한 청크 하나</b>다.
     *
     * <p>구조적 상수라는 점이 중요하다. 실패가 어디서 나든 피해는 청크 하나이고, 그래서 7번에서
     * 청크 크기는 성능의 다이얼이 아니라 <b>사고의 단위</b>다.
     *
     * @return 청크 크기
     */
    static long phantomCount() {
        return OutboxJobCommonConfig.CHUNK_SIZE;
    }

    /**
     * 대상 회원에게 나가야 할 멱등키. <b>정확히 한 번씩</b> 나가야 한다.
     *
     * @return 멱등키 집합. {@code id} 오름차순
     */
    static Set<String> expectedKeys() {
        Set<String> keys = new LinkedHashSet<>();
        members().stream()
                .filter(member -> member.getStatus() == OutboxJobCommonConfig.FROM_STATUS)
                .forEach(member -> keys.add(NotificationIdempotencyKey.of(member.getId())));
        return keys;
    }

    /**
     * 대상 회원의 식별자. 릴레이가 {@code id} 순으로 보냈는지 확인하는 데 쓴다.
     *
     * @return 식별자. 오름차순
     */
    static List<Long> targetIds() {
        return members().stream()
                .filter(member -> member.getStatus() == OutboxJobCommonConfig.FROM_STATUS)
                .map(MemberBase::getId)
                .toList();
    }

    /**
     * 시딩과 같은 데이터. {@code index - 1} 번째 원소가 {@code id = index} 인 행이다.
     *
     * @return 회원 목록. {@code id} 오름차순
     */
    static List<MemberBase> members() {
        MemberSeedGenerator generator = MemberTableSeeder.generator(MemberG::new, 0, false);
        List<MemberBase> members = new ArrayList<>((int) COUNT);
        for (long id = 1; id <= COUNT; id++) {
            members.add(generator.generate(id));
        }
        return members;
    }
}
