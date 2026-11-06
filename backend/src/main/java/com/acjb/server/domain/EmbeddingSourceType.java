package com.acjb.server.domain;

import java.util.Locale;

/** 임베딩 원본의 종류. API·LLM 입력에서는 소문자 이름(qa_issue 등)으로 쓴다 */
public enum EmbeddingSourceType {
    QA_ISSUE, STYLE_MISMATCH, VERIFICATION;

    public String apiName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
