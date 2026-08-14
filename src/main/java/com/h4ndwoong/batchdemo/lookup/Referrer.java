package com.h4ndwoong.batchdemo.lookup;

import com.h4ndwoong.batchdemo.domain.MemberGrade;

/**
 * 조회한 추천인. <b>등급만</b> 들고 있다.
 *
 * <p><b>왜 {@link com.h4ndwoong.batchdemo.domain.MemberBase} 가 아닌가</b><br>
 * 가공에 필요한 것은 추천인의 등급뿐이다({@link ReferrerBonus}). 전 컬럼을 끌어오면 after 의
 * {@code IN} 조회가 필요 없이 비싸지고, before 와 after 가 <b>서로 다른 양의 데이터</b>를 읽게 되어
 * 비교 축이 왕복 횟수 하나로 좁혀지지 않는다. 두 전략은 같은 컬럼({@code id, grade})을 같은
 * PK 경로로 읽고, <b>몇 번의 왕복에 나눠 담는지만</b> 다르다.
 *
 * @param id    추천인 식별자
 * @param grade 추천인 등급. 보너스 산정의 유일한 입력
 */
public record Referrer(long id, MemberGrade grade) {

    /**
     * 추천인을 만든다.
     *
     * @throws IllegalArgumentException {@code grade} 가 {@code null} 일 때
     */
    public Referrer {
        if (grade == null) {
            throw new IllegalArgumentException("추천인 등급은 null 일 수 없다: id=" + id);
        }
    }
}
