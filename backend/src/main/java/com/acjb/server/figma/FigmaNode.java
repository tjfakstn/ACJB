package com.acjb.server.figma;

/** Figma 노드 중 동기화에 필요한 값만 — 응답에서 검증을 통과한 값으로만 만든다 */
public record FigmaNode(String id, String name, String type) {
}
