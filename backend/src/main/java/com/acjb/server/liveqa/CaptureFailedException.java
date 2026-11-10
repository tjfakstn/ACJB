package com.acjb.server.liveqa;

/** 서버 브라우저가 배포 화면을 열거나 찍지 못함 — 메시지는 사용자에게 그대로 보여도 된다(로컬 경로·스택 없음) */
public class CaptureFailedException extends RuntimeException {

    public CaptureFailedException(String message) {
        super(message);
    }

    public CaptureFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
