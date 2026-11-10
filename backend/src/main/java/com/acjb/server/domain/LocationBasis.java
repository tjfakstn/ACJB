package com.acjb.server.domain;

/** 실시간 화면 위에서 찍었는지, 첨부 이미지 위에서 찍었는지, 위치 없이 등록했는지(배포 화면 없음, 2026-11-10) — AC31 */
public enum LocationBasis {
    LIVE_OVERLAY, CAPTURE_IMAGE, NONE
}
