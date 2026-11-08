package com.acjb.server.qa;

/** 첫 QA·Re-QA 실패 — 메시지는 사용자에게 그대로 보여 준다(내부 상세·토큰은 담지 않는다) */
public class QaFailedException extends RuntimeException {

    public QaFailedException(String message) {
        super(message);
    }
}
