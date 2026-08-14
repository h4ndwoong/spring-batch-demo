package com.h4ndwoong.batchdemo.support;

import com.h4ndwoong.batchdemo.domain.MemberGrade;

/**
 * "이 포인트부터 이 등급" 한 칸. {@link GradePolicy} 를 자바 밖으로 내보낼 때의 단위다.
 *
 * <p>6번 문제의 after 는 등급을 자바가 아니라 SQL 의 {@code CASE} 식으로 정한다. 그 식을 손으로
 * 쓰면 등급 규칙이 두 곳에 존재하게 되고, 둘이 어긋나면 <b>배치는 성공으로 끝나고 등급만 조용히
 * 틀린다</b> — 4번 문제에서 가장 경계했던 실패 모양이다. 정책이 자기 임계값을 이 타입으로 내놓고
 * SQL 이 그것으로부터 생성되면, 규칙은 여전히 한 곳에만 있다.
 *
 * @param grade         이 임계값 이상일 때의 등급
 * @param fromInclusive 임계값. <b>같은 값도 포함</b>한다 ({@code point >= fromInclusive})
 */
public record GradeThreshold(MemberGrade grade, long fromInclusive) {
}
