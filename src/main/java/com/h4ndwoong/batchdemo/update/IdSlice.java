package com.h4ndwoong.batchdemo.update;

import java.util.ArrayList;
import java.util.List;

/**
 * 갱신할 {@code id} 구간 한 칸. 6번 문제 after 의 <b>작업 단위</b>다.
 *
 * <p>after 가 읽는 것은 회원이 아니라 <b>일감</b>이다. 100만 행을 애플리케이션으로 끌어올려 한 행씩
 * 갱신하는 대신, "이 구간을 규칙대로 맞춰라" 는 지시 한 장을 서버에 보낸다. 그래서 Step 의
 * {@code READ_COUNT} 가 100만이 아니라 슬라이스 수(기본 20)가 되며, <b>6번은 Step 통계로
 * before 와 비교할 수 없는 첫 문제</b>가 된다. 비교 축은 왕복 횟수와 갱신 행 수다.
 *
 * <p><b>왜 한 문장으로 끝내지 않고 쪼개는가</b><br>
 * 집합 UPDATE 는 왕복을 없애는 대신 <b>락 단위를 키운다.</b> before 는 청크(1,000행)마다 커밋해
 * 락을 놓아주지만, 분할하지 않은 집합 UPDATE 는 대상 전체를 커밋까지 붙잡는다. 100만 행을 한
 * 트랜잭션에 담으면 그동안 그 구간을 건드리는 모든 세션이 멈추고, 언두 로그와 복제 지연도 함께
 * 커진다. <b>슬라이스 크기가 "왕복이냐 락이냐" 의 다이얼</b>이고, 6번이 1~5번과 달리 트레이드오프를
 * 갖는 이유가 여기 있다.
 *
 * @param index  1부터 세는 슬라이스 순번. 보고와 로그에서만 쓴다
 * @param fromId 구간의 시작 {@code id}. 포함한다
 * @param toId   구간의 끝 {@code id}. <b>포함한다</b> ({@code BETWEEN} 과 같은 경계)
 */
public record IdSlice(int index, long fromId, long toId) {

    /**
     * 슬라이스를 만든다.
     *
     * @throws IllegalArgumentException 순번이 1 미만이거나 구간이 뒤집혔을 때
     */
    public IdSlice {
        if (index < 1) {
            throw new IllegalArgumentException("슬라이스 순번은 1부터 시작한다: " + index);
        }
        if (fromId > toId) {
            throw new IllegalArgumentException(
                    "구간이 뒤집혔다: from=%d, to=%d".formatted(fromId, toId));
        }
    }

    /**
     * {@code [minId, maxId]} 를 {@code sliceSize} 크기로 자른다.
     *
     * <p><b>빈틈도 겹침도 없어야 한다.</b> 빈틈이 생기면 그 구간의 회원은 등급이 갱신되지 않은 채
     * 배치가 {@code COMPLETED} 로 끝나고, 겹치면 같은 행을 두 번 갱신한다 (두 번째는 조건
     * {@code grade <> ...} 에 걸려 0행이 되므로 조용히 왕복만 낭비한다). 어느 쪽도 로그에 나타나지
     * 않으므로 여기서 시험으로 고정한다.
     *
     * <p>{@code id} 에 구멍이 있어도 상관없다. 구간은 <b>존재하는 행이 아니라 키 공간</b>을 나누므로,
     * 어떤 슬라이스가 0행을 갱신하는 것은 정상이다.
     *
     * @param minId     최소 {@code id}
     * @param maxId     최대 {@code id}
     * @param sliceSize 슬라이스 하나가 덮을 {@code id} 개수. <b>0 이하면 분할하지 않는다</b>
     *                  (한 문장이 전 구간을 잠그는, 락 비용을 재기 위한 극단값이다)
     * @return 슬라이스 목록. {@code minId > maxId} 면 빈 목록
     */
    public static List<IdSlice> of(long minId, long maxId, long sliceSize) {
        if (minId > maxId) {
            return List.of();
        }
        long size = sliceSize <= 0 ? maxId - minId + 1 : sliceSize;

        List<IdSlice> slices = new ArrayList<>();
        long from = minId;
        int index = 1;
        while (from <= maxId) {
            long to = Math.min(maxId, from + size - 1);
            slices.add(new IdSlice(index++, from, to));
            from = to + 1;
        }
        return List.copyOf(slices);
    }

    /**
     * 이 구간이 덮는 {@code id} 개수. 실제 행 수가 아니라 <b>키 공간의 폭</b>이다.
     *
     * @return 폭
     */
    public long width() {
        return toId - fromId + 1;
    }
}
