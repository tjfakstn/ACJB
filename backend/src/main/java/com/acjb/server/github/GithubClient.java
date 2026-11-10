package com.acjb.server.github;

/**
 * GitHub API 중 저장소 연결 확인(AC21)·비교 분석(AC22)·코드 추적(AC34)에 쓰는 호출. 모두 읽기만 한다 — 이슈·PR·코멘트를 만들거나 바꾸지 않는다(N13).
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

    // ---------------------------------------------------------------- 코드 추적(AC34) — 읽기만

    /** 커밋 하나. message는 첫 줄, author는 GitHub 로그인(없으면 커밋 작성자 이름) */
    record CommitInfo(String sha, String message, String author, java.time.Instant committedAt, String url) {
    }

    /** PR 하나. state는 open·closed·merged */
    record PullInfo(int number, String title, String body, String headRef, String state, String author, java.time.Instant createdAt,
                    String url) {
    }

    /** 줄 기준 — GraphQL blame으로 그 커밋에서 path의 line을 마지막으로 바꾼 커밋과 그 PR. 토큰이 없으면 빈 값(GraphQL은 토큰이 필요하다) */
    java.util.Optional<Origin> blameLine(String owner, String repo, String sha, String path, int line);

    record Origin(CommitInfo commit, java.util.List<PullInfo> pulls) {
    }

    /** 파일 기준 — GET /repos/{owner}/{repo}/commits?sha={sha}&path={path}&per_page=1 */
    java.util.Optional<CommitInfo> lastCommitForPath(String owner, String repo, String sha, String path);

    /** GET /repos/{owner}/{repo}/commits/{sha}/pulls — 그 커밋이 속한 PR */
    java.util.List<PullInfo> pullsForCommit(String owner, String repo, String sha);

    /** GET /repos/{owner}/{repo}/commits?sha={branch}&since={since}&per_page=100 — 최근 커밋(최대 100개) */
    java.util.List<CommitInfo> commitsSince(String owner, String repo, String branch, java.time.Instant since);

    /** GET /repos/{owner}/{repo}/pulls?state=all&sort=created&direction=desc&per_page=100 — 최근 PR(최대 100개) */
    java.util.List<PullInfo> recentPulls(String owner, String repo);

    /** GET /repos/{owner}/{repo}/commits/{sha} — 없으면 빈 값 */
    java.util.Optional<CommitInfo> commit(String owner, String repo, String sha);

    /** GET /repos/{owner}/{repo}/pulls/{number} — 없으면 빈 값 */
    java.util.Optional<PullInfo> pull(String owner, String repo, int number);

    /** 토큰이 있으면 true — 줄 기준 blame을 쓸 수 있다 */
    boolean hasToken();
}
