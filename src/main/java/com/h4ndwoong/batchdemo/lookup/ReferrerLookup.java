package com.h4ndwoong.batchdemo.lookup;

import java.util.Optional;

/**
 * 추천인을 조회한다. <b>4번 문제의 확장점이자 before/after 의 차이 전부</b>다.
 *
 * <p>3번 문제가 {@code MeasuredPagingItemReader.fetchPage} 하나로 갈렸듯이, 4번은 이 인터페이스
 * 하나로 갈린다. 리더도 프로세서도 라이터도 청크 크기도 양쪽이 같고,
 * {@link PerItemReferrerLookup}(행당 2회 SELECT)과 {@link ChunkedReferrerLookup}(청크당 1회
 * {@code IN} 조회) 중 무엇을 주입하는지만 다르다.
 *
 * <p><b>구현이 지켜야 하는 계약</b> — 이 넷이 깨지면 두 전략의 산정 결과가 갈라져 비교 자체가
 * 성립하지 않는다. {@code ReferrerLookupContract} 가 두 구현에 같은 시험을 돌려 확인한다.
 * <ol>
 *   <li>{@code find(null)} 은 {@link Optional#empty()} 다. 추천인이 없는 것은 오류가 아니다
 *       ({@code member_d} 의 {@code id=1} 이 그렇다).</li>
 *   <li>존재하지 않는 {@code id} 도 {@link Optional#empty()} 다. <b>예외를 던지지 않는다</b> —
 *       오류 행 처리는 2번 문제의 주제이고, 여기서 예외가 나면 재현하려던 증상이 바뀐다.</li>
 *   <li>같은 입력 순서열에는 같은 출력 순서열을 돌려준다. 조회를 언제 몇 개씩 묶는지는 자유지만
 *       <b>답이 달라지면 안 된다.</b></li>
 *   <li>인자를 변형하지 않는다.</li>
 * </ol>
 *
 * <p><b>계측이 이 인터페이스에 있는 이유</b><br>
 * 몇 번의 왕복으로 답했는지는 조회 전략 자신만 안다. 프로세서도, Step 도, 서버 전역 카운터도
 * 정확히는 모른다({@link ReferrerLookupStats} 참고). 그래서 재는 주체가 아는 것을 열어 두고,
 * <b>보고는 {@link ReferrerLookupReporter} 가</b> 한다 — 조회 전략이 바뀌는 이유(SQL 묶는 방식)와
 * 보고가 바뀌는 이유(표 형식)는 다르기 때문이다.
 */
public interface ReferrerLookup {

    /**
     * 추천인을 조회한다.
     *
     * @param referrerId 추천인 식별자. 추천인이 없는 행이면 {@code null}
     * @return 추천인. {@code referrerId} 가 {@code null} 이거나 그런 행이 없으면 {@link Optional#empty()}
     */
    Optional<Referrer> find(Long referrerId);

    /**
     * 지금까지의 조회 계측치.
     *
     * @return 계측치. 한 번도 조회하지 않았으면 {@link ReferrerLookupStats#EMPTY}
     */
    ReferrerLookupStats stats();

    /**
     * 계측치와 내부 상태를 초기화한다. Step 시작 시 {@link ReferrerLookupReporter} 가 부른다.
     *
     * <p>비우지 않으면 한 컨텍스트에서 Job 을 두 번 실행할 때 두 번째 실행의 보고에 첫 실행의
     * 왕복이 섞인다 (3번의 {@code PageTimingRecorder} 가 같은 이유로 {@code beforeStep} 에서 비운다).
     */
    void reset();
}
