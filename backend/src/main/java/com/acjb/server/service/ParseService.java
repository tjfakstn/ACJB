package com.acjb.server.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.acjb.server.common.ApiException;
import com.acjb.server.common.ErrorCodes;
import com.acjb.server.dto.ParseResponse;
import com.acjb.server.parse.IssueReportContextSchema;
import com.acjb.server.parse.IssueTextParser;
import com.acjb.server.parse.ParseFailedException;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * POST /qa-issues/parse (AC8). DB에 아무것도 쓰지 않는다 — 이슈 자동 등록 금지(N9). 그래서 Repository를 갖지 않는다.
 * LLM 출력은 IssueReportContext 스키마로 검증하고, 벗어나면 원문을 넣거나 재시도로 값을 만들지 않고 실패로 응답한다.
 * missing은 프롬프트가 아니라 여기서 계산한다(parse_query.md: screen·element·repro_steps 중 비어 있는 것).
 */
@Service
public class ParseService {

    /** 이슈 제보 한 건 분량으로 충분한 길이. 넘으면 400 — 비용·지연이 커지는 입력을 막는다 */
    static final int TEXT_MAX = 5000;

    private final IssueTextParser parser;
    private final IssueReportContextSchema schema;

    public ParseService(IssueTextParser parser, IssueReportContextSchema schema) {
        this.parser = parser;
        this.schema = schema;
    }

    public ParseResponse parse(String text) {
        if (text == null || text.isBlank()) {
            throw ApiException.badRequest("필수 항목이 비어 있습니다: text");
        }
        if (text.length() > TEXT_MAX) {
            throw ApiException.badRequest("text는 " + TEXT_MAX + "자 이하여야 합니다.");
        }
        JsonNode node;
        try {
            node = schema.validate(parser.parse(text));
        } catch (ParseFailedException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCodes.PARSE_FAILED,
                "이슈 텍스트를 해석하지 못했습니다. 직접 입력해 주세요. (" + e.getMessage() + ")");
        }
        ParseResponse.Context context = new ParseResponse.Context(
            textOrNull(node, "screen"), textOrNull(node, "element"), textOrNull(node, "symptom"),
            steps(node), textOrNull(node, "platform_scope"));

        List<String> missing = new ArrayList<>();
        if (isBlank(context.screen())) missing.add("screen");
        if (isBlank(context.element())) missing.add("element");
        if (context.reproSteps().isEmpty()) missing.add("repro_steps");
        return new ParseResponse(context, missing);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /** 공백만 있는 항목은 재현 절차로 보지 않는다 — 등록(AC1)에서도 빈 값으로 거부되기 때문 */
    private static List<String> steps(JsonNode node) {
        List<String> steps = new ArrayList<>();
        node.get("repro_steps").forEach(s -> {
            if (!s.asText().isBlank()) steps.add(s.asText());
        });
        return List.copyOf(steps);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
