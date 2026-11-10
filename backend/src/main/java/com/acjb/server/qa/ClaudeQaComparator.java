package com.acjb.server.qa;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.acjb.server.domain.StyleProperty;
import com.acjb.server.figma.FigmaApiException;
import com.acjb.server.figma.FigmaClient;
import com.acjb.server.github.GithubApiException;
import com.acjb.server.github.GithubClient;
import com.acjb.server.qa.FigmaFacts.Element;
import com.acjb.server.qa.FigmaFacts.Fact;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicException;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 첫 QA·Re-QA 실제 비교기(SPEC 4절·AC23·AC28, 스파이크 docs/spikes/2026-11-10_first-qa-comparator.md).
 *  1) Figma 값: 매핑 노드 하위 요소의 값을 노드 JSON에서 읽는다(FigmaFacts) — 고른 검증 범위의 property만
 *  2) LLM: 짝 파일(+ 직접 import하는 저장소 파일)에서 요소를 그리는 줄과 그 줄의 토큰만 고른다. Figma 값은 보여 주지 않는다 —
 *     값에 맞는 줄을 골라 차이를 숨기지 않게
 *  3) 서버: 토큰이 그 줄에 실제로 있는지 확인하고(없으면 버림, N17) Tailwind 테마로 값을 바꿔 비교한다(StyleRules)
 * API 키와 코드 원문은 로그에 남기지 않는다
 */
@Component
@ConditionalOnProperty(name = "app.qa.fake-comparator", havingValue = "false", matchIfMissing = true)
public class ClaudeQaComparator implements QaComparator {

    private static final Logger log = LoggerFactory.getLogger(ClaudeQaComparator.class);

    static final String SYSTEM = """
        You locate, in React + Tailwind source code, the exact code that renders each element of a Figma design node.
        For each listed element and each listed property, find the JSX element in the provided code that renders that design element, and return:
        - file: the file path exactly as given
        - line: the 1-based line number where the value is written
        - token: the exact substring on that line that sets the property — copy it verbatim from that line
          - color (text color), background, border (border color), font_weight, font_size, radius, padding, gap: the Tailwind class or classes, \
        space-separated, that set it (e.g. "px-6 py-4"). All classes must be on that single line
          - text: the static string literal as written (without quotes)
        Omit an element/property when: no code renders it; the value comes from a variable, prop, function call or data (dynamic) rather than \
        a literal on the line; it is set only through a responsive/state variant (hover:, md:); or you are not sure which JSX element renders it.
        Never compute or convert values and never guess. Do not pick a line because its value looks like the design — pick the line of the \
        element that actually renders that design element.""";

    private static final String KEY_MISSING = "QA 비교용 LLM API 키(ANTHROPIC_API_KEY)가 설정되지 않았어요.";
    private static final Pattern IMPORT = Pattern.compile("(?:from|import)\\s+['\"](\\.{1,2}/[^'\"]+)['\"]");
    private static final List<String> EXTENSIONS = List.of("", ".tsx", ".ts", ".jsx", ".js", "/index.tsx", "/index.ts", "/index.jsx", "/index.js");
    private static final long MAX_FILE_BYTES = 100_000;
    private static final int MAX_BUNDLE_CHARS = 150_000;
    private static final int MAX_CSS_FILES = 30;
    private static final int FRAME_BATCH = 20;
    private static final long MAX_TOKENS = 32_000L;

    private final GithubClient github;
    private final FigmaClient figma;
    private final JsonMapper json;
    private final AnthropicClient client;
    private final String model;
    private final int concurrency;

    public ClaudeQaComparator(GithubClient github, FigmaClient figma, JsonMapper json,
                              @Value("${app.qa.model:claude-sonnet-5-5}") String model,
                              @Value("${app.qa.api-key:}") String apiKey,
                              @Value("${app.qa.timeout:10m}") Duration timeout,
                              @Value("${app.qa.concurrency:4}") int concurrency) {
        this.github = github;
        this.figma = figma;
        this.json = json;
        this.model = model;
        this.concurrency = Math.max(1, concurrency);
        this.client = apiKey == null || apiKey.isBlank() ? null
            : AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(timeout).build();
    }

    @Override
    public boolean demo() {
        return false;
    }

    // ------------------------------------------------------------------ 첫 QA

    @Override
    public FirstQaResult compare(FirstQaInput input) {
        ready(input.figmaFileKey());
        Set<Fact> allowed = factsOf(input.properties());
        Repo repo = Repo.open(github, input.repo(), input.commitSha());
        TailwindValues theme = new TailwindValues(repo.themeCss());
        Map<String, JsonNode> frames = frames(input.figmaFileKey(), input.targets().stream().map(Target::frameId).toList());

        List<Job> jobs = new ArrayList<>();
        for (Target t : input.targets()) {
            JsonNode frame = frames.get(t.frameId());
            Optional<JsonNode> node = frame == null ? Optional.empty() : FigmaFacts.find(frame, t.nodeId());
            if (node.isEmpty() || !repo.has(t.codePath())) {
                continue; // 시안이나 파일이 사라졌으면 비교할 근거가 없다
            }
            List<Element> elements = FigmaFacts.collect(node.get(), allowed);
            if (!elements.isEmpty()) {
                jobs.add(new Job(t, elements, repo.bundle(t.codePath()), theme));
            }
        }
        List<Finding> findings = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int dropped = 0;
        for (JobResult r : run(jobs)) {
            dropped += r.dropped();
            for (Finding f : r.findings()) {
                // 같은 코드 줄·property·값의 차이는 하나로 — 같은 컴포넌트를 여러 번 그린 시안이 이슈를 불리지 않게
                if (seen.add(f.codePath() + "|" + f.codeLine() + "|" + f.property() + "|" + f.expectedValue() + "|" + f.actualValue())) {
                    findings.add(f);
                }
            }
        }
        log.info("첫 QA 비교: 대상 {}개, 차이 {}개, 버림 {}개", jobs.size(), findings.size(), dropped);
        return new FirstQaResult(input.commitSha(), findings, dropped);
    }

    private record Job(Target target, List<Element> elements, Map<String, String> code, TailwindValues theme) {
    }

    record JobResult(List<Finding> findings, int dropped) {
    }

    private List<JobResult> run(List<Job> jobs) {
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        try {
            List<Future<JobResult>> futures = new ArrayList<>();
            for (Job j : jobs) {
                futures.add(pool.submit(() -> judgeWith(j.target(), j.elements(), j.code(), locate(j.target(), j.elements(), j.code()), j.theme())));
            }
            List<JobResult> out = new ArrayList<>();
            for (Future<JobResult> f : futures) {
                out.add(await(f));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    static JobResult judgeWith(Target t, List<Element> elements, Map<String, String> code, List<Binding> bindings, TailwindValues theme) {
        Map<String, Element> byId = new LinkedHashMap<>();
        elements.forEach(e -> byId.put(e.id(), e));
        Set<String> used = new HashSet<>();
        List<Finding> findings = new ArrayList<>();
        int dropped = 0;
        for (Binding b : bindings) {
            Element e = byId.get(b.elementId());
            Optional<Fact> fact = Fact.of(b.property());
            if (e == null || fact.isEmpty() || !e.values().containsKey(fact.get()) || !used.add(b.elementId() + "|" + b.property())) {
                dropped++;
                continue;
            }
            Optional<String> line = lineOf(code, b);
            if (line.isEmpty()) {
                dropped++; // 그 줄에 토큰이 없다 — 지어낸 근거(N17)
                continue;
            }
            Object value = theme.resolve(fact.get(), b.token());
            Optional<Boolean> same = StyleRules.same(fact.get(), e, value, line.get());
            if (same.isEmpty() || same.get()) {
                continue; // 읽을 수 없는 값(동적 값·투명도·변형)은 비교하지 않는다
            }
            boolean fixed = fact.get() == Fact.PADDING && StyleRules.fixedHeight(line.get());
            findings.add(new Finding(t.nodeId(), e.id(), e.name(), fact.get().property,
                StyleRules.format(fact.get(), e.values().get(fact.get()), fixed), StyleRules.format(fact.get(), value, fixed), b.file(), b.line()));
        }
        return new JobResult(findings, dropped);
    }

    // ------------------------------------------------------------------ Re-QA

    @Override
    public String latestCommit(String repo, String branch) {
        String[] ownerRepo = split(repo);
        try {
            return github.commitSha(ownerRepo[0], ownerRepo[1], branch);
        } catch (GithubApiException e) {
            throw new QaFailedException("GitHub 브랜치의 최신 커밋을 읽지 못했어요. 연결 상태를 확인해 주세요.");
        }
    }

    @Override
    public ReQaResult recheck(ReQaInput input) {
        ready(input.figmaFileKey());
        String[] ownerRepo = split(input.repo());
        String sha;
        try {
            sha = github.commitSha(ownerRepo[0], ownerRepo[1], input.branch());
        } catch (GithubApiException e) {
            throw new QaFailedException("GitHub 브랜치의 최신 커밋을 읽지 못했어요. 연결 상태를 확인해 주세요.");
        }
        Repo repo = Repo.open(github, input.repo(), sha);
        TailwindValues theme = new TailwindValues(repo.themeCss());
        Map<String, JsonNode> frames = frames(input.figmaFileKey(), input.items().stream().map(ReQaItem::frameId).toList());

        // 파일마다 한 번 묻는다. 요소를 다시 못 찾거나 파일이 없어졌으면 판정하지 않는다(N18)
        Map<String, List<ReQaItem>> byFile = new LinkedHashMap<>();
        for (ReQaItem item : input.items()) {
            if (item.codePath() != null && repo.has(item.codePath())) {
                byFile.computeIfAbsent(item.codePath(), k -> new ArrayList<>()).add(item);
            }
        }
        Map<Long, String> observed = new LinkedHashMap<>();
        for (Map.Entry<String, List<ReQaItem>> group : byFile.entrySet()) {
            Map<String, Element> elements = new LinkedHashMap<>();
            Map<Long, Fact> facts = new LinkedHashMap<>();
            for (ReQaItem item : group.getValue()) {
                Optional<Fact> fact = StyleRules.factOf(item.property(), item.expectedValue());
                JsonNode frame = frames.get(item.frameId());
                Optional<JsonNode> node = frame == null || fact.isEmpty() ? Optional.empty() : FigmaFacts.find(frame, item.nodeId());
                Optional<Element> e = node.flatMap(n -> FigmaFacts.element(n, FigmaFacts.name(n), Set.of(fact.get())));
                if (e.isEmpty()) {
                    continue;
                }
                facts.put(item.issueId(), fact.get());
                elements.merge(e.get().id(), e.get(), ClaudeQaComparator::mergeValues);
            }
            if (elements.isEmpty()) {
                continue;
            }
            Map<String, String> code = repo.bundle(group.getKey());
            Target t = new Target(null, "", null, group.getValue().get(0).nodeId(), group.getKey(), null);
            List<Binding> bindings = locate(t, List.copyOf(elements.values()), code);
            for (ReQaItem item : group.getValue()) {
                Fact fact = facts.get(item.issueId());
                Element e = fact == null ? null : elements.get(item.nodeId());
                if (e == null) {
                    continue;
                }
                reread(e, fact, bindings, code, theme).ifPresent(v -> observed.put(item.issueId(), v));
            }
        }
        return new ReQaResult(sha, observed);
    }

    /** 다시 읽은 값 — 규칙상 같으면 이슈의 Figma 값 표기 그대로(ReQaJob이 문자열로 판정한다), 다르면 코드 값 표기 */
    private static Optional<String> reread(Element e, Fact fact, List<Binding> bindings, Map<String, String> code, TailwindValues theme) {
        for (Binding b : bindings) {
            if (!b.elementId().equals(e.id()) || !fact.key.equals(b.property())) {
                continue;
            }
            Optional<String> line = lineOf(code, b);
            if (line.isEmpty()) {
                return Optional.empty();
            }
            Object value = theme.resolve(fact, b.token());
            Optional<Boolean> same = StyleRules.same(fact, e, value, line.get());
            if (same.isEmpty()) {
                return Optional.empty();
            }
            boolean fixed = fact == Fact.PADDING && StyleRules.fixedHeight(line.get());
            return Optional.of(StyleRules.format(fact, same.get() ? e.values().get(fact) : value, fixed));
        }
        return Optional.empty();
    }

    private static Element mergeValues(Element a, Element b) {
        Map<Fact, Object> v = new LinkedHashMap<>(a.values());
        v.putAll(b.values());
        return new Element(a.id(), a.name(), a.type(), a.path(), Map.copyOf(v), a.width(), a.height(), a.lead());
    }

    // ------------------------------------------------------------------ 공통

    private void ready(String fileKey) {
        if (client == null) {
            throw new QaFailedException(KEY_MISSING);
        }
        if (!figma.isConfigured()) {
            throw new QaFailedException("서버에 Figma 토큰이 설정되지 않아 시안 값을 읽을 수 없어요.");
        }
        if (fileKey == null || fileKey.isBlank()) {
            throw new QaFailedException("연결된 Figma 파일이 없어요. 연결 관리에서 Figma를 다시 연결해 주세요.");
        }
    }

    private static Set<Fact> factsOf(List<StyleProperty> properties) {
        Set<Fact> facts = EnumSet.noneOf(Fact.class);
        for (Fact f : Fact.values()) {
            if (properties.contains(f.property)) {
                facts.add(f);
            }
        }
        return facts;
    }

    private Map<String, JsonNode> frames(String fileKey, List<String> frameIds) {
        List<String> ids = List.copyOf(new LinkedHashSet<>(frameIds));
        Map<String, JsonNode> out = new LinkedHashMap<>();
        try {
            for (int i = 0; i < ids.size(); i += FRAME_BATCH) {
                out.putAll(figma.nodeDocuments(fileKey, ids.subList(i, Math.min(ids.size(), i + FRAME_BATCH))));
            }
        } catch (FigmaApiException e) {
            throw new QaFailedException(e.retryAfterSeconds() > 0
                ? "Figma 요청 한도를 넘었어요. " + e.retryAfterSeconds() + "초 뒤 다시 시도해 주세요."
                : "Figma 시안을 읽지 못했어요. 연결 상태를 확인한 뒤 다시 시도해 주세요.");
        }
        return out;
    }

    record Binding(String elementId, String property, String file, int line, String token) {
    }

    private static Optional<String> lineOf(Map<String, String> code, Binding b) {
        String src = code.get(b.file());
        if (src == null || b.token() == null || b.token().isBlank()) {
            return Optional.empty();
        }
        String[] lines = src.split("\n", -1);
        if (b.line() < 1 || b.line() > lines.length || !lines[b.line() - 1].contains(b.token())) {
            return Optional.empty();
        }
        return Optional.of(lines[b.line() - 1]);
    }

    /** LLM — 요소마다 줄·토큰. 응답은 스키마로 받고 여기서 다시 형태를 검사한다(AGENTS.md 5절) — 형태가 틀린 항목은 버린다 */
    private List<Binding> locate(Target t, List<Element> elements, Map<String, String> code) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Element e : elements) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("element_id", e.id());
            m.put("name", e.name());
            m.put("type", e.type());
            m.put("path", e.path());
            m.put("properties_to_locate", e.values().keySet().stream().map(f -> f.key).toList());
            if (e.values().get(Fact.TEXT) instanceof String text) {
                m.put("text", text); // 요소를 찾는 단서 — 스타일 값은 보여 주지 않는다
            }
            list.add(m);
        }
        String user = "Figma node '" + t.nodeName() + "'" + (t.frameName() == null || t.frameName().isBlank() ? "" : " in frame '" + t.frameName() + "'")
            + " is implemented " + (t.codeSymbol() == null ? "in " : "by `" + t.codeSymbol() + "` in ") + t.codePath() + ".\n"
            + "Elements (top to bottom, depth-first):\n" + json.writeValueAsString(list);
        JsonNode root = call(numbered(code), user);
        List<Binding> out = new ArrayList<>();
        JsonNode arr = root.get("bindings");
        if (arr == null || !arr.isArray()) {
            throw new QaFailedException("AI 비교 응답 형식이 올바르지 않아요.");
        }
        for (JsonNode b : arr) {
            JsonNode line = b.get("line");
            String id = str(b, "element_id");
            String prop = str(b, "property");
            String file = str(b, "file");
            String token = str(b, "token");
            if (id != null && prop != null && file != null && token != null && line != null && line.isIntegralNumber()) {
                out.add(new Binding(id, prop, file, line.asInt(), token));
            }
        }
        return out;
    }

    private static String str(JsonNode n, String k) {
        JsonNode v = n.get(k);
        return v != null && v.isString() ? v.asString() : null;
    }

    private static String numbered(Map<String, String> code) {
        StringBuilder sb = new StringBuilder();
        code.forEach((path, src) -> {
            sb.append("<file path=\"").append(path).append("\">\n");
            String[] lines = src.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                sb.append(i + 1).append(": ").append(lines[i]).append('\n');
            }
            sb.append("</file>\n");
        });
        return sb.toString();
    }

    private JsonNode call(String code, String user) {
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("type", "object");
        binding.put("additionalProperties", false);
        binding.put("required", List.of("element_id", "property", "file", "line", "token"));
        binding.put("properties", Map.of(
            "element_id", Map.of("type", "string"),
            "property", Map.of("type", "string", "enum", java.util.Arrays.stream(Fact.values()).map(f -> f.key).toList()),
            "file", Map.of("type", "string"),
            "line", Map.of("type", "integer"),
            "token", Map.of("type", "string")));
        Map<String, JsonValue> schema = new LinkedHashMap<>();
        schema.put("type", JsonValue.from("object"));
        schema.put("additionalProperties", JsonValue.from(false));
        schema.put("required", JsonValue.from(List.of("bindings")));
        schema.put("properties", JsonValue.from(Map.of("bindings", Map.of("type", "array", "items", binding))));
        MessageCreateParams params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(MAX_TOKENS)
            .systemOfTextBlockParams(List.of(TextBlockParam.builder().text(SYSTEM).build(), TextBlockParam.builder().text(code).build()))
            .outputConfig(OutputConfig.builder()
                .format(JsonOutputFormat.builder().schema(JsonOutputFormat.Schema.builder().putAllAdditionalProperties(schema).build()).build())
                .effort(OutputConfig.Effort.HIGH)
                .build())
            .addUserMessage(user)
            .build();
        Message response;
        try (StreamResponse<RawMessageStreamEvent> stream = client.messages().createStreaming(params)) {
            MessageAccumulator acc = MessageAccumulator.create();
            stream.stream().forEach(acc::accumulate);
            response = acc.message();
        } catch (AnthropicException e) {
            log.warn("첫 QA LLM 호출 실패: {}", e.getClass().getSimpleName());
            throw new QaFailedException("AI 비교 호출에 실패했어요. 잠시 뒤 다시 시도해 주세요.");
        }
        StopReason stop = response.stopReason().orElse(null);
        if (!StopReason.END_TURN.equals(stop)) {
            log.warn("첫 QA LLM이 정상 종료하지 않음: stop_reason={}", stop);
            throw new QaFailedException("AI 비교가 결과를 끝까지 내지 못했어요.");
        }
        log.info("첫 QA LLM 사용량: input={} output={}", response.usage().inputTokens(), response.usage().outputTokens());
        String text = response.content().stream().flatMap(b -> b.text().stream()).map(t -> t.text()).findFirst()
            .orElseThrow(() -> new QaFailedException("AI 비교 응답이 비어 있어요."));
        try {
            JsonNode root = json.readTree(text);
            if (root == null || !root.isObject()) {
                throw new QaFailedException("AI 비교 응답 형식이 올바르지 않아요.");
            }
            return root;
        } catch (JacksonException e) {
            throw new QaFailedException("AI 비교 응답 형식이 올바르지 않아요.");
        }
    }

    private static <T> T await(Future<T> f) {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new QaFailedException("첫 QA가 중단됐어요. 다시 시도해 주세요.");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof QaFailedException q) {
                throw q;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    private static String[] split(String repo) {
        if (repo == null || !repo.contains("/")) {
            throw new QaFailedException("연결된 GitHub 저장소가 없어요. 연결 관리에서 GitHub를 다시 연결해 주세요.");
        }
        return repo.split("/", 2);
    }

    /** 한 커밋의 저장소 — 파일 목록과 필요한 파일만 읽어 둔다 */
    private static final class Repo {
        private final GithubClient github;
        private final String owner;
        private final String name;
        private final String sha;
        private final Map<String, Long> sizes = new LinkedHashMap<>();
        private final Map<String, String> cache = new ConcurrentHashMap<>();

        private Repo(GithubClient github, String owner, String name, String sha) {
            this.github = github;
            this.owner = owner;
            this.name = name;
            this.sha = sha;
        }

        static Repo open(GithubClient github, String repo, String sha) {
            String[] or = split(repo);
            Repo r = new Repo(github, or[0], or[1], sha);
            try {
                github.tree(or[0], or[1], sha).forEach(f -> r.sizes.put(f.path(), f.size()));
            } catch (GithubApiException e) {
                throw new QaFailedException("GitHub 코드를 읽지 못했어요. 연결 상태를 확인한 뒤 다시 시도해 주세요.");
            }
            return r;
        }

        boolean has(String path) {
            return path != null && sizes.containsKey(path);
        }

        String read(String path) {
            return cache.computeIfAbsent(path, p -> {
                try {
                    return github.fileContent(owner, name, sha, p);
                } catch (GithubApiException e) {
                    throw new QaFailedException("GitHub 코드를 읽지 못했어요. 연결 상태를 확인한 뒤 다시 시도해 주세요.");
                }
            });
        }

        /** Tailwind 4 `@theme`이 있는 CSS(node_modules·빌드 결과 제외, 크기 순 앞에서 30개까지 살펴봄) */
        List<String> themeCss() {
            List<String> out = new ArrayList<>();
            sizes.entrySet().stream()
                .filter(e -> e.getKey().endsWith(".css") && e.getValue() <= MAX_FILE_BYTES
                    && !e.getKey().matches("(?i).*(^|/)(node_modules|dist|build|out|coverage|public)/.*"))
                .limit(MAX_CSS_FILES)
                .forEach(e -> {
                    String css = read(e.getKey());
                    if (css.contains("@theme")) {
                        out.add(css);
                    }
                });
            return out;
        }

        /** 짝 파일과 그 파일이 상대 경로로 직접 import하는 저장소 파일(1단계) — 큰 파일·총량을 넘는 파일은 뺀다 */
        Map<String, String> bundle(String main) {
            Map<String, String> out = new LinkedHashMap<>();
            String src = read(main);
            out.put(main, src);
            int total = src.length();
            String base = main.contains("/") ? main.substring(0, main.lastIndexOf('/')) : "";
            Matcher m = IMPORT.matcher(src);
            while (m.find()) {
                String resolved = resolve(base, m.group(1));
                if (resolved == null || out.containsKey(resolved) || sizes.getOrDefault(resolved, Long.MAX_VALUE) > MAX_FILE_BYTES) {
                    continue;
                }
                String text = read(resolved);
                if (total + text.length() > MAX_BUNDLE_CHARS) {
                    continue;
                }
                out.put(resolved, text);
                total += text.length();
            }
            return out;
        }

        private String resolve(String base, String rel) {
            List<String> parts = new ArrayList<>(base.isEmpty() ? List.of() : List.of(base.split("/")));
            for (String p : rel.split("/")) {
                if (p.equals("..")) {
                    if (parts.isEmpty()) {
                        return null;
                    }
                    parts.remove(parts.size() - 1);
                } else if (!p.equals(".") && !p.isEmpty()) {
                    parts.add(p);
                }
            }
            String path = String.join("/", parts);
            for (String ext : EXTENSIONS) {
                if (sizes.containsKey(path + ext) && !(path + ext).endsWith(".css")) {
                    return path + ext;
                }
            }
            return null;
        }
    }
}
