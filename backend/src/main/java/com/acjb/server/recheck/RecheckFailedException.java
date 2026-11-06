package com.acjb.server.recheck;

/** 재검증 범위 제안의 외부 호출(임베딩·LLM) 실패 또는 LLM 출력 스키마 위반 — API는 502 SUGGESTION_FAILED */
public class RecheckFailedException extends RuntimeException {

    public RecheckFailedException(String message) {
        super(message);
    }

    public RecheckFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
