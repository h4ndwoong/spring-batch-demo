package com.h4ndwoong.batchdemo.outbox;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.domain.MemberG;
import com.h4ndwoong.batchdemo.domain.MemberGrade;
import com.h4ndwoong.batchdemo.domain.MemberStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link StatusChangedNotification} 단위 테스트.
 *
 * <p>before 와 after 가 <b>문자 그대로 같은 메시지</b>를 만든다는 것이 7번 비교의 전제다. 메시지가
 * 프로파일마다 다르면 "같은 알림을 몇 번 보냈는가" 라는 질문 자체가 성립하지 않는다. 그 보장은
 * "양쪽이 이 클래스를 쓴다" 는 사실에서 나오므로, 여기서는 <b>이 클래스가 데이터에만 의존한다</b>
 * 는 것을 고정한다.
 */
class StatusChangedNotificationTest {

    private static final LocalDateTime CHANGED_AT = LocalDateTime.of(2026, 1, 1, 12, 0);

    @Test
    @DisplayName("메시지의 시각은 상태가 바뀐 시각이다 - 시계를 다시 읽지 않는다")
    void 시각은_데이터에서_나온다() {
        NotificationMessage message = StatusChangedNotification.of(changed(1L));

        assertThat(message.createdAt())
                .as("발송 시점에서 시계를 읽으면 before 와 after 의 메시지가 달라진다")
                .isEqualTo(CHANGED_AT);
    }

    @Test
    @DisplayName("같은 회원에서 몇 번을 만들어도 같은 메시지다 - 순수 함수여야 비교가 성립한다")
    void 결정론적이다() {
        MemberBase member = changed(1L);

        assertThat(StatusChangedNotification.of(member)).isEqualTo(StatusChangedNotification.of(member));
    }

    @Test
    @DisplayName("본문에 회원 식별자와 바뀐 상태가 담긴다")
    void 본문() {
        NotificationMessage message = StatusChangedNotification.of(changed(42L));

        assertThat(message.eventType()).isEqualTo(StatusChangedNotification.EVENT_TYPE);
        assertThat(message.memberId()).isEqualTo(42L);
        assertThat(message.idempotencyKey()).isEqualTo(NotificationIdempotencyKey.of(42L));
        assertThat(message.payload())
                .contains("\"memberId\":42")
                .contains("\"status\":\"DORMANT\"");
    }

    @Test
    @DisplayName("따옴표가 섞인 값은 이스케이프한다 - 깨진 본문은 발송된 뒤에 발견된다")
    void 이스케이프() {
        MemberBase member = new MemberG(1L, "we\"ird@example.com", "김\\민준", MemberGrade.BRONZE,
                1_000L, MemberStatus.ACTIVE, null, false, null, CHANGED_AT, null);
        member.changeStatus(MemberStatus.DORMANT, CHANGED_AT);

        assertThat(StatusChangedNotification.of(member).payload())
                .contains("we\\\"ird@example.com")
                .contains("김\\\\민준");
    }

    @Test
    @DisplayName("상태가 바뀌지 않은 회원의 변경 알림은 만들 수 없다")
    void 전이_전_회원() {
        MemberBase notChanged = new MemberG(1L, "user1@example.com", "김민준", MemberGrade.BRONZE,
                1_000L, MemberStatus.ACTIVE, null, false, null, CHANGED_AT, null);

        assertThatThrownBy(() -> StatusChangedNotification.of(notChanged))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static MemberBase changed(long id) {
        MemberBase member = new MemberG(id, "user" + id + "@example.com", "김민준", MemberGrade.BRONZE,
                1_000L, MemberStatus.ACTIVE, null, false, null,
                LocalDateTime.of(2026, 1, 1, 0, 0), null);
        member.changeStatus(MemberStatus.DORMANT, CHANGED_AT);
        return member;
    }
}
