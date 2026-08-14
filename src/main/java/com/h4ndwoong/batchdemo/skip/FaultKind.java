package com.h4ndwoong.batchdemo.skip;

import org.springframework.dao.TransientDataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;

/**
 * {@link FaultInjectingItemWriter} 가 심을 장애의 종류. 2번 문제의 <b>예외 분류</b> 실습용이다.
 *
 * <p>after 구성은 예외를 세 부류로 나눠 다르게 대한다.
 * <ol>
 *   <li><b>데이터 오류</b>({@link MemberValidationException}) — 스킵하고 격리한다. 그 행 하나만
 *       문제이므로 나머지 10만 건을 인질로 잡을 이유가 없다.</li>
 *   <li><b>일시 오류</b>({@link #TRANSIENT}) — 재시도한다. 데이터는 멀쩡하므로 스킵하면 정상 행을
 *       잃는다.</li>
 *   <li><b>그 밖의 오류</b>({@link #FATAL}) — 실패한다. 원인을 모르는 예외를 건너뛰면 배치가
 *       절반을 버리고도 {@code COMPLETED} 로 끝난다.</li>
 * </ol>
 * 이 enum 은 2번과 3번을 실행 중에 직접 재현하기 위한 것이다.
 */
public enum FaultKind {

    /**
     * 일시적 자원 장애. 락 타임아웃·커넥션 끊김 같은 것들의 대역이다.
     *
     * <p>{@link TransientDataAccessException} 의 하위 타입이라 after 의
     * {@code .retry(TransientDataAccessException.class)} 에 걸린다. 실제 락 경합은 재현이
     * 비결정적이라 실습에서 쓸 수 없으므로, <b>같은 재시도 경로를 타는 예외</b>를 대신 심는다.
     */
    TRANSIENT {
        @Override
        public RuntimeException create(long id, int attempt) {
            return new TransientDataAccessResourceException(
                    "주입한 일시 장애: id=" + id + " 청크의 " + attempt + "번째 시도");
        }
    },

    /**
     * 분류되지 않은 오류. 코드 버그의 대역이다.
     *
     * <p>스킵 목록에도 재시도 목록에도 없으므로 Step 을 실패시켜야 한다. "after 는 뭘 해도
     * COMPLETED 로 끝난다" 가 아님을 보이는 것이 이 값의 존재 이유다.
     */
    FATAL {
        @Override
        public RuntimeException create(long id, int attempt) {
            return new IllegalStateException(
                    "주입한 치명적 오류: id=" + id + " 청크의 " + attempt + "번째 시도");
        }
    };

    /**
     * 던질 예외를 만든다.
     *
     * @param id      장애를 심은 청크의 첫 행 식별자
     * @param attempt 이 청크에 대한 시도 순번. 1부터 시작한다
     * @return 던질 예외
     */
    public abstract RuntimeException create(long id, int attempt);
}
