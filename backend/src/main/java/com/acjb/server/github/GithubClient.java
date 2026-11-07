package com.acjb.server.github;

/**
 * GitHub REST API 중 저장소 연결 확인(AC21)에 쓰는 호출. 코드만 읽고 이슈·PR은 다루지 않는다(N13).
 * 테스트에서는 가짜로 바꿔 끼운다
 */
public interface GithubClient {

    /** GET /repos/{owner}/{repo}. 실패하면 GithubApiException(GitHub가 준 상태, 응답이 없으면 0) */
    RepoInfo repository(String owner, String repo);

    record RepoInfo(String fullName, String defaultBranch, boolean isPrivate) {
    }
}
