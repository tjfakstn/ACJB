package com.acjb.server.parse;

/** LLM 호출 실패·거절·스키마 위반. 원문을 넣거나 재시도로 값을 만들어 내지 않고 실패로 응답한다(N9) */
public class ParseFailedException extends RuntimeException {

    public ParseFailedException(String message) {
        super(message);
    }

    public ParseFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
