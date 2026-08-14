package com.h4ndwoong.batchdemo.update;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.domain.MemberGrade;
import com.h4ndwoong.batchdemo.support.GradePolicy;
import org.springframework.batch.item.ItemProcessor;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 포인트로 등급을 다시 매기고, <b>바뀌지 않는 행은 버린다.</b> 6번 문제 before 의 가공이다.
 *
 * <p><b>{@code null} 을 돌려주는 것이 여기서는 옳다.</b> 2번 문제에서 "가공은 {@code null} 을
 * 돌려주지 않는다"고 정했던 것과 반대로 보이지만, 그때의 이유는 <em>검증 실패를 필터로 감추면
 * {@code READ = WRITE + SKIP} 대사가 깨진다</em>였다. 6번의 필터는 감추는 것이 없다 — 등급이 이미
 * 옳은 행에는 <b>보낼 UPDATE 가 없다.</b> 그리고 이 필터가 있어야 after 의 조건
 * ({@code AND grade <> CASE ...}) 과 <b>정확히 같은 일</b>이 된다. 한쪽만 무변경 행을 건너뛰면
 * 갱신 행 수가 달라져 "왕복만 줄었다" 가 성립하지 않는다.
 *
 * <p>결과적으로 Step 통계에는 {@code FILTER_COUNT} 로 잡히고, {@code READ = WRITE + FILTER} 가
 * 성립한다.
 *
 * <p><b>정책은 주입받는다.</b> {@link GradePolicy} 는 Step 시작 시 데이터에서 산출되며(4번 문제의
 * 정책 로딩), 이 클래스는 그 값을 적용만 한다. 시각도 {@link Clock} 으로 받아 테스트가 고정할 수
 * 있게 한다.
 */
public class GradeAssigningItemProcessor implements ItemProcessor<MemberBase, MemberBase> {

    private final GradePolicy gradePolicy;
    private final Clock clock;

    /**
     * 프로세서를 만든다.
     *
     * @param gradePolicy 등급 정책
     * @param clock       {@code updated_at} 의 출처
     */
    public GradeAssigningItemProcessor(GradePolicy gradePolicy, Clock clock) {
        this.gradePolicy = gradePolicy;
        this.clock = clock;
    }

    /**
     * {@inheritDoc}
     *
     * @param item 읽은 회원
     * @return 등급이 바뀐 회원. 바뀌지 않으면 {@code null} (쓰지 않는다)
     */
    @Override
    public MemberBase process(MemberBase item) {
        MemberGrade recalculated = gradePolicy.gradeOf(item.getPoint());
        if (recalculated == item.getGrade()) {
            return null;
        }
        item.changeGrade(recalculated, LocalDateTime.now(clock));
        return item;
    }
}
