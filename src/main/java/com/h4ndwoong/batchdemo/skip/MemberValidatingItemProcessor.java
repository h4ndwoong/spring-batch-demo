package com.h4ndwoong.batchdemo.skip;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import org.springframework.batch.item.ItemProcessor;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 행을 검증하고 처리 완료로 표시한다. 2번 문제에서 <b>예외가 발생하는 유일한 정상 경로</b>다.
 *
 * <p><b>계약</b>
 * <ul>
 *   <li>유효한 행 → 같은 인스턴스를 가공해 반환한다.</li>
 *   <li>오염된 행 → {@link MemberValidationException} 을 던진다.</li>
 *   <li><b>{@code null} 을 반환하지 않는다.</b></li>
 * </ul>
 *
 * <p>마지막 항목이 중요하다. {@code ItemProcessor} 가 {@code null} 을 돌려주면 그 행은 <em>필터</em>로
 * 집계되어 {@code FILTER_COUNT} 에 잡히고, 예외를 던지면 <em>스킵</em>으로 집계되어
 * {@code SKIP_COUNT} 에 잡힌다. 둘을 섞으면 2번 문제의 대사식
 * ({@code READ = WRITE + SKIP}, {@code SKIP = 격리 테이블 행 수})이 깨진다.
 * 오염 행을 조용히 걸러내는 것은 개선이 아니라 증거 인멸이므로 여기서는 항상 던진다.
 *
 * <p><b>시각을 {@link Clock} 으로 받는 이유</b><br>
 * {@link MemberBase} 의 상태 전이 메서드가 시각을 인자로 받도록 설계되어 있고
 * (그 이유는 {@code MemberBase} 에 적혀 있다), 테스트가 {@code updated_at} 을 값으로 검증할 수
 * 있어야 하기 때문이다.
 */
public class MemberValidatingItemProcessor implements ItemProcessor<MemberBase, MemberBase> {

    private final MemberValidator validator;
    private final Clock clock;

    /**
     * 프로세서를 만든다.
     *
     * @param validator 검증기
     * @param clock     처리 시각의 출처
     */
    public MemberValidatingItemProcessor(MemberValidator validator, Clock clock) {
        this.validator = validator;
        this.clock = clock;
    }

    /**
     * {@inheritDoc}
     *
     * <p>멱등키는 {@code null} 로 남긴다. 멱등키 발급은 5·7번 문제의 주제이고, 2번의 가공은
     * "처리했다는 표시" 까지다. 라이터도 {@code processed} 와 {@code updated_at} 만 쓴다.
     *
     * @param item 읽어들인 회원
     * @return 처리 완료로 표시된 같은 인스턴스. 절대 {@code null} 이 아니다
     * @throws MemberValidationException 검증 위반. after 구성에서는 이 예외만 스킵된다
     */
    @Override
    public MemberBase process(MemberBase item) {
        validator.validate(item);
        item.markProcessed(null, LocalDateTime.now(clock));
        return item;
    }
}
