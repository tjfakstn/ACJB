package com.acjb.server.recheck;

import java.util.Locale;
import java.util.StringJoiner;

import com.acjb.server.domain.QAIssue;
import com.acjb.server.domain.StyleMismatch;
import com.acjb.server.domain.Verification;

/**
 * 임베딩·LLM 입력에 쓰는 문서 텍스트(설계 docs/design-recheck-suggestions.md 3절).
 * 상태·담당자·작성자 이름은 넣지 않는다 — 의미 검색에 잡음이고 개인정보다. evals/run_evals.py도 같은 규칙을 따른다.
 */
public final class EmbeddingTexts {

    private EmbeddingTexts() {
    }

    public static String issue(QAIssue issue) {
        StringJoiner j = new StringJoiner("\n");
        j.add("화면: " + issue.getScreenLocation());
        j.add("증상: " + issue.getDescription());
        j.add("재현: " + String.join(" → ", issue.getReproSteps()));
        j.add("플랫폼: " + issue.getPlatformScope().name().toLowerCase(Locale.ROOT));
        return j.toString();
    }

    public static String mismatch(StyleMismatch m) {
        StringJoiner j = new StringJoiner("\n");
        if (m.getScreen() != null && !m.getScreen().isBlank()) {
            j.add("화면: " + m.getScreen());
        }
        if (m.getSelector() != null && !m.getSelector().isBlank()) {
            j.add("요소: " + m.getSelector());
        }
        j.add(m.getProperty().name().toLowerCase(Locale.ROOT) + ": 기대 " + m.getExpectedValue() + " / 실제 " + m.getActualValue());
        return j.toString();
    }

    public static String failedVerification(Verification v) {
        StringJoiner j = new StringJoiner("\n");
        j.add("화면: " + v.getIssue().getScreenLocation());
        j.add("증상: " + v.getIssue().getDescription());
        j.add("결과: 재검증 실패");
        if (v.getScope() != null && !v.getScope().isBlank()) {
            j.add("범위: " + v.getScope());
        }
        return j.toString();
    }
}
