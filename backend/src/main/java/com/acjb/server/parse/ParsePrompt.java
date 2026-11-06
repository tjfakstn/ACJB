package com.acjb.server.parse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 파싱 프롬프트. 정본은 저장소의 src/qating/prompts/parse_query.md이고, 빌드 때 classpath로 복사된 파일에서
 * <!-- prompt:start --> ~ <!-- prompt:end --> 사이만 읽는다 — 프롬프트를 코드에 복제하지 않는다.
 */
@Component
public class ParsePrompt {

    static final String RESOURCE = "qating/prompts/parse_query.md";
    private static final String START = "<!-- prompt:start -->";
    private static final String END = "<!-- prompt:end -->";
    private static final String PLACEHOLDER = "{{text}}";
    /** 입력이 구분자 태그를 닫고 지시를 끼워 넣지 못하게 태그 문자열만 무력화한다(프롬프트 규칙 1의 보강) */
    private static final Pattern TAG = Pattern.compile("</?\\s*issue_text\\s*>", Pattern.CASE_INSENSITIVE);

    private final String template;

    public ParsePrompt() {
        this.template = load();
    }

    public String render(String text) {
        String safe = TAG.matcher(text).replaceAll("[issue_text]");
        return template.replace(PLACEHOLDER, safe);
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
            if (!prompt.contains(PLACEHOLDER)) {
                throw new IllegalStateException(RESOURCE + " 프롬프트에 " + PLACEHOLDER + " 자리가 없습니다.");
            }
            return prompt;
        } catch (IOException e) {
            throw new IllegalStateException(RESOURCE + "를 읽지 못했습니다.", e);
        }
    }
}
