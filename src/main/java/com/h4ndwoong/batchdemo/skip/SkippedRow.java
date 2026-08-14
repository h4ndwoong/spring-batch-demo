package com.h4ndwoong.batchdemo.skip;

import com.h4ndwoong.batchdemo.domain.MemberBase;

import java.time.LocalDateTime;

/**
 * 격리 테이블에 남길 한 건의 기록. "무슨 행이, 어느 단계에서, 왜 빠졌는가" 를 담는다.
 *
 * <p>{@link ErrorRowIsolatingSkipListener}(해석)와 {@link ErrorRowRecorder}(저장) 사이의 계약이다.
 * 이 타입이 있어서 리스너는 SQL 을 모르고, 레코더는 Spring Batch 의 스킵 콜백을 모른다.
 *
 * @param memberId        스킵된 행의 식별자. 읽기 단계 스킵이면 {@code null} 일 수 있다
 * @param phase           스킵된 단계
 * @param rawItem         스킵된 항목의 문자열 표현. 원본이 나중에 바뀌어도 당시 값을 남기기 위한 것이다
 * @param exceptionType   예외 클래스 이름
 * @param message         예외 메시지
 * @param stepExecutionId 어느 실행에서 스킵되었는지. 재실행하면 기록이 누적되므로 이 값으로 구분한다
 * @param skippedAt       스킵 시각
 */
public record SkippedRow(Long memberId,
                         SkipPhase phase,
                         String rawItem,
                         String exceptionType,
                         String message,
                         Long stepExecutionId,
                         LocalDateTime skippedAt) {

    /**
     * 스킵 이벤트로부터 기록을 만든다.
     *
     * @param phase           스킵된 단계
     * @param item            스킵된 항목. 읽기 단계 스킵이면 {@code null}
     * @param cause           스킵의 원인이 된 예외
     * @param stepExecutionId Step 실행 식별자
     * @param skippedAt       스킵 시각
     * @return 격리 기록
     */
    public static SkippedRow of(SkipPhase phase,
                                MemberBase item,
                                Throwable cause,
                                Long stepExecutionId,
                                LocalDateTime skippedAt) {
        return new SkippedRow(
                memberId(item, cause),
                phase,
                describe(item),
                cause == null ? null : cause.getClass().getName(),
                cause == null ? null : cause.getMessage(),
                stepExecutionId,
                skippedAt);
    }

    /**
     * 식별자를 찾는다. 항목이 있으면 항목에서, 없으면 예외에서 얻는다.
     *
     * <p>{@link MemberValidationException} 이 식별자를 들고 다니는 덕분에, 항목을 넘겨받지 못하는
     * 읽기 단계 스킵에서도 어느 행인지 알 수 있는 경우가 생긴다.
     */
    private static Long memberId(MemberBase item, Throwable cause) {
        if (item != null && item.getId() != null) {
            return item.getId();
        }
        if (cause instanceof MemberValidationException validation) {
            return validation.getMemberId();
        }
        return null;
    }

    /**
     * 항목을 사람이 읽을 수 있는 한 줄로 만든다.
     *
     * <p>{@code toString()} 을 엔티티에 구현하지 않고 여기서 만드는 이유는, 이 문자열의 목적이
     * 디버깅 출력이 아니라 <b>격리 테이블에 영구히 남을 증거</b>이기 때문이다. 엔티티의
     * {@code toString()} 이 언젠가 바뀌면 과거 기록과 형식이 어긋난다.
     */
    private static String describe(MemberBase item) {
        if (item == null) {
            return null;
        }
        return "%s{id=%d, email=%s, name=%s, grade=%s, point=%d, status=%s}".formatted(
                item.getClass().getSimpleName(),
                item.getId(),
                item.getEmail(),
                item.getName(),
                item.getGrade(),
                item.getPoint(),
                item.getStatus());
    }
}
