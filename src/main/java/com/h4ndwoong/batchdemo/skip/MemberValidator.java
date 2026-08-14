package com.h4ndwoong.batchdemo.skip;

import com.h4ndwoong.batchdemo.domain.MemberBase;

import java.util.regex.Pattern;

/**
 * 회원 한 행이 처리 가능한 상태인지 검사한다. 위반이면 {@link MemberValidationException} 을 던진다.
 *
 * <p><b>왜 프로세서에서 분리했는가</b><br>
 * 검증 규칙은 이 실습에서 가장 자주 바뀔 부분이다({@code change point 1}). 가공 로직과 한 클래스에
 * 두면 "규칙이 늘어서" 와 "가공이 바뀌어서" 라는 서로 다른 이유로 같은 파일이 흔들린다.
 *
 * <p><b>왜 인터페이스가 아닌가</b><br>
 * 구현이 하나뿐이고, 규칙을 늘리는 일은 이 클래스 안의 검사 하나를 더하는 것으로 끝난다. 규칙마다
 * 전략 객체를 두는 구조는 규칙이 세 개인 지금은 코드보다 크다. 프로세서가 생성자로 주입받으므로
 * 나중에 추상화가 필요해져도 호출부는 바뀌지 않는다.
 *
 * <p><b>검사 순서</b>는 이메일 → 포인트 → 이름으로 고정한다. 시드 데이터는 행마다 오염 원인을
 * 하나만 심으므로 순서가 집계에 영향을 주지 않지만, 원인이 겹친 행이 들어오면 <b>항상 같은 규칙</b>이
 * 보고되어야 재실행 결과가 달라지지 않는다.
 */
public class MemberValidator {

    /**
     * 이메일 형식. 공백과 {@code @} 를 제외한 문자로 이루어진 로컬파트와, 점을 포함한 도메인을 요구한다.
     *
     * <p>RFC 5322 를 온전히 구현하지 않는다. 여기서 필요한 것은 "시드가 심은 {@code invalid-email-200}
     * 같은 값을 걸러내는가" 이고, 완전한 이메일 정규식은 그 자체로 검증이 필요한 물건이 된다.
     */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /**
     * 행을 검증한다. 통과하면 아무 일도 일어나지 않는다.
     *
     * @param member 검증할 회원
     * @throws MemberValidationException 이메일 형식 위반, 음수 포인트, 빈 이름 중 하나에 해당할 때
     */
    public void validate(MemberBase member) {
        String email = member.getEmail();
        if (email == null || !EMAIL.matcher(email).matches()) {
            throw new MemberValidationException(member.getId(), ValidationRule.EMAIL_FORMAT, email);
        }

        if (member.getPoint() < 0) {
            throw new MemberValidationException(
                    member.getId(), ValidationRule.NEGATIVE_POINT, String.valueOf(member.getPoint()));
        }

        String name = member.getName();
        if (name == null || name.isBlank()) {
            throw new MemberValidationException(member.getId(), ValidationRule.BLANK_NAME, name);
        }
    }
}
