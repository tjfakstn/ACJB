package com.acjb.server.liveqa;

/** 배포 화면을 서버 브라우저로 열어 viewport 크기 그대로 PNG로 찍는다(AC30). 테스트에서는 가짜로 바꿔 끼운다 */
public interface PageCapturer {

    /** 실패하면 CaptureFailedException(사용자에게 보여도 되는 메시지) */
    byte[] capture(String url, int width, int height);
}
