package com.acjb.server.parse;

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
 * IssueReportContext JSON Schema(정본: src/qating/schemas/IssueReportContext.schema.json, 빌드 때 classpath로 복사).
 * LLM 출력은 받은 뒤 반드시 이 스키마로 검증한다(AGENTS.md 5절) — 구조화 출력을 켜도 검증을 생략하지 않는다.
 * networknt 검증기가 Jackson 2를 써서 이 클래스만 com.fasterxml.jackson을 쓴다(Spring은 Jackson 3).
 */
@Component
public class IssueReportContextSchema {

    static final String RESOURCE = "qating/schemas/IssueReportContext.schema.json";
    /** API의 output_config.format에는 검증 규칙만 보낸다 — 설명용 키는 뺀다 */
    private static final Set<String> ANNOTATION_KEYS = Set.of("$schema", "title", "description");

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonSchema schema;
    private final Map<String, Object> outputFormatSchema;

    public IssueReportContextSchema() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            JsonNode node = mapper.readTree(in);
            this.schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(node);
            Map<String, Object> raw = mapper.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() { });
            ANNOTATION_KEYS.forEach(raw::remove);
            this.outputFormatSchema = Map.copyOf(toApiForm(raw));
        } catch (IOException e) {
            throw new IllegalStateException(RESOURCE + "를 읽지 못했습니다.", e);
        }
    }

    /**
     * 구조화 출력 API는 "type": ["string","null"] + enum 조합을 거부한다(400 "Enum value 'common' does not match
     * declared type", 2026-11-04 실호출로 확인). 같은 뜻의 anyOf [{type:string, enum:[...]}, {type:null}]로 바꿔 보낸다.
     * 정본 파일과 서버 쪽 검증(validate)은 그대로 둔다 — 바뀌는 건 API에 보내는 사본뿐이다.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> toApiForm(Map<String, Object> schema) {
        Object props = schema.get("properties");
        if (!(props instanceof Map<?, ?> properties)) {
            return schema;
        }
        Map<String, Object> converted = new LinkedHashMap<>();
        ((Map<String, Object>) properties).forEach((name, value) -> {
            Map<String, Object> prop = new LinkedHashMap<>((Map<String, Object>) value);
            if (prop.get("type") instanceof List<?> types && types.size() == 2 && types.contains("null")) {
                Object nonNull = types.stream().filter(t -> !"null".equals(t)).findFirst().orElseThrow();
                Map<String, Object> valueBranch = new LinkedHashMap<>();
                valueBranch.put("type", nonNull);
                if (prop.get("enum") instanceof List<?> values) {
                    valueBranch.put("enum", values.stream().filter(java.util.Objects::nonNull).toList());
                }
                prop = new LinkedHashMap<>();
                prop.put("anyOf", List.of(valueBranch, Map.of("type", "null")));
            }
            converted.put(name, prop);
        });
        Map<String, Object> out = new LinkedHashMap<>(schema);
        out.put("properties", converted);
        return out;
    }

    /** Claude 구조화 출력(output_config.format)에 넣을 스키마 */
    public Map<String, Object> outputFormatSchema() {
        return outputFormatSchema;
    }

    /** 스키마를 통과하면 파싱된 노드를, 아니면 ParseFailedException */
    public JsonNode validate(String json) {
        JsonNode node;
        try {
            node = mapper.readTree(json);
        } catch (IOException e) {
            throw new ParseFailedException("모델 출력이 JSON이 아닙니다.", e);
        }
        if (node == null || node.isMissingNode()) {
            throw new ParseFailedException("모델 출력이 비어 있습니다.");
        }
        Set<ValidationMessage> errors = schema.validate(node);
        if (!errors.isEmpty()) {
            List<String> messages = errors.stream().map(ValidationMessage::getMessage).sorted().toList();
            throw new ParseFailedException("모델 출력이 IssueReportContext 스키마를 벗어났습니다: " + String.join("; ", messages));
        }
        return node;
    }
}
