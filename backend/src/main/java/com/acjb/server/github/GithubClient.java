package com.acjb.server.github;

/**
 * GitHub REST API 중 저장소 연결 확인(AC21)에 쓰는 호출. 코드만 읽고 이슈·PR은 다루지 않는다(N13).
 * 테스트에서는 가짜로 바꿔 끼운다
 */
public interface GithubClient {

    /** GET /repos/{owner}/{repo}. 실패하면 GithubApiException(GitHub가 준 상태, 응답이 없으면 0) */
    RepoInfo repository(String owner, String repo);

    /** GET /repos/{owner}/{repo}/branches?per_page=100 — 브랜치 이름(받은 순서, 최대 100개) */
    java.util.List<String> branches(String owner, String repo);

    /** GET /repos/{owner}/{repo}/branches/{branch} — 404면 false. 목록 100개 밖의 브랜치를 고를 때 확인한다 */
    boolean branchExists(String owner, String repo, String branch);

    record RepoInfo(String fullName, String defaultBranch, boolean isPrivate) {
    }
}
