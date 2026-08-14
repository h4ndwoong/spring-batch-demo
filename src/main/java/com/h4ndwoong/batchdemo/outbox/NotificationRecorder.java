package com.h4ndwoong.batchdemo.outbox;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 외부로 나간 알림을 순서대로 모은다. <b>7번 문제의 주 측정 장치</b>이며 양쪽 공통이다.
 *
 * <p><b>왜 새 장치가 필요한가</b><br>
 * 1~6번의 지표는 전부 DB 가 알고 있었다 — {@code SHOW GLOBAL STATUS} 의 문장 수, 스캔 행 수,
 * Step 통계의 읽기·쓰기 건수. 7번의 주 지표인 <b>발송 건수는 DB 도 배치도 모른다.</b> 알림은
 * 정의상 DB 밖에서 일어나는 일이고, 그래서 그것을 재는 눈금도 밖에 있어야 한다.
 *
 * <p><b>이 기록은 롤백되지 않는다.</b> 그것이 버그가 아니라 <b>재현하려는 성질 그 자체</b>다.
 * 트랜잭션이 되돌리는 것은 DB 의 상태뿐이고, 이 리스트에 남은 흔적은 실무에서 이미 발송된 문자에
 * 대응한다.
 *
 * <p><b>{@link #reset()} 을 리스너로 만들지 않는다.</b> 6번의 {@code SliceUpdateRecorder} 는 Step 이
 * 시작할 때마다 기록을 비웠다. 여기서 같은 일을 하면 7번의 주 지표가 사라진다 — <b>중복 발송은
 * 실행을 가로질러야만 보이기 때문</b>이다 (실행 1에서 나간 알림과 실행 2에서 나간 알림을 함께
 * 세야 한다). 그래서 비우는 시점은 테스트가 정한다. CLI 실행은 프로세스가 매번 새로 뜨므로
 * 누적이 끊기는데, 그 경우를 위한 원자료가 {@link LoggingNotificationSender} 의 로그 한 줄이다.
 *
 * <p><b>스레드 안전하지 않다.</b> 이 실습의 Step 은 모두 단일 스레드다. 멀티스레드 Step 을 쓰게
 * 되면 그때 동기화를 넣는 것이 아니라, <b>측정치를 신뢰할 수 있는지부터 다시 물어야 한다.</b>
 */
public class NotificationRecorder {

    private final List<SendAttempt> attempts = new ArrayList<>();

    /**
     * 발송 한 건을 기록한다. {@link NotificationSender} 구현이 실제로 내보낸 <b>직후</b>에 부른다.
     *
     * @param message 나간 메시지
     */
    public void record(NotificationMessage message) {
        attempts.add(new SendAttempt(message.memberId(), message.idempotencyKey()));
    }

    /**
     * 기록을 비운다. <b>테스트가 메서드마다 부른다.</b>
     *
     * <p>Step 리스너로 만들지 않은 이유는 클래스 주석에 적었다.
     */
    public void reset() {
        attempts.clear();
    }

    /**
     * 나간 알림의 총 수. <b>대상 회원 수보다 크면 그 초과분이 곧 중복이다.</b>
     *
     * @return 발송 시도 수
     */
    public long attemptCount() {
        return attempts.size();
    }

    /**
     * 서로 다른 멱등키의 수. <b>실제로 알림을 받은 사람의 수</b>다.
     *
     * @return 고유 키 수
     */
    public long distinctKeyCount() {
        return keys().size();
    }

    /**
     * 중복 발송 수. 같은 알림이 두 번 나간 횟수다.
     *
     * <p>after 는 0 이어야 하고 before 는 실패한 청크의 크기만큼 나온다. <b>이 값이 7번의 결승선
     * 하나다.</b>
     *
     * @return 중복 수
     */
    public long duplicateCount() {
        return attemptCount() - distinctKeyCount();
    }

    /**
     * 알림을 받은 회원 식별자. 유령 알림을 가리려면 DB 의 상태와 대조해야 한다.
     *
     * @return 회원 식별자. 발송 순서를 유지한다
     */
    public Set<Long> memberIds() {
        Set<Long> ids = new LinkedHashSet<>();
        attempts.forEach(attempt -> ids.add(attempt.memberId()));
        return ids;
    }

    /**
     * 나간 멱등키. 발송 순서를 유지한다.
     *
     * @return 고유 키 집합
     */
    public Set<String> keys() {
        Set<String> keys = new LinkedHashSet<>();
        attempts.forEach(attempt -> keys.add(attempt.idempotencyKey()));
        return keys;
    }

    /**
     * 나간 순서 그대로의 기록. 릴레이가 {@code id} 순으로 보냈는지 확인하는 데 쓴다.
     *
     * @return 발송 기록. 순서를 유지하는 불변 목록
     */
    public List<SendAttempt> attempts() {
        return List.copyOf(attempts);
    }

    /**
     * 사람이 읽는 한 줄 요약.
     *
     * @return 요약 문자열
     */
    public String summary() {
        return "sendAttempts=%d, distinctKeys=%d, duplicateSends=%d"
                .formatted(attemptCount(), distinctKeyCount(), duplicateCount());
    }
}
