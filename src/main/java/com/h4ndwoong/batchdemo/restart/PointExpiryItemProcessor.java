package com.h4ndwoong.batchdemo.restart;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import org.springframework.batch.item.ItemProcessor;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 포인트를 정해진 액수만큼 소멸시킨다. <b>before 와 after 가 문자 그대로 같은 클래스를 쓴다.</b>
 *
 * <p>5번 문제에서 달라지는 것은 "무엇을 계산하는가" 가 아니라 <b>"그 계산을 했다는 사실을 어디에
 * 남기는가"</b> 다. 그래서 계산은 여기 한 곳에 두고, 흔적 남기기는
 * {@link ProcessMarkingItemProcessor} 가 이 프로세서를 감싸서 더한다. 4번 문제가 프로세서를
 * 공유하고 조회 전략만 갈랐던 것과 같은 구조다.
 *
 * <p><b>잔액을 검사하지 않는다.</b> {@link MemberBase#deductPoint(long, LocalDateTime)} 의 Javadoc 에
 * 적힌 그대로다 — 여기서 예외를 던지면 이중 소멸 대신 예외가 나면서 재현하려던 증상이 바뀐다.
 * 포인트가 음수로 내려간 것 자체가 <b>이중 차감의 물증</b>이고, 5번의 부수 지표({@code negativeRows})가
 * 그것을 센다. 잔액 검사가 필요한 배치라면 그 검사는 2번 문제(검증과 격리)의 주제다.
 *
 * <p><b>{@code null} 을 돌려주지 않는다.</b> 필터링이 일어나면 {@code READ = WRITE} 대사가 깨져
 * "몇 건을 처리했는가" 가 흐려진다. 대상 선별은 전부 리더의 {@code WHERE} 절이 한다.
 */
public class PointExpiryItemProcessor implements ItemProcessor<MemberBase, MemberBase> {

    private final long amount;
    private final Clock clock;

    /**
     * 프로세서를 만든다.
     *
     * @param amount 소멸시킬 포인트
     * @param clock  {@code updated_at} 의 출처
     */
    public PointExpiryItemProcessor(long amount, Clock clock) {
        this.amount = amount;
        this.clock = clock;
    }

    /**
     * {@inheritDoc}
     *
     * @param item 읽어 온 회원. 이 인스턴스를 그대로 바꿔 돌려준다
     * @return 포인트가 차감된 회원
     */
    @Override
    public MemberBase process(MemberBase item) {
        item.deductPoint(amount, LocalDateTime.now(clock));
        return item;
    }
}
