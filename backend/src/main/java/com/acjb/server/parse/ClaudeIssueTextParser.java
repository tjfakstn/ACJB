package com.acjb.server.parse;

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
 * Claude Messages API로 파싱한다. 구조화 출력(output_config.format)에 IssueReportContext 스키마를 걸어
 * 모델이 그 형태로만 답하게 하고, 받은 뒤에는 ParseService가 같은 스키마로 다시 검증한다.
 * API 키는 backend/.env의 ANTHROPIC_API_KEY — 없으면 호출하지 않고 실패한다. 키와 입력 원문은 로그에 남기지 않는다.
 */
@Component
public class ClaudeIssueTextParser implements IssueTextParser {

    private static final Logger log = LoggerFactory.getLogger(ClaudeIssueTextParser.class);
    /** 5개 필드짜리 짧은 JSON + 적응형 사고 여유분 */
    private static final long MAX_TOKENS = 4096L;

    private final ParsePrompt prompt;
    private final String model;
    private final AnthropicClient client;
    private final JsonOutputFormat outputFormat;

    public ClaudeIssueTextParser(ParsePrompt prompt, IssueReportContextSchema schema,
                                 @Value("${app.parse.model}") String model,
                                 @Value("${app.parse.api-key:}") String apiKey,
                                 @Value("${app.parse.timeout:30s}") Duration timeout) {
        this.prompt = prompt;
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
    public String parse(String text) {
        if (client == null) {
            throw new ParseFailedException("파싱용 LLM API 키(ANTHROPIC_API_KEY)가 설정되지 않았습니다.");
        }
        MessageCreateParams params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(MAX_TOKENS)
            // 짧은 추출 작업이라 effort는 low. 사고(thinking)는 모델 기본값(적응형)을 따른다
            .outputConfig(OutputConfig.builder().format(outputFormat).effort(OutputConfig.Effort.LOW).build())
            .addUserMessage(prompt.render(text))
            .build();
        Message response;
        try {
            response = client.messages().create(params);
        } catch (AnthropicException e) {
            log.warn("파싱 LLM 호출 실패: {}", e.getClass().getSimpleName());
            throw new ParseFailedException("LLM 호출에 실패했습니다.", e);
        }
        StopReason stop = response.stopReason().orElse(null);
        if (!StopReason.END_TURN.equals(stop)) {
            // refusal·max_tokens 등 — 잘린 출력이나 거절 문구를 결과로 쓰지 않는다
            log.warn("파싱 LLM이 정상 종료하지 않음: stop_reason={}", stop);
            throw new ParseFailedException("LLM이 결과를 끝까지 내지 못했습니다(stop_reason=" + stop + ").");
        }
        return response.content().stream()
            .flatMap(block -> block.text().stream())
            .map(t -> t.text())
            .collect(Collectors.joining());
    }
}
