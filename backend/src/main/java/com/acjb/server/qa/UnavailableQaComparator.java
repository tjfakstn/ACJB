package com.acjb.server.qa;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 가짜 비교기를 켜지 않았을 때(기본). 실제 비교기는 첫 QA 스파이크 뒤에 만들기 때문에, 그 전까지는 실행을 실패로 끝낸다
 * — 근거 없이 이슈를 만들지 않는다(N17)
 */
@Component
@ConditionalOnProperty(name = "app.qa.fake-comparator", havingValue = "false", matchIfMissing = true)
public class UnavailableQaComparator implements QaComparator {

    static final String MESSAGE = "QA 비교용 LLM API 키(ANTHROPIC_API_KEY)가 설정되지 않았어요.";

    @Override
    public boolean demo() {
        return false;
    }

    @Override
    public FirstQaResult compare(FirstQaInput input) {
        throw new QaFailedException(MESSAGE);
    }

    @Override
    public ReQaResult recheck(ReQaInput input) {
        throw new QaFailedException(MESSAGE);
    }
}
