package com.acjb.server.recheck;

/** 완성된 프롬프트 → 모델이 낸 JSON 문자열. 운영은 Claude, 테스트는 대역 */
public interface RecheckSuggester {

    /** 실패·거절·잘림이면 RecheckFailedException */
    String suggest(String prompt);
}
