package com.acjb.server.github;

/**
 * GitHub REST API 중 저장소 연결 확인(AC21)과 비교 분석(AC22)에 쓰는 호출. 코드만 읽고 이슈·PR은 다루지 않는다(N13).
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

    /** GET /repos/{owner}/{repo}/commits/{branch} — 그 브랜치의 지금 커밋 sha(비교 분석은 이 시점 코드를 읽는다, AC22) */
    String commitSha(String owner, String repo, String branch);

    /** GET /repos/{owner}/{repo}/git/trees/{sha}?recursive=1 — 파일(blob)만. GitHub가 목록을 잘랐으면(truncated) 예외 */
    java.util.List<TreeFile> tree(String owner, String repo, String sha);

    /** GET /repos/{owner}/{repo}/contents/{path}?ref={sha} (raw) — 파일 원문 */
    String fileContent(String owner, String repo, String sha, String path);

    record TreeFile(String path, long size) {
    }

    /**
     * 최근 배포 중 마지막 상태가 success이고 environment_url이 있는 첫 배포의 주소(AC29). 없으면 빈 값.
     * GET /repos/{owner}/{repo}/deployments?per_page=10 과 배포마다 GET .../deployments/{id}/statuses?per_page=1 — 읽기만 한다
     */
    java.util.Optional<String> latestDeploymentUrl(String owner, String repo);
}
