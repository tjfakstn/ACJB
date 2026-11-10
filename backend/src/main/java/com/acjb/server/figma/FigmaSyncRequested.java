package com.acjb.server.figma;

/** 동기화 요청이 커밋된 뒤 작업을 시작하라는 신호 */
public record FigmaSyncRequested(Long syncId) {
}
