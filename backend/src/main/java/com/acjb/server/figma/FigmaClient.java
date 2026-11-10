package com.acjb.server.figma;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;

/** Figma REST API 중 동기화·scan에 쓰는 호출. 테스트에서는 가짜로 바꿔 끼운다 */
public interface FigmaClient {

    /** 토큰이 설정돼 호출할 수 있는 상태인지 — 요청 단계에서 미리 거부하는 데 쓴다 */
    boolean isConfigured();

    /** 파일 이름과 페이지 목록 — GET /v1/files/{key}?depth=1. 연결할 때 보기 권한과 페이지 링크인지 확인하는 데 쓴다(AC21) */
    FileOutline outline(String fileKey);

    record Page(String id, String name) {
    }

    record FileOutline(String name, List<Page> pages) {
    }

    /** 파일의 각 페이지 바로 아래 노드(화면 단위 프레임 후보) — GET /v1/files/{key}?depth=2 */
    List<FigmaNode> topLevelNodes(String fileKey);

    /** 지정한 노드들(이름·종류만) — GET /v1/files/{key}/nodes?ids=…&depth=1. 없는 id는 Optional.empty() */
    Map<String, Optional<FigmaNode>> nodes(String fileKey, List<String> ids);

    /**
     * 노드 하나의 원본 document(자식 포함) — GET /v1/files/{key}/nodes?ids={id}. scan이 fills·cornerRadius·style 값을 읽는다.
     * 없는 id는 Optional.empty()
     */
    Optional<JsonNode> nodeDocument(String fileKey, String nodeId);

    /**
     * 노드의 바로 아래 자식(이름·종류만) — GET /v1/files/{key}/nodes?ids={id}&depth=1. 비교 분석이 페이지·섹션 안을 한 단계씩 내려가는 데 쓴다(AC22).
     * 노드가 없으면 Optional.empty()
     */
    Optional<List<FigmaNode>> children(String fileKey, String nodeId);

    /** 여러 노드의 원본 document(하위 트리 전체) — GET /v1/files/{key}/nodes?ids=a,b,… 없는 id는 결과에서 빠진다 */
    Map<String, JsonNode> nodeDocuments(String fileKey, List<String> ids);

    /** 노드의 Figma 캔버스 좌표 상자(absoluteBoundingBox) */
    record Box(double x, double y, double width, double height) {
    }

    /** 노드들의 좌표 상자 — GET /v1/files/{key}/nodes?ids=…&depth=1. 없는 id·상자가 없는 노드는 결과에서 빠진다(실시간 QA 워크스페이스, AC32) */
    Map<String, Box> bounds(String fileKey, List<String> ids);

    /** 노드를 PNG로 렌더링한 임시 이미지 주소 — GET /v1/images/{key}?ids={id}&format=png&scale={scale}. 렌더링할 수 없으면 Optional.empty() */
    Optional<String> renderUrl(String fileKey, String nodeId, double scale);
}
