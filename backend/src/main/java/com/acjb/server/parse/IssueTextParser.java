package com.acjb.server.parse;

/** 자유서술 이슈 텍스트를 LLM에 보내 IssueReportContext JSON 문자열을 받는다. 검증은 호출한 쪽(ParseService)이 한다 */
public interface IssueTextParser {

    /** @return 모델이 낸 JSON 문자열 그대로. 실패하면 ParseFailedException */
    String parse(String text);
}
