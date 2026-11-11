package com.acjb.server.analysis;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import com.acjb.server.domain.DesignCodeMapping;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.ProjectRun;
import com.acjb.server.figma.FigmaApiException;
import com.acjb.server.figma.FigmaClient;
import com.acjb.server.figma.FigmaNode;
import com.acjb.server.github.GithubApiException;
import com.acjb.server.github.GithubClient;
import com.acjb.server.repository.DesignCodeMappingRepository;
import com.acjb.server.repository.ProjectRunRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 비교 분석(AC22) 실행. 요청 트랜잭션이 커밋된 뒤 별도 스레드에서 돈다.
 * 순서: running 표시 → GitHub 커밋·코드 읽기 → Figma 페이지·섹션 안 프레임 읽기(섹션 안까지) → LLM ① 프레임 → 파일
 * → 짝이 있는 프레임마다 LLM ② 노드 → 파일·컴포넌트 → 검증 → 한 트랜잭션으로 저장 + succeeded.
 * 입력에 없는 프레임·노드, 넘기지 않은 파일 경로를 가리킨 짝은 버리고 센다. 실패하면 매핑을 하나도 남기지 않고 failed다.
 * 이슈·StyleMismatch는 만들지 않는다(N16)
 */
@Component
public class AnalysisJob {

    private static final Logger log = LoggerFactory.getLogger(AnalysisJob.class);
    /**
     * 한 번에 받는 프레임 원본 수. 프레임 하나가 수백 KB라 나눠 받되, Figma 요청 한도(분당)에 걸리지 않게 호출 수를 줄인다
     * — 10개씩이면 프레임 460개에 약 60번을 불러 429를 받았다(2026-11-07 실측)
     */
    private static final int FRAME_BATCH = 40;
    /** Figma 429 — Retry-After만큼(없으면 20초·40초·60초) 기다렸다가 같은 호출을 다시 한다 */
    private static final int FIGMA_ATTEMPTS = 4;
    private static final long FIGMA_WAIT_CAP_SECONDS = 60;
    static final int MAX_FRAMES = 1000;

    private final FigmaClient figma;
    private final GithubClient github;
    private final DesignCodeMapper mapper;
    private final ProjectRunRepository runs;
    private final DesignCodeMappingRepository mappings;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final JsonMapper json;
    private final int nodeConcurrency;
    private final int maxCodeChars;
    /** 테스트에서 실제로 기다리지 않게 바꿔 끼운다(setSleeper) */
    private Sleeper sleeper = seconds -> {
        try {
            Thread.sleep(seconds * 1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MappingFailedException("비교 분석이 중단됐어요.", e);
        }
    };

    public interface Sleeper {
        void sleep(long seconds);
    }

    public void setSleeper(Sleeper sleeper) {
        this.sleeper = sleeper;
    }

    public AnalysisJob(FigmaClient figma, GithubClient github, DesignCodeMapper mapper, ProjectRunRepository runs,
                       DesignCodeMappingRepository mappings, PlatformTransactionManager txManager, Clock clock, JsonMapper json,
                       @Value("${app.analysis.node-concurrency:4}") int nodeConcurrency,
                       @Value("${app.analysis.max-code-chars:1000000}") int maxCodeChars) {
        this.figma = figma;
        this.github = github;
        this.mapper = mapper;
        this.runs = runs;
        this.mappings = mappings;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        this.json = json;
        this.nodeConcurrency = Math.max(1, nodeConcurrency);
        this.maxCodeChars = maxCodeChars;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequested(AnalysisRequested event) {
        run(event.runId());
    }

    /** 테스트에서도 직접 부른다. 결과는 run 행의 status·created_count·dropped_count로 남는다 */
    /** 프로젝트에 소유자 Figma 토큰이 있으면 작업 전체를 그 토큰으로 부른다(2026-11-11) */
    @org.springframework.beans.factory.annotation.Autowired
    private com.acjb.server.figma.ProjectFigmaToken figmaTokens;

    /** 실패하면 실행한 사람에게 알린다(2026-11-11) */
    @org.springframework.beans.factory.annotation.Autowired
    private com.acjb.server.service.TeamNotifier notifier;

    public void run(Long runId) {
        figmaTokens.withRun(runId, () -> execute(runId));
    }

    private void execute(Long runId) {
        Inputs in = tx.execute(s -> {
            ProjectRun run = runs.findById(runId).orElse(null);
            if (run == null) {
                return null;
            }
            run.start();
            Project p = run.getProject();
            return new Inputs(p.getId(), p.getFigmaFileKey(), p.getFigmaPageId(), p.getGithubRepo(),
                p.getGithubBranch() == null ? p.getGithubDefaultBranch() : p.getGithubBranch());
        });
        if (in == null) {
            return;
        }
        try {
            Result result = analyze(in);
            tx.executeWithoutResult(s -> {
                ProjectRun run = runs.findById(runId).orElseThrow();
                Project project = run.getProject();
                java.time.Instant now = clock.instant();
                for (Saved m : result.mappings()) {
                    mappings.save(m.nodeId() == null
                        ? DesignCodeMapping.frame(project, run, m.frameId(), m.frameName(), m.path(), now)
                        : DesignCodeMapping.node(project, run, m.frameId(), m.frameName(), m.nodeId(), m.nodeName(), m.path(), m.symbol(), now));
                }
                run.setUnmappedFrames(json.writeValueAsString(result.unmapped()));
                run.succeed(result.commitSha(), result.mappings().size(), result.dropped(), now);
            });
        } catch (MappingFailedException e) {
            fail(runId, e.getMessage());
        } catch (FigmaApiException e) {
            log.warn("비교 분석 Figma 읽기 실패 run_id={}: {}", runId, e.getMessage());
            fail(runId, com.acjb.server.figma.FigmaErrors.message(e));
        } catch (GithubApiException e) {
            log.warn("비교 분석 GitHub 읽기 실패 run_id={}: {}", runId, e.getMessage());
            fail(runId, "GitHub 저장소를 읽지 못했어요. 잠시 뒤 다시 시도해 주세요.");
        } catch (RuntimeException e) {
            log.error("비교 분석 중 예상치 못한 오류 run_id={}", runId, e);
            fail(runId, "비교 분석 중 오류가 발생했어요. 다시 시도해 주세요.");
        }
    }

    private void fail(Long runId, String message) {
        tx.executeWithoutResult(s -> runs.findById(runId).ifPresent(r -> {
            r.fail(message, clock.instant());
            // 실행한 사람에게 알린다(2026-11-11)
            notifier.notifyRunFailed(r);
        }));
    }

    private Result analyze(Inputs in) {
        if (in.fileKey() == null || in.repo() == null) {
            throw new MappingFailedException("Figma·GitHub 연결이 없어요.");
        }
        String[] ownerRepo = in.repo().split("/", 2);
        String sha = github.commitSha(ownerRepo[0], ownerRepo[1], in.branch());
        DesignCodeMapper.CodeBundle code = readCode(ownerRepo[0], ownerRepo[1], sha, in.repo());

        // 페이지·섹션 안을 한 단계씩 내려가 프레임을 모은다(섹션 안 섹션 포함) — 페이지 원본을 한 번에 받으면 수십~수백 MB다
        Map<String, String> frameSection = new LinkedHashMap<>();
        Deque<String[]> containers = new ArrayDeque<>();
        containers.add(new String[] {in.scopeId(), null});
        while (!containers.isEmpty()) {
            String[] c = containers.poll();
            List<FigmaNode> kids = figmaCall(() -> figma.children(in.fileKey(), c[0]))
                .orElseThrow(() -> new MappingFailedException("연결한 Figma 페이지·섹션을 찾을 수 없어요. 연결을 다시 확인해 주세요."));
            for (FigmaNode kid : kids) {
                if ("SECTION".equals(kid.type())) {
                    containers.add(new String[] {kid.id(), kid.name()});
                } else if (FigmaFrames.FRAME_TYPES.contains(kid.type())) {
                    frameSection.put(kid.id(), c[1]);
                }
            }
        }
        if (frameSection.isEmpty()) {
            throw new MappingFailedException("연결한 Figma 페이지·섹션에 화면(프레임)이 없어요.");
        }
        if (frameSection.size() > MAX_FRAMES) {
            throw new MappingFailedException("화면(프레임)이 " + MAX_FRAMES + "개를 넘어요. 최종 시안만 담은 섹션을 연결해 주세요.");
        }
        List<DesignCodeMapper.FrameSummary> summaries = new ArrayList<>();
        Map<String, String> frameNames = new HashMap<>();
        List<String> ids = new ArrayList<>(frameSection.keySet());
        for (int i = 0; i < ids.size(); i += FRAME_BATCH) {
            List<String> batch = ids.subList(i, Math.min(ids.size(), i + FRAME_BATCH));
            Map<String, JsonNode> docs = figmaCall(() -> figma.nodeDocuments(in.fileKey(), batch));
            for (Map.Entry<String, JsonNode> e : docs.entrySet()) {
                DesignCodeMapper.FrameSummary s = FigmaFrames.summary(e.getValue(), frameSection.get(e.getKey()));
                summaries.add(s);
                frameNames.put(e.getKey(), s.name());
            }
        }

        // ① 프레임 → 파일. 입력에 없는 프레임, 넘기지 않은 경로, 같은 프레임 두 번째 답은 버린다
        int dropped = 0;
        Map<String, String> framePath = new LinkedHashMap<>();
        for (DesignCodeMapper.FrameMatch m : mapper.mapFrames(summaries, code)) {
            if (m.file() == null) {
                continue;
            }
            if (!frameNames.containsKey(m.frameId()) || !code.files().containsKey(m.file()) || framePath.containsKey(m.frameId())) {
                dropped++;
                continue;
            }
            framePath.put(m.frameId(), m.file());
        }

        // ② 짝이 있는 프레임마다 노드 → 파일·컴포넌트
        List<Saved> saved = new ArrayList<>();
        Map<String, List<DesignCodeMapper.NodeMatch>> nodeAnswers = new HashMap<>();
        Map<String, Set<String>> nodeIds = new HashMap<>();
        Map<String, Map<String, String>> nodeNames = new HashMap<>();
        List<String> mapped = new ArrayList<>(framePath.keySet());
        ExecutorService pool = Executors.newFixedThreadPool(nodeConcurrency);
        try {
            for (int i = 0; i < mapped.size(); i += FRAME_BATCH) {
                List<String> batch = mapped.subList(i, Math.min(mapped.size(), i + FRAME_BATCH));
                Map<String, JsonNode> docs = figmaCall(() -> figma.nodeDocuments(in.fileKey(), batch));
                Map<String, Future<List<DesignCodeMapper.NodeMatch>>> pending = new LinkedHashMap<>();
                for (Map.Entry<String, JsonNode> e : docs.entrySet()) {
                    Set<String> idsInFrame = new HashSet<>();
                    DesignCodeMapper.FrameDetail detail = FigmaFrames.detail(e.getValue(), idsInFrame);
                    Map<String, String> names = new HashMap<>();
                    FigmaFrames.names(e.getValue(), names);
                    nodeIds.put(e.getKey(), idsInFrame);
                    nodeNames.put(e.getKey(), names);
                    pending.put(e.getKey(), pool.submit(() -> mapper.mapNodes(detail, code)));
                }
                for (Map.Entry<String, Future<List<DesignCodeMapper.NodeMatch>>> p : pending.entrySet()) {
                    nodeAnswers.put(p.getKey(), await(p.getValue()));
                }
            }
        } finally {
            pool.shutdownNow();
        }
        for (String frameId : mapped) {
            String frameName = frameNames.get(frameId);
            saved.add(new Saved(frameId, frameName, null, null, framePath.get(frameId), null));
            Set<String> seen = new HashSet<>();
            for (DesignCodeMapper.NodeMatch n : nodeAnswers.getOrDefault(frameId, List.of())) {
                if (n.file() == null) {
                    continue;
                }
                boolean known = nodeIds.getOrDefault(frameId, Set.of()).contains(n.nodeId());
                if (!known || !code.files().containsKey(n.file()) || n.symbol() == null || !seen.add(n.nodeId())) {
                    dropped++;
                    continue;
                }
                saved.add(new Saved(frameId, frameName, n.nodeId(), nodeNames.get(frameId).get(n.nodeId()), n.file(), n.symbol()));
            }
        }
        // 코드 파일을 찾지 못한 프레임 — 이슈가 아니라 "확인해 볼 곳"(LLM이 놓쳤을 수도 있다). 순서는 Figma에서 모은 순서
        List<Unmapped> unmapped = new ArrayList<>();
        for (String id : ids) {
            if (!framePath.containsKey(id) && frameNames.containsKey(id)) {
                unmapped.add(new Unmapped(id, frameNames.get(id), frameSection.get(id)));
            }
        }
        return new Result(sha, saved, dropped, unmapped);
    }

    private DesignCodeMapper.CodeBundle readCode(String owner, String repo, String sha, String fullName) {
        List<GithubClient.TreeFile> files = CodeSelector.select(github.tree(owner, repo, sha));
        if (files.isEmpty()) {
            throw new MappingFailedException("저장소에서 UI 코드 파일(.tsx·.jsx·.ts·.js·.vue·.svelte)을 찾지 못했어요. 분석할 브랜치를 확인해 주세요.");
        }
        if (files.size() > CodeSelector.MAX_FILES) {
            throw new MappingFailedException("UI 코드 파일이 " + CodeSelector.MAX_FILES + "개를 넘어요. 지금은 이보다 작은 저장소만 분석할 수 있어요.");
        }
        Map<String, String> sources = new TreeMap<>();
        int total = 0;
        for (GithubClient.TreeFile f : files) {
            String src = github.fileContent(owner, repo, sha, f.path());
            total += src.length();
            if (total > maxCodeChars) {
                // 잘라서 넘기면 매핑이 조용히 빠진다 — 실패로 알린다
                throw new MappingFailedException("UI 코드가 너무 커요(" + maxCodeChars + "자 초과). 지금은 이보다 작은 저장소만 분석할 수 있어요.");
            }
            sources.put(f.path(), src);
        }
        return new DesignCodeMapper.CodeBundle(fullName, sha, sources);
    }

    /** Figma 요청 한도(429)면 기다렸다가 다시 부른다. 다른 오류는 그대로 던진다 */
    private <T> T figmaCall(java.util.function.Supplier<T> call) {
        for (int attempt = 1; ; attempt++) {
            try {
                return call.get();
            } catch (FigmaApiException e) {
                // 며칠짜리 한도는 기다려도 풀리지 않는다 — 바로 실패시키고 소유자 토큰을 안내한다(2026-11-11)
                if (e.status() != 429 || e.longRateLimit() || attempt >= FIGMA_ATTEMPTS) {
                    throw e;
                }
                long wait = Math.min(FIGMA_WAIT_CAP_SECONDS, e.retryAfterSeconds() > 0 ? e.retryAfterSeconds() : 20L * attempt);
                log.info("Figma 요청 한도 — {}초 뒤 다시 시도({}/{})", wait, attempt, FIGMA_ATTEMPTS - 1);
                sleeper.sleep(wait);
            }
        }
    }

    private static <T> T await(Future<T> f) {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MappingFailedException("비교 분석이 중단됐어요.", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new MappingFailedException("AI 분석 호출에 실패했어요.", e);
        }
    }

    private record Inputs(Long projectId, String fileKey, String scopeId, String repo, String branch) {
    }

    private record Saved(String frameId, String frameName, String nodeId, String nodeName, String path, String symbol) {
    }

    private record Result(String commitSha, List<Saved> mappings, int dropped, List<Unmapped> unmapped) {
    }

    /** 저장 형태 — MappingListResponse.UnmappedFrame과 같은 이름(snake_case) */
    @tools.jackson.databind.annotation.JsonNaming(tools.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    record Unmapped(String frameId, String frameName, String section) {
    }
}
