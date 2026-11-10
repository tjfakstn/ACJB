package com.acjb.server.scan;

/** scan을 failed로 끝내는 이유. 메시지는 scan 상태에 그대로 저장되므로 내부 정보(스택, 토큰 등)를 넣지 않는다 */
public class ScanFailedException extends RuntimeException {

    public ScanFailedException(String message) {
        super(message);
    }

    public ScanFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
