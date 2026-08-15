package com.h4ndwoong.batchdemo.outbox;

/**
 * DB 의 상태와 실제로 나간 알림을 나란히 놓은 지문. <b>7번 문제에서 "같아야 하는 것" 은 이 값이다.</b>
 *
 * <p>1~6번의 지문은 전부 DB 안에서 나왔다 (등급 분포, 포인트 총합, 갱신 행 수). <b>7번의 지문은
 * 절반이 DB 밖에서 온다</b> — 그리고 두 절반이 어긋나는 것이 이 문제의 증상 그 자체다.
 *
 * <p>값마다 잡아내는 사고가 다르다.
 * <ul>
 *   <li>{@code changedRows} — <b>일의 양.</b> 양쪽이 같아야 한다. 다르면 개선이 아니라 일을 빠뜨린
 *       것이다 (6번의 {@code HANDLER_UPDATE} 와 같은 자리).</li>
 *   <li>{@code phantomSends} — <b>주 지표 1.</b> 알림은 나갔는데 DB 는 그대로인 회원 수. before 의
 *       실패한 실행에서 청크 크기만큼 나오고 after 는 0 이어야 한다.</li>
 *   <li>{@code duplicateSends} — <b>주 지표 2.</b> 같은 알림이 두 번 나간 횟수. 재실행에서 드러난다.</li>
 *   <li>{@code sendAttempts} 대 {@code distinctKeys} — 시도와 수신자. 둘이 벌어진 만큼이 중복이다.</li>
 *   <li>{@code outboxRows} / {@code outboxPending} / {@code outboxSent} — after 전용. 적재와 발송이
 *       같은지, 아직 나가지 못한 것이 있는지. <b>{@code PENDING} 이 남아 있는 것은 유실이 아니라
 *       지연</b>이며, 그 구분이 Outbox 가 파는 물건이다.</li>
 * </ul>
 *
 * <p><b>{@code phantomSends} 는 시점 지표다.</b> 실패한 실행 <em>직후</em>에 재야 의미가 있다.
 * 재실행이 끝나면 그 회원들도 정상 처리되므로 0 이 되고, 대신 그 흔적은 {@code duplicateSends} 로
 * 옮겨 간다. <b>유령은 사라지지 않는다. 이름을 바꿔 남는다.</b>
 *
 * @param activeRows     아직 {@code ACTIVE} 인 회원 수
 * @param dormantRows    {@code DORMANT} 인 회원 수. 시드의 휴면 회원을 포함한다
 * @param changedRows    이 배치가 건드린 회원 수 ({@code updated_at} 이 채워진 행)
 * @param sendAttempts   실제로 나간 알림의 총 수
 * @param distinctKeys   서로 다른 멱등키의 수 = 알림을 받은 사람의 수
 * @param duplicateSends 중복 발송 수
 * @param phantomSends   알림은 받았는데 상태는 그대로인 회원 수
 * @param outboxRows     {@code member_g_outbox} 의 전체 행 수. before 는 언제나 0
 * @param outboxPending  아직 발송되지 않은 Outbox 행 수
 * @param outboxSent     발송 완료로 표시된 Outbox 행 수
 */
public record NotificationDeliveryChecksum(long activeRows,
                                           long dormantRows,
                                           long changedRows,
                                           long sendAttempts,
                                           long distinctKeys,
                                           long duplicateSends,
                                           long phantomSends,
                                           long outboxRows,
                                           long outboxPending,
                                           long outboxSent) {

    /** 아무것도 재지 않은 상태. */
    public static final NotificationDeliveryChecksum EMPTY =
            new NotificationDeliveryChecksum(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    /**
     * 사람이 읽는 한 줄 요약.
     *
     * @return 요약 문자열
     */
    public String summary() {
        return ("ACTIVE=%d DORMANT=%d changed=%d | sends=%d distinct=%d dup=%d phantom=%d "
                + "| outbox=%d(PENDING=%d SENT=%d)")
                .formatted(activeRows, dormantRows, changedRows,
                        sendAttempts, distinctKeys, duplicateSends, phantomSends,
                        outboxRows, outboxPending, outboxSent);
    }
}
