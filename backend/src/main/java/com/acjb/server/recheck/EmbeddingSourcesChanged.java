package com.acjb.server.recheck;

/** 임베딩 대상 원본(이슈·스타일 차이·fail 재검증)이 그 프로젝트에 새로 저장됐다 — 커밋 뒤 EmbeddingIndexer가 누락분을 채운다 */
public record EmbeddingSourcesChanged(Long projectId) {
}
