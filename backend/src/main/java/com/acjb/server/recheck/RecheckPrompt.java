package com.acjb.server.recheck;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 재검증 범위 제안 프롬프트. 정본은 src/qating/prompts/recheck_scope.md(빌드 때 classpath로 복사)의
 * prompt:start ~ prompt:end 사이 — 코드에 복제하지 않는다.
 */
@Component
public class RecheckPrompt {

    static final String RESOURCE = "qating/prompts/recheck_scope.md";
    private static final String START = "<!-- prompt:start -->";
    private static final String END = "<!-- prompt:end -->";
    private static final String TARGET = "{{target}}";
    private static final String DOCUMENTS = "{{documents}}";
    /** 이슈 문장이 구분 태그를 닫고 지시를 끼워 넣지 못하게 태그 문자열만 무력화한다(프롬프트 규칙 1의 보강) */
    private static final Pattern TAG = Pattern.compile("</?\\s*(target_issue|documents|document)\\b[^>]*>", Pattern.CASE_INSENSITIVE);

    /** LLM에 넘기는 문서 하나 — id는 "qa_issue:7" 형식 */
    public record Document(String id, String text) {
    }

    private final String template;

    public RecheckPrompt() {
        this.template = load();
    }

    public String render(String target, List<Document> documents) {
        StringBuilder docs = new StringBuilder();
        for (Document d : documents) {
            docs.append("<document id=\"").append(d.id()).append("\">\n")
                .append(safe(d.text())).append("\n</document>\n");
        }
        return template.replace(TARGET, safe(target)).replace(DOCUMENTS, docs.toString().strip());
    }

    private static String safe(String text) {
        return TAG.matcher(text).replaceAll("[$1]");
    }

    private static String load() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            String doc = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int start = doc.indexOf(START);
            int end = doc.indexOf(END);
            if (start < 0 || end < start) {
                throw new IllegalStateException(RESOURCE + "에서 prompt:start/end 표시를 찾지 못했습니다.");
            }
            String prompt = doc.substring(start + START.length(), end).strip();
            if (!prompt.contains(TARGET) || !prompt.contains(DOCUMENTS)) {
                throw new IllegalStateException(RESOURCE + " 프롬프트에 " + TARGET + " 또는 " + DOCUMENTS + " 자리가 없습니다.");
            }
            return prompt;
        } catch (IOException e) {
            throw new IllegalStateException(RESOURCE + "를 읽지 못했습니다.", e);
        }
    }
}
