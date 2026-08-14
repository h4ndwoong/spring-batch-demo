package com.h4ndwoong.batchdemo.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 알림을 "보내는" 구현. 기록기에 남기고 로그 한 줄을 찍는다. <b>양쪽이 같은 빈을 쓴다.</b>
 *
 * <p><b>왜 진짜 API 를 부르지 않는가</b><br>
 * 실습 규칙이기도 하지만 (외부 운영 API 호출 금지), 그보다 <b>진짜 게이트웨이는 before/after 를
 * 같은 축에 놓을 수 없기 때문</b>이다. 네트워크 지연은 실행마다 다르고, 상대가 알아서 중복을 걸러
 * 주면 before 의 증상 자체가 사라진다. 여기서 재현하려는 것은 발송의 <em>속도</em>가 아니라
 * <b>발송이 트랜잭션 밖에 있다는 사실</b>이고, 그 사실은 인메모리 기록으로도 완전히 재현된다 —
 * 리스트에 담긴 것도 로그에 찍힌 것도 롤백이 되돌리지 못한다.
 *
 * <p><b>{@code SEND} 접두 로그가 필요한 이유</b><br>
 * 7번의 결승선은 "두 번의 실행에 걸쳐 몇 건이 나갔는가" 인데, CLI 실행은 실행마다 프로세스가
 * 새로 뜨므로 인메모리 기록이 끊긴다. 로그는 끊기지 않는다. 3번의 {@code PAGE_TIMING},
 * 6번의 {@code SLICE_UPDATE} 와 같은 장치다.
 * <pre>{@code
 * ./gradlew bootRun --args='...' | grep -o 'SEND,.*' > data/outbox-before-1.csv
 * cat data/outbox-before-*.csv | wc -l                       # 발송 시도
 * cat data/outbox-before-*.csv | cut -d, -f2 | sort -u | wc -l  # 실제 수신자
 * }</pre>
 * 그 차이가 중복 발송 건수다.
 *
 * <p><b>로그 비용은 양쪽에 똑같이 걸린다.</b> 한 발송에 한 줄이며, before 는 Step 안에서 after 는
 * 릴레이 Step 안에서 찍는다. 비교 축을 흔들지 않는다. 통합 테스트는 출력이 2만 줄이 되므로 이
 * 클래스의 로그 레벨만 따로 올려 둔다.
 */
public class LoggingNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationSender.class);

    private static final String CSV_MARKER = "SEND";

    private final NotificationRecorder recorder;

    /**
     * 발송기를 만든다.
     *
     * @param recorder 나간 알림을 모을 기록기
     */
    public LoggingNotificationSender(NotificationRecorder recorder) {
        this.recorder = recorder;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>기록이 곧 발송이다.</b> 이 메서드가 반환한 뒤에는 어떤 롤백도 이 호출을 취소하지 못한다.
     */
    @Override
    public void send(NotificationMessage message) {
        recorder.record(message);
        log.info("{},{},{},{}", CSV_MARKER,
                message.idempotencyKey(), message.memberId(), message.createdAt());
    }
}
