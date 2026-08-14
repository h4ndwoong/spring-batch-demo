package com.h4ndwoong.batchdemo.restart;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.domain.MemberE;
import com.h4ndwoong.batchdemo.domain.MemberStatus;
import com.h4ndwoong.batchdemo.seed.MemberSeedGenerator;
import com.h4ndwoong.batchdemo.support.MemberTableSeeder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 5번 문제의 before/after 통합 테스트가 <b>함께 쓰는</b> 기대값.
 *
 * <p>4번의 {@code LookupFixture} 와 같은 이유로 기대값을 상수로 박지 않고 계산한다. 실행 결과를
 * 옮겨 적으면 그 시험은 "지난번과 같다" 만 말한다. 여기서는 {@link MemberSeedGenerator} 로 같은
 * 데이터를 만든 뒤 <b>배치를 거치지 않고 메모리에서</b> 소멸 결과를 계산한다.
 *
 * <p><b>4번과 다른 점이 하나 있다.</b> 4번의 기대값은 "한 번 처리했을 때의 답" 하나였지만, 5번은
 * <b>몇 번 처리했느냐가 매개변수</b>다 ({@link #expectedPointSum(int)}). 멱등성이란 이 함수가
 * 상수여야 한다는 뜻이고, before 는 그렇지 않다.
 */
final class RestartFixture {

    /** 통합 테스트가 다룰 행 수. 청크 크기의 배수로 잡아 청크 수가 딱 떨어지게 한다. */
    static final long COUNT = 20_000L;

    /**
     * 장애를 심을 지점. 이만큼 커밋한 뒤 실패한다.
     *
     * <p>청크 크기({@value RestartJobCommonConfig#CHUNK_SIZE})의 배수여야 "정확히 N 건이 커밋된
     * 상태" 가 만들어진다. 활성 회원 수보다 작아야 실패 뒤에 할 일이 남는다.
     */
    static final long FAIL_AFTER = 10_000L;

    private RestartFixture() {
    }

    /**
     * {@code member_e} 를 테스트 데이터로 채운다.
     *
     * @param jdbcTemplate JDBC 템플릿
     */
    static void seed(JdbcTemplate jdbcTemplate) {
        MemberTableSeeder.seed(jdbcTemplate, "member_e", MemberE::new, COUNT, 0);
    }

    /**
     * 소멸 대상이 되는 활성 회원 수. 시드에서 약 95% 다.
     *
     * @return 활성 회원 수
     */
    static long activeCount() {
        return members().stream().filter(member -> member.getStatus() == MemberStatus.ACTIVE).count();
    }

    /**
     * 소멸 전 포인트 총합.
     *
     * @return 총합
     */
    static long initialPointSum() {
        return members().stream().mapToLong(MemberBase::getPoint).sum();
    }

    /**
     * 활성 회원 전체를 {@code passes} 번 소멸시켰을 때의 포인트 총합.
     *
     * <p><b>이 메서드가 5번 문제의 시험지다.</b> 멱등한 배치라면 몇 번을 실행해도 결과가
     * {@code expectedPointSum(1)} 이어야 한다. before 는 재실행 횟수만큼 이 값이 내려간다.
     *
     * @param passes 전량 소멸이 일어난 횟수
     * @return 기대 총합
     */
    static long expectedPointSum(int passes) {
        return initialPointSum() - passes * activeCount() * RestartJobCommonConfig.EXPIRE_AMOUNT;
    }

    /**
     * 앞 {@code processed} 건만 소멸시킨 상태의 포인트 총합. 실패한 실행의 기대값이다.
     *
     * @param processed 커밋된 건수
     * @return 기대 총합
     */
    static long partialPointSum(long processed) {
        return initialPointSum() - processed * RestartJobCommonConfig.EXPIRE_AMOUNT;
    }

    /**
     * 활성 회원 전체를 {@code passes} 번 소멸시켰을 때 포인트가 음수가 되는 행 수.
     *
     * <p>1회에서는 0 이고 2회에서는 0 이 아니어야 한다 — 그것이 <b>총합을 되돌려도 복구되지 않는
     * 피해</b>의 크기다.
     *
     * @param passes 전량 소멸이 일어난 횟수
     * @return 음수가 되는 행 수
     */
    static long negativeRows(int passes) {
        long deducted = passes * RestartJobCommonConfig.EXPIRE_AMOUNT;
        return members().stream()
                .filter(member -> member.getStatus() == MemberStatus.ACTIVE)
                .filter(member -> member.getPoint() - deducted < 0)
                .count();
    }

    /**
     * 시딩과 같은 데이터. {@code index - 1} 번째 원소가 {@code id = index} 인 행이다.
     *
     * @return 회원 목록. {@code id} 오름차순
     */
    static List<MemberBase> members() {
        MemberSeedGenerator generator = MemberTableSeeder.generator(MemberE::new, 0, false);
        List<MemberBase> members = new ArrayList<>((int) COUNT);
        for (long id = 1; id <= COUNT; id++) {
            members.add(generator.generate(id));
        }
        return members;
    }
}
