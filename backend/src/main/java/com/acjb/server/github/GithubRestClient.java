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

    public GithubRestClient(JsonMapper json,
                            @Value("${app.github.base-url:https://api.github.com}") String baseUrl,
                            @Value("${app.github.token:}") String token,
                            @Value("${app.github.timeout:15s}") Duration timeout) {
        this.json = json;
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
}
