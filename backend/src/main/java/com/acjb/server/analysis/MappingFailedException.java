package com.acjb.server.analysis;

/** 비교 분석을 끝내지 못한 이유 — 메시지는 사용자에게 그대로 보여 준다(내부 상세·키는 담지 않는다) */
public class MappingFailedException extends RuntimeException {

    public MappingFailedException(String message) {
        super(message);
    }

    public MappingFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
