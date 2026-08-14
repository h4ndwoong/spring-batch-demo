package com.h4ndwoong.batchdemo.lookup;

import com.h4ndwoong.batchdemo.domain.MemberGrade;

/**
 * 한 회원의 등급 재산정 결과. {@code lookupStep} 의 가공 산출물이다.
 *
 * <p><b>왜 {@code MemberBase} 를 고쳐서 넘기지 않는가</b><br>
 * 4번 문제는 <b>DB 에 쓰지 않는다</b> ({@link GradeDecisionItemWriter} 참고). 엔티티를 변형해
 * 넘기면 "이건 저장되는가" 가 코드에서 읽히지 않고, 나중에 라이터를 바꿀 때 조용히 저장되기
 * 시작할 여지도 남는다. 산출물을 별도 타입으로 두면 <b>가공 결과가 값일 뿐</b>이라는 사실이
 * 타입에 드러난다.
 *
 * @param memberId       회원 식별자
 * @param oldGrade       기존 등급
 * @param newGrade       재산정된 등급
 * @param effectivePoint 보유 포인트 + 추천인 보너스. 등급 산정의 입력값
 */
public record GradeDecision(long memberId, MemberGrade oldGrade, MemberGrade newGrade, long effectivePoint) {

    /**
     * 등급이 바뀌었는지 여부.
     *
     * @return 바뀌었으면 {@code true}
     */
    public boolean changed() {
        return oldGrade != newGrade;
    }
}
