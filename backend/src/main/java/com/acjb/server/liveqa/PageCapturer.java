package com.acjb.server.liveqa;

import java.util.List;

import com.acjb.server.domain.PageElement;

/** 배포 화면을 서버 브라우저로 열어 viewport 크기 그대로 PNG로 찍는다(AC30). 테스트에서는 가짜로 바꿔 끼운다 */
public interface PageCapturer {

    /** 전체 페이지는 이 높이까지만 찍는다 — 무한 스크롤 화면이 끝없이 길어지지 않게 */
    int MAX_PAGE_HEIGHT = 12000;

    /** 요소 목록은 이 개수까지만 — 저장 크기를 묶는다 */
    int MAX_ELEMENTS = 3000;

    /** 실패하면 CaptureFailedException(사용자에게 보여도 되는 메시지) */
    byte[] capture(String url, int width, int height);

    /**
     * 같은 화면 크기로 열어 페이지 전체(최대 MAX_PAGE_HEIGHT)를 찍고, 그 순간 보이는 요소의 위치를 함께 돌려준다(2026-11-20).
     * 수동 QA를 이 캡처 위에서 찍으면 스크롤해도 어긋나지 않고, 찍은 점의 요소를 남길 수 있다
     */
    PageShot captureFullPage(String url, int width, int height);

    /** png의 크기는 width × pageHeight. elements는 페이지 맨 위 기준 위치 */
    record PageShot(byte[] png, int pageHeight, List<PageElement> elements) {
    }
}
