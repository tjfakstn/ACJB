package com.acjb.server.recheck;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

/**
 * RecheckSuggestion JSON Schema(정본: src/qating/schemas/RecheckSuggestion.schema.json, 빌드 때 classpath로 복사).
 * LLM 출력은 받은 뒤 반드시 이 스키마로 검증한다(AGENTS.md 5절). networknt 검증기가 Jackson 2를 써서 이 클래스만 com.fasterxml을 쓴다.
 */
@Component
public class RecheckSuggestionSchema {

    static final String RESOURCE = "qating/schemas/RecheckSuggestion.schema.json";
    /** API의 output_config.format에는 설명용 키를 보내지 않는다 */
    private static final Set<String> ANNOTATION_KEYS = Set.of("$schema", "title", "description");
    /**
     * 구조화 출력은 maxItems·minLength 같은 길이 제약을 받지 않는다(400, Claude 문서의 Not supported 목록).
     * API에 보내는 사본에서만 빼고, 받은 뒤 서버 검증(validate)은 정본 그대로 한다.
     */
    private static final Set<String> UNSUPPORTED_KEYS = Set.of("maxItems", "minLength", "maxLength", "minimum", "maximum", "pattern");

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonSchema schema;
    private final Map<String, Object> outputFormatSchema;

    public RecheckSuggestionSchema() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            JsonNode node = mapper.readTree(in);
            this.schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(node);
            Map<String, Object> raw = mapper.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() { });
            ANNOTATION_KEYS.forEach(raw::remove);
            this.outputFormatSchema = Map.copyOf(stripUnsupported(raw));
        } catch (IOException e) {
            throw new IllegalStateException(RESOURCE + "를 읽지 못했습니다.", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stripUnsupported(Map<String, Object> node) {
        Map<String, Object> out = new LinkedHashMap<>();
        node.forEach((key, value) -> {
            if (UNSUPPORTED_KEYS.contains(key)) {
                return;
            }
            if (value instanceof Map<?, ?> child) {
                out.put(key, stripUnsupported((Map<String, Object>) child));
            } else {
                out.put(key, value);
            }
        });
        return out;
    }

    /** Claude 구조화 출력(output_config.format)에 넣을 스키마 */
    public Map<String, Object> outputFormatSchema() {
        return outputFormatSchema;
    }

    /** 스키마를 통과하면 파싱된 노드를, 아니면 RecheckFailedException(일부만 쓰지 않는다) */
    public JsonNode validate(String json) {
        JsonNode node;
        try {
            node = mapper.readTree(json);
        } catch (IOException e) {
            throw new RecheckFailedException("모델 출력이 JSON이 아닙니다.", e);
        }
        if (node == null || node.isMissingNode()) {
            throw new RecheckFailedException("모델 출력이 비어 있습니다.");
        }
        Set<ValidationMessage> errors = schema.validate(node);
        if (!errors.isEmpty()) {
            List<String> messages = errors.stream().map(ValidationMessage::getMessage).sorted().toList();
            throw new RecheckFailedException("모델 출력이 RecheckSuggestion 스키마를 벗어났습니다: " + String.join("; ", messages));
        }
        return node;
    }
}
