package com.h4ndwoong.batchdemo.outbox;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.domain.MemberStatus;
import org.springframework.batch.item.ItemProcessor;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 회원의 상태를 전이시킨다. <b>양쪽이 같은 클래스, 같은 빈을 쓴다.</b>
 *
 * <p>7번에서 프로세서는 비교 축이 아니다. 달라지는 것은 이 계산의 결과를 <b>어디에 쓰고 언제
 * 보내는가</b>뿐이다. 그래서 이 클래스는 알림을 알지 못한다 — 상태를 바꿀 뿐이고, 그 변경으로부터
 * 알림을 만드는 것은 {@link StatusChangedNotification} 의 일이다.
 *
 * <p><b>전이 대상이 아니면 {@code null} 을 돌려준다.</b> 리더가 이미 {@code status = 'ACTIVE'} 로
 * 걸러 오므로 이 분기는 실행되지 않아야 하고, {@code FILTER_COUNT} 가 0 이 아니면 리더의 조건과
 * 프로세서의 판단이 어긋났다는 신호다. 조건이 두 곳에 있는 것이 아니라 <b>한 곳의 조건을 다른
 * 곳이 검산</b>하는 구조다.
 *
 * <p><b>시각을 시계에서 받는다.</b> 여기서 정한 {@code updatedAt} 이 그대로 알림의 시각이 된다
 * ({@link StatusChangedNotification} 참고). 시각의 출처가 하나여야 before 와 after 의 메시지가
 * 같아진다.
 */
public class StatusTransitionItemProcessor implements ItemProcessor<MemberBase, MemberBase> {

    private final MemberStatus from;
    private final MemberStatus to;
    private final Clock clock;

    /**
     * 프로세서를 만든다.
     *
     * @param from  전이 대상 상태
     * @param to    전이 후 상태
     * @param clock {@code updatedAt} 의 출처
     */
    public StatusTransitionItemProcessor(MemberStatus from, MemberStatus to, Clock clock) {
        this.from = from;
        this.to = to;
        this.clock = clock;
    }

    /**
     * {@inheritDoc}
     *
     * @param member 읽어온 회원
     * @return 상태가 바뀐 회원. 전이 대상이 아니면 {@code null}
     */
    @Override
    public MemberBase process(MemberBase member) {
        if (member.getStatus() != from) {
            return null;
        }
        member.changeStatus(to, LocalDateTime.now(clock));
        return member;
    }
}
