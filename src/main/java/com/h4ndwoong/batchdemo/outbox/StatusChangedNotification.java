package com.h4ndwoong.batchdemo.outbox;

import com.h4ndwoong.batchdemo.domain.MemberBase;

/**
 * 상태가 바뀐 회원으로부터 알림 메시지를 만든다. <b>7번 문제에서 양쪽이 공유해야 하는 한 곳</b>이다.
 *
 * <p>before 는 이 메시지를 만들어 <em>바로 보내고</em>, after 는 같은 메시지를 만들어
 * <em>{@code member_g_outbox} 에 적재</em>한다. 만드는 자리가 하나여야 "같은 알림을 다르게 보냈다"
 * 가 성립한다. 6번에서 등급 규칙이 자바와 SQL 두 곳에 존재할 뻔한 것을 {@code GradeCaseExpression}
 * 이 <b>생성</b>으로 막았던 것과 같은 방어다.
 *
 * <p><b>시각을 인자로 받지 않는다.</b> 알림의 시각은 <b>상태가 바뀐 시각</b>({@code updated_at}) 이고,
 * 그것은 이미 {@link StatusTransitionItemProcessor} 가 정해 두었다. 여기서 시계를 한 번 더 읽으면
 * 같은 사건에 두 개의 시각이 생기고, 무엇보다 <b>before 와 after 의 메시지가 서로 달라진다</b> —
 * 발송 시점이 다르기 때문이다. 비교 축을 지키려면 시각도 데이터에서 나와야 한다.
 *
 * <p><b>JSON 을 손으로 만든다.</b> 이 프로젝트에는 Jackson 이 없다
 * ({@code spring-boot-starter-batch} 와 {@code -data-jpa} 는 그것을 끌어오지 않는다). 필드가 다섯
 * 개인 고정 형식 하나를 위해 의존성을 늘리지 않고, 대신 문자열 값은 {@link #escape(String)} 로
 * 이스케이프한다 — 이름이나 이메일에 따옴표가 들어오면 <b>본문이 깨진 채로 발송된다</b>.
 */
public final class StatusChangedNotification {

    /** 이벤트 종류. {@code schema.sql} 의 주석이 예시로 든 이름을 그대로 쓴다. */
    public static final String EVENT_TYPE = "MEMBER_STATUS_CHANGED";

    private static final String PAYLOAD_TEMPLATE =
            "{\"memberId\":%d,\"email\":\"%s\",\"name\":\"%s\",\"status\":\"%s\",\"changedAt\":\"%s\"}";

    private StatusChangedNotification() {
    }

    /**
     * 상태 전이가 끝난 회원으로부터 알림을 만든다.
     *
     * @param member 상태가 이미 바뀌어 있고 {@code updatedAt} 이 채워진 회원
     * @return 알림 메시지
     * @throws IllegalArgumentException {@code member} 가 {@code null} 이거나 {@code updatedAt} 이
     *                                  비어 있을 때. 아직 바뀌지 않은 회원의 변경 알림은 만들 수 없다
     */
    public static NotificationMessage of(MemberBase member) {
        if (member == null) {
            throw new IllegalArgumentException("알림을 만들 회원이 없다");
        }
        if (member.getUpdatedAt() == null) {
            throw new IllegalArgumentException(
                    "상태가 바뀌지 않은 회원의 변경 알림은 만들 수 없다: memberId=" + member.getId());
        }

        return new NotificationMessage(
                member.getId(),
                EVENT_TYPE,
                payloadOf(member),
                NotificationIdempotencyKey.of(member.getId()),
                member.getUpdatedAt());
    }

    private static String payloadOf(MemberBase member) {
        return PAYLOAD_TEMPLATE.formatted(
                member.getId(),
                escape(member.getEmail()),
                escape(member.getName()),
                member.getStatus(),
                member.getUpdatedAt());
    }

    /**
     * JSON 문자열 값에 들어갈 수 없는 문자를 이스케이프한다.
     *
     * <p>시드 데이터에는 따옴표도 역슬래시도 없지만, 2번 문제가 보여준 대로 <b>오염된 값은 실제로
     * 들어온다.</b> 본문이 깨진 알림은 발송된 뒤에 발견되고 그때는 되돌릴 수 없다.
     *
     * @param value 원본 값. {@code null} 이면 빈 문자열이 된다
     * @return 이스케이프된 값
     */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
