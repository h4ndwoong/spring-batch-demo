package com.h4ndwoong.batchdemo.seed;

import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.domain.MemberC;
import com.h4ndwoong.batchdemo.domain.MemberD;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MemberSeedGenerator} 의 결정론성과 대상별 데이터 요건을 검증한다.
 *
 * <p>결정론성은 이 프로젝트의 전제다. before/after 가 같은 입력을 받지 않으면 측정치를 비교할 수 없고,
 * Step 이 재시작되면서 데이터가 달라지면 5번 문제의 멱등성 검증이 성립하지 않는다.
 */
class MemberSeedGeneratorTest {

    private static final long SEED = 20260814L;
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final int CORRUPT_INTERVAL = 200;

    private static MemberSeedGenerator plain() {
        return new MemberSeedGenerator(MemberC::new, 0, false, SEED, BASE_TIME);
    }

    private static MemberSeedGenerator corrupting() {
        return new MemberSeedGenerator(MemberC::new, CORRUPT_INTERVAL, false, SEED, BASE_TIME);
    }

    private static MemberSeedGenerator selfReferencing() {
        return new MemberSeedGenerator(MemberD::new, 0, true, SEED, BASE_TIME);
    }

    @Nested
    @DisplayName("결정론성")
    class Determinism {

        @Test
        @DisplayName("같은 시드와 같은 순번은 항상 같은 행을 만든다")
        void 같은_시드는_같은_데이터() {
            MemberBase first = plain().generate(12_345L);
            MemberBase second = plain().generate(12_345L);

            assertThat(first.getEmail()).isEqualTo(second.getEmail());
            assertThat(first.getName()).isEqualTo(second.getName());
            assertThat(first.getGrade()).isEqualTo(second.getGrade());
            assertThat(first.getPoint()).isEqualTo(second.getPoint());
            assertThat(first.getStatus()).isEqualTo(second.getStatus());
            assertThat(first.getCreatedAt()).isEqualTo(second.getCreatedAt());
        }

        @Test
        @DisplayName("앞 구간을 읽지 않고 n번째 행을 바로 만들어도 순차 생성 결과와 같다 - 재시작 시 데이터가 변하지 않는 근거")
        void 순차_생성과_직접_생성이_같다() {
            MemberSeedGenerator sequential = plain();
            MemberBase last = null;
            for (long index = 1; index <= 500; index++) {
                last = sequential.generate(index);
            }

            MemberBase direct = plain().generate(500L);

            assertThat(last).isNotNull();
            assertThat(direct.getEmail()).isEqualTo(last.getEmail());
            assertThat(direct.getPoint()).isEqualTo(last.getPoint());
            assertThat(direct.getGrade()).isEqualTo(last.getGrade());
            assertThat(direct.getCreatedAt()).isEqualTo(last.getCreatedAt());
        }

        @Test
        @DisplayName("시드가 다르면 데이터가 달라진다")
        void 다른_시드는_다른_데이터() {
            MemberSeedGenerator other = new MemberSeedGenerator(MemberC::new, 0, false, SEED + 1, BASE_TIME);

            long samePointCount = LongStream.rangeClosed(1, 200)
                    .filter(index -> plain().generate(index).getPoint() == other.generate(index).getPoint())
                    .count();

            assertThat(samePointCount).as("200건이 모두 같을 수는 없다").isLessThan(200L);
        }

        @Test
        @DisplayName("순번은 1부터 시작한다")
        void 순번_하한() {
            assertThatThrownBy(() -> plain().generate(0L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("1부터");
        }
    }

    @Nested
    @DisplayName("공통 필드")
    class CommonFields {

        @Test
        @DisplayName("이메일은 순번마다 유일하다")
        void 이메일_유일성() {
            MemberSeedGenerator generator = plain();

            Set<String> emails = new HashSet<>();
            IntStream.rangeClosed(1, 10_000).forEach(index -> emails.add(generator.generate(index).getEmail()));

            assertThat(emails).hasSize(10_000);
        }

        @Test
        @DisplayName("생성 시각은 기준 시각 이전 1년 범위에 분포한다")
        void 생성_시각_분포() {
            MemberSeedGenerator generator = plain();
            LocalDateTime oldest = BASE_TIME.minusYears(1);

            Set<LocalDateTime> distinct = new HashSet<>();
            for (long index = 1; index <= 1_000; index++) {
                LocalDateTime createdAt = generator.generate(index).getCreatedAt();
                assertThat(createdAt).isAfterOrEqualTo(oldest).isBeforeOrEqualTo(BASE_TIME);
                distinct.add(createdAt);
            }

            assertThat(distinct).as("1번 문제의 인덱스 랜덤 갱신을 재현하려면 시각이 흩어져야 한다")
                    .hasSizeGreaterThan(900);
        }

        @Test
        @DisplayName("신규 생성 행은 처리되지 않은 상태다")
        void 초기_상태() {
            MemberBase member = plain().generate(1L);

            assertThat(member.isProcessed()).isFalse();
            assertThat(member.getIdempotencyKey()).isNull();
            assertThat(member.getUpdatedAt()).isNull();
        }

        @Test
        @DisplayName("순번이 그대로 식별자가 된다 - AUTO_INCREMENT 에 맡기지 않는다")
        void 식별자는_순번() {
            assertThat(plain().generate(1L).getId()).isEqualTo(1L);
            assertThat(plain().generate(999_999L).getId()).isEqualTo(999_999L);
        }
    }

    @Nested
    @DisplayName("오염 행 - 2번 문제")
    class Corruption {

        @Test
        @DisplayName("오염 간격마다 한 건씩만 오염된다")
        void 오염_건수() {
            MemberSeedGenerator generator = corrupting();

            long corrupted = LongStream.rangeClosed(1, 10_000).filter(generator::isCorrupt).count();

            assertThat(corrupted).isEqualTo(10_000 / CORRUPT_INTERVAL);
        }

        @Test
        @DisplayName("오염 종류는 이메일 형식 오류와 음수 포인트가 번갈아 나타나고 한 행에 겹치지 않는다")
        void 오염_종류가_교대한다() {
            MemberSeedGenerator generator = corrupting();

            MemberBase invalidEmail = generator.generate(200L);
            MemberBase negativePoint = generator.generate(400L);

            assertThat(invalidEmail.getEmail()).doesNotContain("@");
            assertThat(invalidEmail.getPoint()).as("이메일 오염 행의 포인트는 정상이어야 한다").isNotNegative();

            assertThat(negativePoint.getPoint()).isNegative();
            assertThat(negativePoint.getEmail()).as("포인트 오염 행의 이메일은 정상이어야 한다").contains("@");
        }

        @Test
        @DisplayName("오염 간격이 아닌 행은 정상이다")
        void 정상_행() {
            MemberSeedGenerator generator = corrupting();

            for (long index = 1; index < CORRUPT_INTERVAL; index++) {
                MemberBase member = generator.generate(index);
                assertThat(member.getEmail()).contains("@");
                assertThat(member.getPoint()).isNotNegative();
            }
        }

        @Test
        @DisplayName("오염 간격이 0이면 오염 행이 없다")
        void 오염_없음() {
            MemberSeedGenerator generator = plain();

            long corrupted = LongStream.rangeClosed(1, 10_000).filter(generator::isCorrupt).count();

            assertThat(corrupted).isZero();
        }
    }

    @Nested
    @DisplayName("자기 참조 - 4번 문제")
    class SelfReference {

        @Test
        @DisplayName("첫 행은 가리킬 앞선 행이 없어 추천인이 없다")
        void 첫_행() {
            assertThat(selfReferencing().generate(1L).getReferrerId()).isNull();
        }

        @Test
        @DisplayName("추천인은 항상 자기보다 앞선 순번이라 실제 존재하는 행을 가리킨다")
        void 추천인은_앞선_행() {
            MemberSeedGenerator generator = selfReferencing();

            for (long index = 2; index <= 5_000; index++) {
                Long referrerId = generator.generate(index).getReferrerId();
                assertThat(referrerId).isNotNull();
                assertThat(referrerId).isBetween(1L, index - 1);
            }
        }

        @Test
        @DisplayName("자기 참조 대상이 아니면 추천인이 없다")
        void 자기_참조_아님() {
            MemberSeedGenerator generator = plain();

            for (long index = 1; index <= 100; index++) {
                assertThat(generator.generate(index).getReferrerId()).isNull();
            }
        }
    }

    @Nested
    @DisplayName("시딩 대상")
    class Target {

        @Test
        @DisplayName("대상은 자기 테이블에 맞는 엔티티 타입을 만든다")
        void 대상별_엔티티_타입() {
            MemberSeedGenerator generator =
                    MemberSeedGenerator.forTarget(SeedTarget.MEMBER_D, SEED, BASE_TIME);

            assertThat(generator.generate(1L)).isInstanceOf(MemberD.class);
        }

        @Test
        @DisplayName("member_a 는 insertJob 이 직접 적재하므로 시딩 대상이 아니다")
        void member_a_는_시딩_대상이_아니다() {
            assertThatThrownBy(() -> SeedTarget.from("member_a"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("insertJob");
        }

        @Test
        @DisplayName("알 수 없는 테이블 이름은 가능한 값을 알려주며 거부한다")
        void 알_수_없는_대상() {
            assertThatThrownBy(() -> SeedTarget.from("member_z"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("member_b");
        }

        @Test
        @DisplayName("target 이 없으면 필요하다고 알린다")
        void 대상_누락() {
            assertThatThrownBy(() -> SeedTarget.from(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("target");
        }

        @Test
        @DisplayName("대상별 기본 건수는 README 의 데이터 규모를 따른다")
        void 기본_건수() {
            assertThat(SeedTarget.MEMBER_B.defaultCount()).isEqualTo(100_000L);
            assertThat(SeedTarget.MEMBER_C.defaultCount()).isEqualTo(2_000_000L);
            assertThat(SeedTarget.MEMBER_D.defaultCount()).isEqualTo(500_000L);
            assertThat(SeedTarget.MEMBER_E.defaultCount()).isEqualTo(300_000L);
            assertThat(SeedTarget.MEMBER_F.defaultCount()).isEqualTo(1_000_000L);
            assertThat(SeedTarget.MEMBER_G.defaultCount()).isEqualTo(100_000L);
        }

        @Test
        @DisplayName("member_b 의 기본 오염 건수는 500건이다")
        void 기본_오염_건수() {
            MemberSeedGenerator generator =
                    MemberSeedGenerator.forTarget(SeedTarget.MEMBER_B, SEED, BASE_TIME);

            long corrupted = LongStream.rangeClosed(1, SeedTarget.MEMBER_B.defaultCount())
                    .filter(generator::isCorrupt)
                    .count();

            assertThat(corrupted).isEqualTo(500L);
        }
    }
}
