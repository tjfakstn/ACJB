package com.acjb.server.recheck;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;

/**
 * Claude Messages API로 재검증 범위를 제안받는다. 구조화 출력에 RecheckSuggestion 스키마를 걸고, 받은 뒤에는
 * RecheckSuggestionService가 정본 스키마로 다시 검증한다. 키는 parse와 같은 ANTHROPIC_API_KEY — 키·프롬프트는 로그에 남기지 않는다.
 */
@Component
public class ClaudeRecheckSuggester implements RecheckSuggester {

    private static final Logger log = LoggerFactory.getLogger(ClaudeRecheckSuggester.class);
    /** 제안 5개짜리 JSON + 적응형 사고 여유분 */
    private static final long MAX_TOKENS = 8192L;

    private final String model;
    private final AnthropicClient client;
    private final JsonOutputFormat outputFormat;

    public ClaudeRecheckSuggester(RecheckSuggestionSchema schema,
                                  @Value("${app.recheck.model}") String model,
                                  @Value("${app.recheck.api-key:}") String apiKey,
                                  @Value("${app.recheck.timeout:60s}") Duration timeout) {
        this.model = model;
        this.client = apiKey == null || apiKey.isBlank() ? null
            : AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(timeout).build();
        Map<String, JsonValue> schemaProps = schema.outputFormatSchema().entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> JsonValue.from(e.getValue())));
        this.outputFormat = JsonOutputFormat.builder()
            .schema(JsonOutputFormat.Schema.builder().putAllAdditionalProperties(schemaProps).build())
            .build();
    }

    @Override
    public String suggest(String prompt) {
        if (client == null) {
            throw new RecheckFailedException("LLM API 키(ANTHROPIC_API_KEY)가 설정되지 않았습니다.");
        }
        MessageCreateParams params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(MAX_TOKENS)
            // 문서 사이의 연결을 따져야 해서 parse(low)보다 한 단계 높인다
            .outputConfig(OutputConfig.builder().format(outputFormat).effort(OutputConfig.Effort.MEDIUM).build())
            .addUserMessage(prompt)
            .build();
        Message response;
        try {
            response = client.messages().create(params);
        } catch (AnthropicException e) {
            log.warn("재검증 범위 제안 LLM 호출 실패: {}", e.getClass().getSimpleName());
            throw new RecheckFailedException("LLM 호출에 실패했습니다.", e);
        }
        StopReason stop = response.stopReason().orElse(null);
        if (!StopReason.END_TURN.equals(stop)) {
            log.warn("재검증 범위 제안 LLM이 정상 종료하지 않음: stop_reason={}", stop);
            throw new RecheckFailedException("LLM이 결과를 끝까지 내지 못했습니다(stop_reason=" + stop + ").");
        }
        return response.content().stream()
            .flatMap(block -> block.text().stream())
            .map(t -> t.text())
            .collect(Collectors.joining());
    }
}
