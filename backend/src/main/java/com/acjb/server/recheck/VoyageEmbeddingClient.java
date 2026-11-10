package com.acjb.server.recheck;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Voyage AI 임베딩(POST /v1/embeddings, 2026-11-05 결정). 키는 backend/.env의 VOYAGE_API_KEY — 키와 입력 문장은 로그에 남기지 않는다.
 * 응답은 개수·순서(index)·차원을 검사한 뒤에만 쓴다(AGENTS.md 5절 외부 API 응답 검증).
 */
@Component
public class VoyageEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(VoyageEmbeddingClient.class);
    /** 한 요청에 넣는 문장 수. Voyage 한도(1,000개·voyage-4 32만 토큰)보다 넉넉히 작게 */
    private static final int BATCH = 100;

    private final RestClient http;
    private final JsonMapper json;
    private final String model;
    private final int dimension;
    private final boolean configured;

    public VoyageEmbeddingClient(JsonMapper json,
                                 @Value("${app.voyage.base-url:https://api.voyageai.com}") String baseUrl,
                                 @Value("${app.voyage.api-key:}") String apiKey,
                                 @Value("${app.voyage.model:voyage-4}") String model,
                                 @Value("${app.voyage.dimension:1024}") int dimension,
                                 @Value("${app.voyage.timeout:30s}") Duration timeout) {
        this.json = json;
        this.model = model;
        this.dimension = dimension;
        this.configured = apiKey != null && !apiKey.isBlank();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
            .defaultHeader("Authorization", "Bearer " + (configured ? apiKey : "")).build();
    }

    @Override
    public boolean isConfigured() {
        return configured;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public List<float[]> embed(List<String> texts, InputType type) {
        if (!configured) {
            throw new RecheckFailedException("임베딩 API 키(VOYAGE_API_KEY)가 설정되지 않았습니다.");
        }
        List<float[]> out = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += BATCH) {
            out.addAll(call(texts.subList(from, Math.min(texts.size(), from + BATCH)), type));
        }
        return out;
    }

    private List<float[]> call(List<String> texts, InputType type) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("input", texts);
        body.put("model", model);
        body.put("input_type", type == InputType.QUERY ? "query" : "document");
        body.put("output_dimension", dimension);
        String raw;
        try {
            raw = http.post().uri("/v1/embeddings").contentType(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsString(body)).retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            log.warn("Voyage 임베딩 호출 실패: status={}", e.getStatusCode().value());
            throw new RecheckFailedException("임베딩 API 호출에 실패했습니다(HTTP " + e.getStatusCode().value() + ").", e);
        } catch (ResourceAccessException e) {
            log.warn("Voyage 임베딩 연결 실패: {}", e.getClass().getSimpleName());
            throw new RecheckFailedException("임베딩 API에 연결하지 못했습니다(시간 초과 또는 네트워크 오류).", e);
        }
        return parse(raw, texts.size());
    }

    private List<float[]> parse(String raw, int expected) {
        JsonNode data;
        try {
            data = json.readTree(raw).get("data");
        } catch (RuntimeException e) {
            throw new RecheckFailedException("임베딩 API 응답이 JSON이 아닙니다.", e);
        }
        if (data == null || !data.isArray() || data.size() != expected) {
            throw new RecheckFailedException("임베딩 API 응답의 벡터 개수가 요청과 다릅니다.");
        }
        float[][] ordered = new float[expected][];
        for (JsonNode item : data) {
            JsonNode index = item.get("index");
            JsonNode embedding = item.get("embedding");
            if (index == null || !index.isInt() || index.asInt() < 0 || index.asInt() >= expected
                || ordered[index.asInt()] != null || embedding == null || !embedding.isArray() || embedding.size() != dimension) {
                throw new RecheckFailedException("임베딩 API 응답 형식이 예상과 다릅니다.");
            }
            float[] v = new float[dimension];
            for (int i = 0; i < dimension; i++) {
                JsonNode x = embedding.get(i);
                if (!x.isNumber()) {
                    throw new RecheckFailedException("임베딩 API 응답 형식이 예상과 다릅니다.");
                }
                v[i] = x.floatValue();
            }
            ordered[index.asInt()] = v;
        }
        return List.of(ordered);
    }
}
