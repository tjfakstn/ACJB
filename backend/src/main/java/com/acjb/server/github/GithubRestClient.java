package com.acjb.server.github;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 공개 저장소를 읽는 GitHub 클라이언트(2026-11-07 결정 — 공개 저장소 + 서버 토큰 하나, GitHub App 없음).
 * 토큰(GITHUB_TOKEN)은 선택이다 — 없으면 익명으로 읽고 호출 한도가 낮다
 */
@Component
public class GithubRestClient implements GithubClient {

    private final RestClient http;
    private final JsonMapper json;
    private final boolean hasToken;

    public GithubRestClient(JsonMapper json,
                            @Value("${app.github.base-url:https://api.github.com}") String baseUrl,
                            @Value("${app.github.token:}") String token,
                            @Value("${app.github.timeout:15s}") Duration timeout) {
        this.json = json;
        this.hasToken = token != null && !token.isBlank();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
            .defaultHeader("Accept", "application/vnd.github+json")
            .defaultHeader("User-Agent", "QAting");
        if (token != null && !token.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + token);
        }
        this.http = builder.build();
    }

    @Override
    public java.util.List<String> branches(String owner, String repo) {
        JsonNode root = read(() -> http.get().uri("/repos/{owner}/{repo}/branches?per_page=100", owner, repo).retrieve().body(String.class));
        if (root == null || !root.isArray()) {
            throw new GithubApiException("GitHub 응답 형식이 올바르지 않습니다.", 0, null);
        }
        java.util.List<String> names = new java.util.ArrayList<>();
        for (JsonNode b : root) {
            JsonNode name = b.get("name");
            if (name == null || !name.isString()) {
                throw new GithubApiException("GitHub 응답 형식이 올바르지 않습니다.", 0, null);
            }
            names.add(name.asString());
        }
        return names;
    }

    @Override
    public boolean branchExists(String owner, String repo, String branch) {
        try {
            http.get().uri("/repos/{owner}/{repo}/branches/{branch}", owner, repo, branch).retrieve().toBodilessEntity();
            return true;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return false;
            }
            throw new GithubApiException("GitHub 호출이 실패했습니다(HTTP " + e.getStatusCode().value() + ").", e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            throw new GithubApiException("GitHub에 연결하지 못했습니다(시간 초과 또는 네트워크 오류).", 0, e);
        }
    }

    @Override
    public java.util.Optional<String> latestDeploymentUrl(String owner, String repo) {
        JsonNode deployments = read(() -> http.get().uri("/repos/{owner}/{repo}/deployments?per_page=10", owner, repo)
            .retrieve().body(String.class));
        if (deployments == null || !deployments.isArray()) {
            throw new GithubApiException("GitHub 응답 형식이 올바르지 않습니다.", 0, null);
        }
        // GitHub는 최신 배포부터 준다. 배포마다 가장 최근 상태 하나만 본다 — 실패·진행 중인 배포의 주소는 쓰지 않는다
        for (JsonNode d : deployments) {
            JsonNode id = d.get("id");
            if (id == null || !id.isNumber()) {
                continue;
            }
            long deploymentId = id.asLong();
            JsonNode statuses = read(() -> http.get()
                .uri("/repos/{owner}/{repo}/deployments/{id}/statuses?per_page=1", owner, repo, deploymentId)
                .retrieve().body(String.class));
            if (statuses == null || !statuses.isArray() || statuses.isEmpty()) {
                continue;
            }
            JsonNode latest = statuses.get(0);
            JsonNode state = latest.get("state");
            JsonNode url = latest.get("environment_url");
            if (state != null && "success".equals(state.asString()) && url != null && url.isString() && !url.asString().isBlank()) {
                return java.util.Optional.of(url.asString().trim());
            }
        }
        return java.util.Optional.empty();
    }

    private JsonNode read(java.util.function.Supplier<String> call) {
        String body;
        try {
            body = call.get();
        } catch (RestClientResponseException e) {
            throw new GithubApiException("GitHub 호출이 실패했습니다(HTTP " + e.getStatusCode().value() + ").", e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            throw new GithubApiException("GitHub에 연결하지 못했습니다(시간 초과 또는 네트워크 오류).", 0, e);
        }
        try {
            return json.readTree(body);
        } catch (JacksonException e) {
            throw new GithubApiException("GitHub 응답이 JSON이 아닙니다.", 0, e);
        }
    }

    @Override
    public String commitSha(String owner, String repo, String branch) {
        JsonNode root = read(() -> http.get().uri("/repos/{owner}/{repo}/commits/{branch}", owner, repo, branch).retrieve().body(String.class));
        JsonNode sha = root == null ? null : root.get("sha");
        if (sha == null || !sha.isString() || !sha.asString().matches("[0-9a-f]{40}")) {
            throw new GithubApiException("GitHub 응답 형식이 올바르지 않습니다.", 0, null);
        }
        return sha.asString();
    }

    @Override
    public java.util.List<TreeFile> tree(String owner, String repo, String sha) {
        JsonNode root = read(() -> http.get().uri("/repos/{owner}/{repo}/git/trees/{sha}?recursive=1", owner, repo, sha).retrieve().body(String.class));
        JsonNode entries = root == null ? null : root.get("tree");
        JsonNode truncated = root == null ? null : root.get("truncated");
        if (entries == null || !entries.isArray()) {
            throw new GithubApiException("GitHub 응답 형식이 올바르지 않습니다.", 0, null);
        }
        if (truncated != null && truncated.asBoolean(false)) {
            // 목록이 잘리면 "저장소에 없는 경로" 검사를 믿을 수 없다 — 실패로 끝낸다
            throw new GithubApiException("저장소 파일이 너무 많아 목록을 다 읽지 못했어요.", 0, null);
        }
        java.util.List<TreeFile> files = new java.util.ArrayList<>();
        for (JsonNode e : entries) {
            JsonNode type = e.get("type");
            JsonNode path = e.get("path");
            if (type == null || !type.isString() || path == null || !path.isString()) {
                throw new GithubApiException("GitHub 응답 형식이 올바르지 않습니다.", 0, null);
            }
            if ("blob".equals(type.asString())) {
                JsonNode size = e.get("size");
                files.add(new TreeFile(path.asString(), size != null && size.isNumber() ? size.asLong() : 0L));
            }
        }
        return files;
    }

    @Override
    public String fileContent(String owner, String repo, String sha, String path) {
        try {
            // path의 "/"는 경로 구분자로 그대로 둬야 해서 템플릿 변수가 아니라 경로 문자열로 붙인다(중괄호만 막는다)
            String literal = path.replace("{", "%7B").replace("}", "%7D");
            return http.get().uri(b -> b.path("/repos/{owner}/{repo}/contents/" + literal).queryParam("ref", sha).build(owner, repo))
                .header("Accept", "application/vnd.github.raw+json").retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            throw new GithubApiException("GitHub 호출이 실패했습니다(HTTP " + e.getStatusCode().value() + ").", e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            throw new GithubApiException("GitHub에 연결하지 못했습니다(시간 초과 또는 네트워크 오류).", 0, e);
        }
    }

    @Override
    public RepoInfo repository(String owner, String repo) {
        String body;
        try {
            body = http.get().uri("/repos/{owner}/{repo}", owner, repo).retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            throw new GithubApiException("GitHub 호출이 실패했습니다(HTTP " + e.getStatusCode().value() + ").", e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            throw new GithubApiException("GitHub에 연결하지 못했습니다(시간 초과 또는 네트워크 오류).", 0, e);
        }
        try {
            JsonNode root = json.readTree(body);
            JsonNode name = root == null ? null : root.get("full_name");
            JsonNode branch = root == null ? null : root.get("default_branch");
            JsonNode priv = root == null ? null : root.get("private");
            if (name == null || !name.isString() || branch == null || !branch.isString() || priv == null || !priv.isBoolean()) {
                throw new GithubApiException("GitHub 응답 형식이 올바르지 않습니다.", 0, null);
            }
            return new RepoInfo(name.asString(), branch.asString(), priv.asBoolean());
        } catch (JacksonException e) {
            throw new GithubApiException("GitHub 응답이 JSON이 아닙니다.", 0, e);
        }
    }

    // ---------------------------------------------------------------- 코드 추적(AC34) — 읽기만

    private static final String BLAME_QUERY = """
        query($o:String!,$r:String!,$sha:GitObjectID!,$p:String!){repository(owner:$o,name:$r){object(oid:$sha){... on Commit{
          blame(path:$p){ranges{startingLine endingLine commit{oid messageHeadline committedDate url author{name user{login}}
            associatedPullRequests(first:5){nodes{number title body headRefName state createdAt url author{login}}}}}}}}}}""";

    @Override
    public boolean hasToken() {
        return hasToken;
    }

    @Override
    public java.util.Optional<Origin> blameLine(String owner, String repo, String sha, String path, int line) {
        if (!hasToken) {
            return java.util.Optional.empty();
        }
        java.util.Map<String, Object> body = java.util.Map.of("query", BLAME_QUERY,
            "variables", java.util.Map.of("o", owner, "r", repo, "sha", sha, "p", path));
        JsonNode root = read(() -> http.post().uri("/graphql").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .body(json.writeValueAsString(body)).retrieve().body(String.class));
        if (root == null || root.has("errors")) {
            // GraphQL은 오류도 200으로 준다 — 첫 오류 메시지만 남긴다(토큰·코드는 담기지 않는다)
            String detail = root == null ? "" : text(root.at("/errors/0/message"));
            throw new GithubApiException("GitHub blame 조회가 실패했습니다: " + detail, 0, null);
        }
        JsonNode ranges = root.at("/data/repository/object/blame/ranges");
        if (!ranges.isArray()) {
            return java.util.Optional.empty();
        }
        for (JsonNode r : ranges) {
            if (r.path("startingLine").asInt() <= line && line <= r.path("endingLine").asInt()) {
                JsonNode c = r.path("commit");
                String login = text(c.at("/author/user/login"));
                CommitInfo commit = new CommitInfo(text(c.get("oid")), text(c.get("messageHeadline")),
                    login != null ? login : text(c.at("/author/name")), instant(c.get("committedDate")), text(c.get("url")));
                java.util.List<PullInfo> pulls = new java.util.ArrayList<>();
                for (JsonNode p : c.at("/associatedPullRequests/nodes")) {
                    pulls.add(new PullInfo(p.path("number").asInt(), text(p.get("title")), text(p.get("body")), text(p.get("headRefName")),
                        text(p.get("state")) == null ? null : text(p.get("state")).toLowerCase(java.util.Locale.ROOT), text(p.at("/author/login")),
                        instant(p.get("createdAt")), text(p.get("url"))));
                }
                return commit.sha() == null ? java.util.Optional.empty() : java.util.Optional.of(new Origin(commit, pulls));
            }
        }
        return java.util.Optional.empty();
    }

    @Override
    public java.util.Optional<CommitInfo> lastCommitForPath(String owner, String repo, String sha, String path) {
        JsonNode root = read(() -> http.get().uri(b -> b.path("/repos/{owner}/{repo}/commits").queryParam("sha", sha).queryParam("path", path)
            .queryParam("per_page", 1).build(owner, repo)).retrieve().body(String.class));
        return root != null && root.isArray() && !root.isEmpty() ? java.util.Optional.of(commitOf(root.get(0))) : java.util.Optional.empty();
    }

    @Override
    public java.util.List<PullInfo> pullsForCommit(String owner, String repo, String sha) {
        JsonNode root = read(() -> http.get().uri("/repos/{owner}/{repo}/commits/{sha}/pulls?per_page=5", owner, repo, sha)
            .retrieve().body(String.class));
        return pullsOf(root);
    }

    @Override
    public java.util.List<CommitInfo> commitsSince(String owner, String repo, String branch, java.time.Instant since) {
        JsonNode root = read(() -> http.get().uri(b -> b.path("/repos/{owner}/{repo}/commits").queryParam("sha", branch)
            .queryParam("since", since.toString()).queryParam("per_page", 100).build(owner, repo)).retrieve().body(String.class));
        java.util.List<CommitInfo> out = new java.util.ArrayList<>();
        if (root != null && root.isArray()) {
            root.forEach(c -> out.add(commitOf(c)));
        }
        return out;
    }

    @Override
    public java.util.List<PullInfo> recentPulls(String owner, String repo) {
        JsonNode root = read(() -> http.get().uri("/repos/{owner}/{repo}/pulls?state=all&sort=created&direction=desc&per_page=100", owner, repo)
            .retrieve().body(String.class));
        return pullsOf(root);
    }

    @Override
    public java.util.Optional<CommitInfo> commit(String owner, String repo, String sha) {
        try {
            JsonNode root = read(() -> http.get().uri("/repos/{owner}/{repo}/commits/{sha}", owner, repo, sha).retrieve().body(String.class));
            return root == null || !root.isObject() ? java.util.Optional.empty() : java.util.Optional.of(commitOf(root));
        } catch (GithubApiException e) {
            if (e.status() == 404 || e.status() == 422) {
                return java.util.Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public java.util.Optional<PullInfo> pull(String owner, String repo, int number) {
        try {
            JsonNode root = read(() -> http.get().uri("/repos/{owner}/{repo}/pulls/{n}", owner, repo, number).retrieve().body(String.class));
            return root == null || !root.isObject() ? java.util.Optional.empty() : java.util.Optional.of(pullOf(root));
        } catch (GithubApiException e) {
            if (e.status() == 404) {
                return java.util.Optional.empty();
            }
            throw e;
        }
    }

    private static CommitInfo commitOf(JsonNode c) {
        String message = text(c.at("/commit/message"));
        String login = text(c.at("/author/login"));
        return new CommitInfo(text(c.get("sha")), message == null ? null : message.lines().findFirst().orElse(""),
            login != null ? login : text(c.at("/commit/author/name")), instant(c.at("/commit/committer/date")), text(c.get("html_url")));
    }

    private static java.util.List<PullInfo> pullsOf(JsonNode root) {
        java.util.List<PullInfo> out = new java.util.ArrayList<>();
        if (root != null && root.isArray()) {
            root.forEach(p -> out.add(pullOf(p)));
        }
        return out;
    }

    private static PullInfo pullOf(JsonNode p) {
        String state = text(p.get("state"));
        if (p.hasNonNull("merged_at")) {
            state = "merged";
        }
        return new PullInfo(p.path("number").asInt(), text(p.get("title")), text(p.get("body")), text(p.at("/head/ref")), state,
            text(p.at("/user/login")), instant(p.get("created_at")), text(p.get("html_url")));
    }

    private static String text(JsonNode n) {
        return n != null && n.isString() ? n.asString() : null;
    }

    private static java.time.Instant instant(JsonNode n) {
        try {
            return n != null && n.isString() ? java.time.Instant.parse(n.asString()) : null;
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }
}
