package com.acjb.server.qa;

/** Re-QA 실행이 만들어졌다 — 커밋 뒤 ReQaJob이 받는다 */
public record ReQaRequested(Long runId) {
}
