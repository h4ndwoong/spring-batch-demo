package com.h4ndwoong.batchdemo.seed;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import org.springframework.batch.item.support.AbstractItemCountingItemStreamItemReader;

/**
 * {@link MemberSeedGenerator} 로 지정한 건수만큼 회원을 만들어 내는 리더. 읽을 원본 데이터가 없다.
 *
 * <p>{@link AbstractItemCountingItemStreamItemReader} 를 상속해 읽은 건수를
 * {@code ExecutionContext} 에 저장하므로, Step 이 실패해도 재시작 시 남은 구간만 생성한다.
 *
 * <p>{@link #jumpToItem(int)} 를 빈 구현으로 덮은 것이 핵심이다. 기본 구현은 건너뛸 구간을
 * {@code doRead()} 로 하나씩 읽어 버리는데(파일이나 커서 리더에는 그래야 한다), 이 리더는 데이터가
 * 인덱스만으로 결정되므로 <b>건너뛸 것이 없다</b>. 200만 건 중 190만 건째에서 재시작해도
 * 앞 190만 건을 다시 생성하지 않는다.
 */
public class MemberSeedItemReader extends AbstractItemCountingItemStreamItemReader<MemberBase> {

    private final MemberSeedGenerator generator;

    /**
     * 리더를 만든다.
     *
     * @param generator 회원 생성기
     * @param count     생성할 건수
     * @throws ArithmeticException {@code count} 가 {@code int} 범위를 넘을 때. 상위 클래스의
     *                             건수 추적이 {@code int} 라서 생기는 제약이며, 실습 최대 규모인
     *                             200만 건은 문제되지 않는다
     */
    public MemberSeedItemReader(MemberSeedGenerator generator, long count) {
        this.generator = generator;
        setName(MemberSeedItemReader.class.getSimpleName());
        setMaxItemCount(Math.toIntExact(count));
    }

    /**
     * {@inheritDoc}
     *
     * <p>상위 클래스가 건수를 먼저 증가시킨 뒤 호출하므로 {@code getCurrentItemCount()} 는
     * 1부터 시작하는 순번이며, 그대로 생성기의 인덱스로 쓴다.
     */
    @Override
    protected MemberBase doRead() {
        return generator.generate(getCurrentItemCount());
    }

    /** 열어 둘 자원이 없다. */
    @Override
    protected void doOpen() {
    }

    /** 닫을 자원이 없다. */
    @Override
    protected void doClose() {
    }

    /**
     * 아무것도 하지 않는다. 데이터가 인덱스로부터 결정되므로 재시작 지점까지 건너뛸 필요가 없다.
     *
     * @param itemIndex 재시작할 순번. 상위 클래스가 이 값으로 건수를 복원하므로 여기서는 쓰지 않는다
     */
    @Override
    protected void jumpToItem(int itemIndex) {
    }
}
