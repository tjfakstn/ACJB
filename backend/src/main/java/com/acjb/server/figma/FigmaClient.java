package com.acjb.server.figma;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;

/** Figma REST API 중 동기화·scan에 쓰는 호출. 테스트에서는 가짜로 바꿔 끼운다 */
public interface FigmaClient {

    /** 토큰이 설정돼 호출할 수 있는 상태인지 — 요청 단계에서 미리 거부하는 데 쓴다 */
    boolean isConfigured();

    /** 파일의 각 페이지 바로 아래 노드(화면 단위 프레임 후보) — GET /v1/files/{key}?depth=2 */
    List<FigmaNode> topLevelNodes(String fileKey);

    /** 지정한 노드들 — GET /v1/files/{key}/nodes?ids=… 없는 id는 Optional.empty() */
    Map<String, Optional<FigmaNode>> nodes(String fileKey, List<String> ids);

    /**
     * 노드 하나의 원본 document(자식 포함) — GET /v1/files/{key}/nodes?ids={id}. scan이 fills·cornerRadius·style 값을 읽는다.
     * 없는 id는 Optional.empty()
     */
    Optional<JsonNode> nodeDocument(String fileKey, String nodeId);
}
