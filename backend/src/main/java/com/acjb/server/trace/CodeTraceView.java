package com.acjb.server.trace;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** openapi CodeTrace — 이슈 상세의 코드 추적(AC34) */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CodeTraceView(String issueKey, Code code, List<Related> related, Origin origin, List<Fix> fixes, String github) {

    /** 첫 QA가 값을 읽은 곳 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Code(String path, Integer line, String symbol, String commitSha, String url) {
    }

    /** 같은 Figma 요소의 다른 첫 QA 이슈 — 표의 행 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Related(String issueId, String property, String expectedValue, String actualValue, String status) {
    }

    /** 문제 코드가 들어온 커밋과 그 PR. basis=line(blame)·file(그 파일을 마지막으로 바꾼 커밋) */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Origin(String basis, Commit commit, List<Pull> pullRequests) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Commit(String sha, String message, String author, Instant committedAt, String url) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Pull(int number, String title, String state, String author, Instant createdAt, String url) {
    }

    /** 고친 커밋·PR. source=key(이슈 키)·manual(담당 팀이 붙임)·re_qa(Re-QA 판정 커밋). verdict는 Re-QA 판정이 있을 때 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Fix(String kind, String source, Commit commit, Pull pullRequest, String url, String verdict, Instant at) {

        static Fix ofCommit(String source, Commit c, String verdict) {
            return new Fix("commit", source, c, null, c.url(), verdict, c.committedAt());
        }

        static Fix ofPull(String source, Pull p) {
            return new Fix("pull_request", source, null, p, p.url(), null, p.createdAt());
        }

        Fix withVerdict(String v) {
            return new Fix(kind, source, commit, pullRequest, url, v, at);
        }

        /** 같은 커밋(앞 7자리)이나 같은 PR 번호인지 */
        boolean sameTarget(Fix o) {
            if (pullRequest != null || o.pullRequest != null) {
                return pullRequest != null && o.pullRequest != null && pullRequest.number() == o.pullRequest.number();
            }
            String a = commit.sha();
            String b = o.commit.sha();
            int n = Math.min(a.length(), b.length());
            return n >= 7 && a.substring(0, n).equalsIgnoreCase(b.substring(0, n));
        }
    }
}
