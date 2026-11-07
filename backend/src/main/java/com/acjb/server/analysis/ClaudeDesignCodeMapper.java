package com.acjb.server.analysis;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicException;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Claude로 프레임·노드 ↔ 코드 짝을 만든다(AC22). 프롬프트는 스파이크(docs/spikes/2026-11-07_design-code-mapping.md)와 같다.
 * 코드 원문은 시스템 프롬프트 끝에 두고 캐시한다 — 프레임 단계와 프레임마다 부르는 노드 단계가 같은 접두부를 다시 쓴다(2026-11-07 결정).
 * 구조화 출력으로 형태를 강제하고, 받은 JSON은 여기서 다시 형태를 검사한다(AGENTS.md 5절). API 키와 입력 원문은 로그에 남기지 않는다
 */
@Component
public class ClaudeDesignCodeMapper implements DesignCodeMapper {

    private static final Logger log = LoggerFactory.getLogger(ClaudeDesignCodeMapper.class);

    static final String SYSTEM = "You map Figma design frames to the UI source files that implement them. "
        + "Only use file paths that appear verbatim in the provided code. "
        + "If no provided file implements a frame (the screen is not built yet, or it is not a UI screen, "
        + "e.g. an email, a spec/annotation board, a flow label, a component sheet), return null. "
        + "Do not guess: a frame must be mapped only when its visible content (texts, structure) matches code that renders it.";
    static final String FRAMES_INSTRUCTION = "Return exactly one mapping per frame id above: the single file that renders that screen "
        + "(the page or the dialog component), or null. Keep reason under 15 words.";
    static final String NODES_INSTRUCTION = "List the frame's main UI blocks (header, sidebar, page header, banners, cards/panels — "
        + "not individual texts, icons or rows) and for each give the file and the React component/function name that renders it, "
        + "or null/null if no code renders it.";

    /** 프레임 460개에 약 2.2만 토큰이 나왔다(스파이크) — 사고 여유분까지 */
    private static final long FRAMES_MAX_TOKENS = 64_000L;
    private static final long NODES_MAX_TOKENS = 16_000L;

    private final AnthropicClient client;
    private final String model;
    private final JsonMapper json;

    public ClaudeDesignCodeMapper(JsonMapper json,
                                  @Value("${app.analysis.model}") String model,
                                  @Value("${app.analysis.api-key:}") String apiKey,
                                  @Value("${app.analysis.timeout:10m}") Duration timeout) {
        this.json = json;
        this.model = model;
        this.client = apiKey == null || apiKey.isBlank() ? null
            : AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(timeout).build();
    }

    @Override
    public List<FrameMatch> mapFrames(List<FrameSummary> frames, CodeBundle code) {
        List<Map<String, Object>> input = new ArrayList<>();
        for (FrameSummary f : frames) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", f.id());
            m.put("section", f.section());
            m.put("name", f.name());
            m.put("children", f.children());
            m.put("texts", f.texts());
            input.add(m);
        }
        String user = "# Figma frames (one Figma page or section; frames grouped in sections)\n" + json.writeValueAsString(input)
            + "\n\n" + FRAMES_INSTRUCTION;
        Map<String, Object> schema = object(Map.of("mappings", Map.of("type", "array", "items",
            object(Map.of("frame_id", Map.of("type", "string"), "file", nullableString(), "reason", Map.of("type", "string"))))));
        JsonNode root = call(code, user, schema, FRAMES_MAX_TOKENS);
        List<FrameMatch> result = new ArrayList<>();
        for (JsonNode m : array(root, "mappings")) {
            result.add(new FrameMatch(requiredString(m, "frame_id"), optionalString(m, "file")));
        }
        return result;
    }

    @Override
    public List<NodeMatch> mapNodes(FrameDetail frame, CodeBundle code) {
        String user = "# Figma frame '" + frame.name() + "' node tree (id, type, name)\n" + frame.tree() + "\n" + NODES_INSTRUCTION;
        Map<String, Object> schema = object(Map.of("nodes", Map.of("type", "array", "items",
            object(Map.of("node_id", Map.of("type", "string"), "file", nullableString(), "symbol", nullableString())))));
        JsonNode root = call(code, user, schema, NODES_MAX_TOKENS);
        List<NodeMatch> result = new ArrayList<>();
        for (JsonNode m : array(root, "nodes")) {
            result.add(new NodeMatch(requiredString(m, "node_id"), optionalString(m, "file"), optionalString(m, "symbol")));
        }
        return result;
    }

    private JsonNode call(CodeBundle code, String user, Map<String, Object> schema, long maxTokens) {
        if (client == null) {
            throw new MappingFailedException("비교 분석용 LLM API 키(ANTHROPIC_API_KEY)가 설정되지 않았어요.");
        }
        Map<String, JsonValue> schemaProps = new LinkedHashMap<>();
        schema.forEach((k, v) -> schemaProps.put(k, JsonValue.from(v)));
        MessageCreateParams params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(maxTokens)
            .systemOfTextBlockParams(List.of(
                TextBlockParam.builder().text(SYSTEM).build(),
                // 코드 원문 — 한 분석 안의 모든 호출이 같은 접두부라 캐시로 다시 읽는다
                TextBlockParam.builder().text(codeText(code))
                    .cacheControl(CacheControlEphemeral.builder().build()).build()))
            .outputConfig(OutputConfig.builder()
                .format(JsonOutputFormat.builder().schema(JsonOutputFormat.Schema.builder().putAllAdditionalProperties(schemaProps).build()).build())
                .effort(OutputConfig.Effort.HIGH)
                .build())
            .addUserMessage(user)
            .build();
        Message response;
        try (StreamResponse<RawMessageStreamEvent> stream = client.messages().createStreaming(params)) {
            MessageAccumulator accumulator = MessageAccumulator.create();
            stream.stream().forEach(accumulator::accumulate);
            response = accumulator.message();
        } catch (AnthropicException e) {
            log.warn("비교 분석 LLM 호출 실패: {}", e.getClass().getSimpleName());
            throw new MappingFailedException("AI 분석 호출에 실패했어요. 잠시 뒤 다시 시도해 주세요.", e);
        }
        StopReason stop = response.stopReason().orElse(null);
        if (!StopReason.END_TURN.equals(stop)) {
            log.warn("비교 분석 LLM이 정상 종료하지 않음: stop_reason={}", stop);
            throw new MappingFailedException("AI 분석이 결과를 끝까지 내지 못했어요.");
        }
        log.info("비교 분석 LLM 사용량: input={} output={} cache_read={}", response.usage().inputTokens(), response.usage().outputTokens(),
            response.usage().cacheReadInputTokens().orElse(0L));
        String text = response.content().stream().flatMap(b -> b.text().stream()).map(t -> t.text()).findFirst()
            .orElseThrow(() -> new MappingFailedException("AI 분석 응답이 비어 있어요."));
        try {
            JsonNode root = json.readTree(text);
            if (root == null || !root.isObject()) {
                throw new MappingFailedException("AI 분석 응답 형식이 올바르지 않아요.");
            }
            return root;
        } catch (JacksonException e) {
            throw new MappingFailedException("AI 분석 응답 형식이 올바르지 않아요.", e);
        }
    }

    static String codeText(CodeBundle code) {
        StringBuilder sb = new StringBuilder("# Code (repository ").append(code.repo()).append(", commit ").append(code.commitSha()).append(")\n");
        code.files().forEach((path, src) -> sb.append("\n=== FILE: ").append(path).append(" ===\n").append(src).append('\n'));
        return sb.toString();
    }

    private static Map<String, Object> object(Map<String, Object> properties) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("type", "object");
        o.put("additionalProperties", false);
        o.put("required", List.copyOf(properties.keySet()));
        o.put("properties", properties);
        return o;
    }

    private static Map<String, Object> nullableString() {
        return Map.of("type", List.of("string", "null"));
    }

    private static List<JsonNode> array(JsonNode root, String field) {
        JsonNode v = root.get(field);
        if (v == null || !v.isArray()) {
            throw new MappingFailedException("AI 분석 응답 형식이 올바르지 않아요.");
        }
        List<JsonNode> list = new ArrayList<>();
        v.forEach(list::add);
        return list;
    }

    private static String requiredString(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || !v.isString()) {
            throw new MappingFailedException("AI 분석 응답 형식이 올바르지 않아요.");
        }
        return v.asString();
    }

    private static String optionalString(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isString()) {
            throw new MappingFailedException("AI 분석 응답 형식이 올바르지 않아요.");
        }
        return v.asString().isBlank() ? null : v.asString();
    }
}
