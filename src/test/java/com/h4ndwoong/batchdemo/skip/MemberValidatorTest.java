package com.h4ndwoong.batchdemo.skip;

import com.h4ndwoong.batchdemo.domain.MemberB;
import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.domain.MemberGrade;
import com.h4ndwoong.batchdemo.domain.MemberStatus;
import com.h4ndwoong.batchdemo.seed.MemberSeedGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MemberValidator} 단위 테스트. DB 도 Spring 컨텍스트도 필요 없다.
 *
 * <p>여기서 고정하는 것은 <b>무엇을 오염으로 볼 것인가</b>이다. 이 경계가 흔들리면 격리 테이블의
 * 건수가 시드 데이터의 오염 건수와 어긋나고, 2번 문제의 대사식이 통째로 무너진다.
 */
class MemberValidatorTest {

    private final MemberValidator validator = new MemberValidator();

    @Test
    @DisplayName("정상 행은 통과한다")
    void 정상_통과() {
        assertThatCode(() -> validator.validate(member("user1@example.com", "김민준", 100L)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("이메일 형식이 틀리면 EMAIL_FORMAT 으로 걸린다")
    void 잘못된_이메일() {
        assertThatThrownBy(() -> validator.validate(member("invalid-email-200", "김민준", 100L)))
                .isInstanceOf(MemberValidationException.class)
                .extracting(exception -> ((MemberValidationException) exception).getRule())
                .isEqualTo(ValidationRule.EMAIL_FORMAT);
    }

    @Test
    @DisplayName("음수 포인트는 NEGATIVE_POINT 로 걸린다")
    void 음수_포인트() {
        assertThatThrownBy(() -> validator.validate(member("user1@example.com", "김민준", -1L)))
                .isInstanceOf(MemberValidationException.class)
                .extracting(exception -> ((MemberValidationException) exception).getRule())
                .isEqualTo(ValidationRule.NEGATIVE_POINT);
    }

    @Test
    @DisplayName("포인트 0 은 오염이 아니다 - 경계값")
    void 포인트_0() {
        assertThatCode(() -> validator.validate(member("user1@example.com", "김민준", 0L)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("이름이 비면 BLANK_NAME 으로 걸린다")
    void 빈_이름() {
        assertThatThrownBy(() -> validator.validate(member("user1@example.com", "   ", 100L)))
                .isInstanceOf(MemberValidationException.class)
                .extracting(exception -> ((MemberValidationException) exception).getRule())
                .isEqualTo(ValidationRule.BLANK_NAME);
    }

    @Test
    @DisplayName("null 이메일과 null 이름도 예외로 걸린다 - NPE 가 아니어야 한다")
    void null_값() {
        assertThatThrownBy(() -> validator.validate(member(null, "김민준", 100L)))
                .isInstanceOf(MemberValidationException.class);
        assertThatThrownBy(() -> validator.validate(member("user1@example.com", null, 100L)))
                .isInstanceOf(MemberValidationException.class);
    }

    @Test
    @DisplayName("예외가 식별자와 규칙을 들고 다닌다 - 격리 기록이 메시지를 파싱하지 않게 하려는 것")
    void 예외가_담는_정보() {
        MemberValidationException exception = catchValidation(member("bad", "김민준", 100L));

        assertThat(exception.getMemberId()).isEqualTo(200L);
        assertThat(exception.getRule()).isEqualTo(ValidationRule.EMAIL_FORMAT);
        assertThat(exception.getMessage())
                .as("격리 테이블의 message 컬럼에 그대로 저장된다")
                .contains("EMAIL_FORMAT", "bad", "200");
    }

    @Test
    @DisplayName("시드 생성기가 심은 오염 행이 실제로 걸린다 - 실습 데이터와의 계약")
    void 시드_오염_행() {
        MemberSeedGenerator generator = new MemberSeedGenerator(
                MemberB::new, 200, false,
                MemberSeedGenerator.DEFAULT_SEED, MemberSeedGenerator.BASE_TIME);

        assertThatCode(() -> validator.validate(generator.generate(199L)))
                .as("오염 간격에 걸리지 않는 행은 통과해야 한다").doesNotThrowAnyException();
        assertThat(catchValidation(generator.generate(200L)).getRule())
                .as("홀수 번째 오염은 이메일 형식 오류다").isEqualTo(ValidationRule.EMAIL_FORMAT);
        assertThat(catchValidation(generator.generate(400L)).getRule())
                .as("짝수 번째 오염은 음수 포인트다").isEqualTo(ValidationRule.NEGATIVE_POINT);
    }

    private MemberValidationException catchValidation(MemberBase member) {
        try {
            validator.validate(member);
        }
        catch (MemberValidationException e) {
            return e;
        }
        throw new AssertionError("검증에 걸리지 않았다: " + member.getEmail());
    }

    private MemberBase member(String email, String name, long point) {
        return new MemberB(200L, email, name, MemberGrade.GOLD, point, MemberStatus.ACTIVE,
                null, false, null, LocalDateTime.of(2026, 1, 1, 0, 0), null);
    }
}
