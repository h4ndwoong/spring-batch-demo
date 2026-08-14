package com.h4ndwoong.batchdemo.paging;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import org.springframework.batch.item.database.AbstractPagingItemReader;

import java.util.List;

/**
 * 페이지를 가져오는 <b>시간을 재는</b> 페이징 리더의 골격. 3번 문제의 두 구현이 여기서 갈린다.
 *
 * <p><b>확장점은 {@link #fetchPage} 하나뿐이다.</b> offset 이든 키셋이든 커서든, 달라지는 것은
 * "한 페이지를 어떤 SQL 로 가져오는가" 이고 나머지(페이지 산술, 계측, 상태 관리)는 모두 같다.
 * 그래서 {@code doReadPage()} 를 {@code final} 로 닫고 SQL 만 위임한다. 하위 클래스가 계측을
 * 빠뜨리거나 다르게 재면 before/after 비교가 성립하지 않기 때문이다.
 *
 * <p><b>왜 데코레이터가 아니라 상위 클래스인가</b><br>
 * 리더를 감싸는 데코레이터는 {@code read()} 호출만 볼 수 있다. 그런데 페이지 획득은 페이지의
 * <em>첫</em> {@code read()} 안에서 일어나므로, 데코레이터로 재면 SQL 시간과 행 매핑 시간이 섞이고
 * 몇 번째 호출이 페이지 경계인지 알기 위해 페이징 산술을 한 벌 더 구현해야 한다.
 * {@link AbstractPagingItemReader#doReadPage()} 를 감싸면 <b>SQL 발행 구간만</b> 정확히 잡힌다.
 *
 * <p><b>재시작을 지원하지 않는다.</b> 생성자에서 {@code saveState(false)} 로 못을 박는다.
 * {@link AbstractPagingItemReader#jumpToItem(int)} 은 "몇 번째 페이지" 만 복원하는데, 키셋 리더의
 * 위치는 페이지 번호가 아니라 <b>마지막으로 읽은 {@code id}</b> 라서 복원되지 않는다. 그 상태로
 * 재시작하면 조용히 틀린 구간을 읽는다. 재시작 멱등성은 5번 문제의 주제이므로, 여기서는 조용히
 * 틀리는 대신 <b>지원하지 않는다고 선언</b>한다.
 */
public abstract class MeasuredPagingItemReader extends AbstractPagingItemReader<MemberBase> {

    private final PageTimingRecorder recorder;

    /**
     * 리더를 만든다.
     *
     * @param name     리더 이름. 로그와 실행 컨텍스트 키에 쓰인다
     * @param pageSize 페이지 크기. before/after 가 같아야 한다
     * @param recorder 페이지별 소요 시간을 받을 측정 장치
     */
    protected MeasuredPagingItemReader(String name, int pageSize, PageTimingRecorder recorder) {
        setName(name);
        setPageSize(pageSize);
        setSaveState(false);
        this.recorder = recorder;
    }

    /**
     * {@inheritDoc}
     *
     * <p>페이지 획득 SQL 1회를 재서 {@link PageTimingRecorder} 에 넘긴다. 여기에는 행 매핑 시간이
     * 포함된다 — {@code RowMapper} 가 결과셋을 도는 것까지가 한 번의 조회이고, 그 비용은 양쪽이
     * 같으므로 비교 축을 흔들지 않는다. 빠지는 것은 청크 처리와 커밋이다.
     */
    @Override
    protected final void doReadPage() {
        int pageNumber = getPage() + 1;
        long start = System.nanoTime();
        List<MemberBase> page = fetchPage(getPage(), getPageSize());
        long elapsed = System.nanoTime() - start;

        results = page;
        recorder.record(pageNumber, page.size(), elapsed);
    }

    /**
     * 페이지 한 장을 가져온다. <b>이 메서드가 before 와 after 의 차이 전부다.</b>
     *
     * @param pageIndex 0부터 세는 페이지 색인
     * @param pageSize  한 페이지의 행 수
     * @return 그 페이지의 행들. 더 없으면 빈 리스트. {@code null} 을 돌려주면 안 된다
     */
    protected abstract List<MemberBase> fetchPage(int pageIndex, int pageSize);

    /**
     * 실제로 발행하는 SQL. 테스트가 "정말 OFFSET 을 쓰는가" 를 확인하는 데 쓴다.
     *
     * <p>이 문제의 before/after 차이는 코드 구조가 아니라 <b>SQL 한 줄</b>이므로, 그 한 줄이
     * 의도한 모양인지는 실행 결과가 아니라 문자열로 확인하는 것이 가장 확실하다.
     *
     * @return 페이지 획득 SQL
     */
    public abstract String sql();

    /**
     * {@inheritDoc}
     *
     * @throws UnsupportedOperationException 항상. 이 리더들은 재시작을 지원하지 않는다
     */
    @Override
    protected void jumpToItem(int itemIndex) {
        throw new UnsupportedOperationException(
                "페이징 리더는 재시작을 지원하지 않는다 (saveState=false). 키셋 리더의 위치는 "
                        + "페이지 번호가 아니라 마지막으로 읽은 id 라서 복원되지 않는다. "
                        + "재시작 멱등성은 5번 문제(restartJob)의 주제다.");
    }
}
