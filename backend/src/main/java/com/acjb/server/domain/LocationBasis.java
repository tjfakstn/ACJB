package com.acjb.server.domain;

/**
 * 실시간 화면 위에서 찍었는지, 첨부 이미지 위에서 찍었는지, 위치 없이 등록했는지(배포 화면 없음, 2026-11-10) — AC31.
 * PAGE_CAPTURE(2026-11-20): 서버가 찍은 배포 화면 전체 페이지 캡처 위에서 찍었다 — 좌표는 페이지 맨 위 기준이고 그 점의 요소를 함께 남긴다.
 * 교차 출처 iframe 위의 LIVE_OVERLAY는 스크롤·화면 조작을 읽을 수 없어 핀이 어긋났다(화면은 더 이상 이 방식으로 등록하지 않는다)
 */
public enum LocationBasis {
    /**
     * LIVE_ELEMENT(2026-11-20): 배포 사이트에 넣은 QAting 스니펫(embed.js)으로 실시간 화면의 요소를 골랐다 — 핀은 그 요소에 붙어
     * 스크롤·화면 변화를 따라간다. 좌표는 페이지 맨 위 기준, 요소는 브라우저가 알려 준 CSS 경로·글자·상자
     */
    LIVE_OVERLAY, CAPTURE_IMAGE, PAGE_CAPTURE, LIVE_ELEMENT, NONE
}
