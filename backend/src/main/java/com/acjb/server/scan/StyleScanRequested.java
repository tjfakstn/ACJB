package com.acjb.server.scan;

/** scan 요청이 저장됐다 — 커밋 뒤 StyleScanJob이 실행한다 */
public record StyleScanRequested(Long scanId) {
}
