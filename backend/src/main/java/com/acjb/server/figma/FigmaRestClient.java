package com.acjb.server.figma;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Figma REST API 호출. 토큰은 backend/.env의 FIGMA_ACCESS_TOKEN(X-Figma-Token 헤더)이고 로그·예외 메시지에 넣지 않는다.
 * 응답은 쓰기 전에 필요한 필드·타입을 확인한다(AGENTS.md 5절) — 형식이 다르면 FigmaApiException.
 */
@Component
public class FigmaRestClient implements FigmaClient {

    private final RestClient http;
    private final JsonMapper json;
    private final boolean configured;

    public FigmaRestClient(JsonMapper json,
                           @Value("${app.figma.base-url:https://api.figma.com}") String baseUrl,
                           @Value("${app.figma.access-token:}") String token,
                           @Value("${app.figma.timeout:30s}") Duration timeout) {
        this.json = json;
        this.configured = token != null && !token.isBlank();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
            .defaultHeader("X-Figma-Token", configured ? token : "").build();
    }

    @Override
    public boolean isConfigured() {
        return configured;
    }

    @Override
    public FileOutline outline(String fileKey) {
        JsonNode root = get("/v1/files/{key}?depth=1", fileKey, null);
        List<Page> pages = new ArrayList<>();
        for (JsonNode page : requireArray(requireObject(root, "document"), "children")) {
            pages.add(new Page(requireText(page, "id"), requireText(page, "name")));
        }
        return new FileOutline(requireText(root, "name"), pages);
    }

    @Override
    public List<FigmaNode> topLevelNodes(String fileKey) {
        JsonNode root = get("/v1/files/{key}?depth=2", fileKey, null);
        JsonNode pages = requireArray(requireObject(root, "document"), "children");
        List<FigmaNode> result = new ArrayList<>();
        for (JsonNode page : pages) {
            if (!page.has("children")) {
                continue;
            }
            for (JsonNode child : requireArray(page, "children")) {
                result.add(toNode(child));
            }
        }
        return result;
    }

    @Override
    public Map<String, Optional<FigmaNode>> nodes(String fileKey, List<String> ids) {
        JsonNode root = get("/v1/files/{key}/nodes?ids={ids}&depth=1", fileKey, String.join(",", ids));
        JsonNode nodes = requireObject(root, "nodes");
        Map<String, Optional<FigmaNode>> result = new LinkedHashMap<>();
        for (String id : ids) {
            JsonNode entry = nodes.get(id);
            // Figma는 없는 id를 null 값으로 돌려준다
            if (entry == null || entry.isNull()) {
                result.put(id, Optional.empty());
            } else {
                result.put(id, Optional.of(toNode(requireObject(entry, "document"))));
            }
        }
        return result;
    }

    @Override
    public Optional<JsonNode> nodeDocument(String fileKey, String nodeId) {
        JsonNode nodes = requireObject(get("/v1/files/{key}/nodes?ids={ids}", fileKey, nodeId), "nodes");
        JsonNode entry = nodes.get(nodeId);
        if (entry == null || entry.isNull()) {
            return Optional.empty();
        }
        JsonNode document = requireObject(entry, "document");
        requireText(document, "id");
        requireText(document, "type");
        return Optional.of(document);
    }

    @Override
    public Optional<List<FigmaNode>> children(String fileKey, String nodeId) {
        JsonNode nodes = requireObject(get("/v1/files/{key}/nodes?ids={ids}&depth=1", fileKey, nodeId), "nodes");
        JsonNode entry = nodes.get(nodeId);
        if (entry == null || entry.isNull()) {
            return Optional.empty();
        }
        JsonNode document = requireObject(entry, "document");
        List<FigmaNode> result = new ArrayList<>();
        JsonNode children = document.get("children");
        if (children != null && children.isArray()) {
            for (JsonNode child : children) {
                result.add(toNode(child));
            }
        }
        return Optional.of(result);
    }

    @Override
    public Map<String, JsonNode> nodeDocuments(String fileKey, List<String> ids) {
        JsonNode nodes = requireObject(get("/v1/files/{key}/nodes?ids={ids}", fileKey, String.join(",", ids)), "nodes");
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (String id : ids) {
            JsonNode entry = nodes.get(id);
            if (entry != null && !entry.isNull()) {
                JsonNode document = requireObject(entry, "document");
                requireText(document, "id");
                requireText(document, "type");
                result.put(id, document);
            }
        }
        return result;
    }

    private JsonNode get(String path, String fileKey, String ids) {
        if (!configured) {
            throw new FigmaApiException("Figma 토큰(FIGMA_ACCESS_TOKEN)이 설정되지 않았습니다.");
        }
        String body;
        try {
            body = ids == null
                ? http.get().uri(path, fileKey).retrieve().body(String.class)
                : http.get().uri(path, fileKey, ids).retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            String errorBody = e.getResponseBodyAsString();
            int code = e.getStatusCode().value();
            boolean tokenProblem = code != 429 && errorBody != null && errorBody.toLowerCase(java.util.Locale.ROOT).contains("token");
            long retryAfter = -1;
            String header = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After");
            if (code == 429 && header != null && header.trim().matches("\\d{1,6}")) {
                retryAfter = Long.parseLong(header.trim());
            }
            throw new FigmaApiException(tokenProblem ? "서버 Figma 토큰이 만료됐거나 올바르지 않습니다." : describe(e.getStatusCode()),
                code, tokenProblem, retryAfter, e);
        } catch (ResourceAccessException e) {
            throw new FigmaApiException("Figma에 연결하지 못했습니다(시간 초과 또는 네트워크 오류).", e);
        }
        try {
            JsonNode root = json.readTree(body);
            if (root == null || !root.isObject()) {
                throw new FigmaApiException("Figma 응답 형식이 올바르지 않습니다.");
            }
            return root;
        } catch (JacksonException e) {
            throw new FigmaApiException("Figma 응답이 JSON이 아닙니다.", e);
        }
    }

    private static String describe(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "Figma 요청이 올바르지 않습니다(파일 키 또는 프레임 id 형식 확인).";
            case 403 -> "Figma 접근이 거부됐습니다(토큰 권한 또는 파일 공유 설정 확인).";
            case 404 -> "Figma 파일을 찾을 수 없습니다.";
            case 429 -> "Figma 요청 한도를 넘었습니다. 잠시 후 다시 시도하세요.";
            default -> "Figma 호출이 실패했습니다(HTTP " + status.value() + ").";
        };
    }

    private static FigmaNode toNode(JsonNode node) {
        return new FigmaNode(requireText(node, "id"), requireText(node, "name"), requireText(node, "type"));
    }

    private static JsonNode requireObject(JsonNode parent, String field) {
        JsonNode v = parent.get(field);
        if (v == null || !v.isObject()) {
            throw new FigmaApiException("Figma 응답에 " + field + " 객체가 없습니다.");
        }
        return v;
    }

    private static JsonNode requireArray(JsonNode parent, String field) {
        JsonNode v = parent.get(field);
        if (v == null || !v.isArray()) {
            throw new FigmaApiException("Figma 응답에 " + field + " 배열이 없습니다.");
        }
        return v;
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode v = parent.get(field);
        if (v == null || !v.isString() || v.asString().isBlank()) {
            throw new FigmaApiException("Figma 응답의 노드에 " + field + " 값이 없습니다.");
        }
        return v.asString();
    }
}
