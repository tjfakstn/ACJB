package com.acjb.server.trace;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.acjb.server.common.ApiException;
import com.acjb.server.domain.DesignCodeMapping;
import com.acjb.server.domain.IssueSource;
import com.acjb.server.domain.Member;
import com.acjb.server.domain.MemberRole;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.QAIssue;
import com.acjb.server.domain.RunKind;
import com.acjb.server.domain.RunStatus;
import com.acjb.server.domain.StyleMismatch;
import com.acjb.server.domain.Verification;
import com.acjb.server.domain.VerificationSource;
import com.acjb.server.github.GithubApiException;
import com.acjb.server.github.GithubClient;
import com.acjb.server.github.GithubClient.CommitInfo;
import com.acjb.server.github.GithubClient.PullInfo;
import com.acjb.server.domain.ManualQaPin;
import com.acjb.server.repository.DesignCodeMappingRepository;
import com.acjb.server.repository.ManualQaPinRepository;
import com.acjb.server.repository.ProjectMemberRepository;
import com.acjb.server.repository.ProjectRunRepository;
import com.acjb.server.repository.QAIssueRepository;
import com.acjb.server.repository.VerificationRepository;

/**
 * 이슈 상세의 코드 추적(SPEC AC34, 시안 ISS-02~04). GitHub는 읽기만 한다 — 이슈·PR·코멘트를 만들거나 바꾸지 않는다(N13).
 *  - code: 첫 QA가 값을 읽은 파일·줄·커밋(저장된 값)과 매핑의 컴포넌트 이름
 *  - origin: 고치기 전 참고 — 그 줄을 마지막으로 바꾼 커밋(토큰이 있으면 blame, 없으면 파일 기준)과 그 PR
 *  - fixes: 고친 뒤 — 이슈 키가 정확히 들어간 커밋·PR, 담당 팀이 붙인 링크, Re-QA 커밋. 코드 변경을 보고 추정하지 않는다
 * GitHub를 읽지 못해도 저장된 값으로 응답한다(github: unavailable). 외부 호출 동안 DB 트랜잭션을 잡지 않는다
 */
@Service
public class CodeTraceService {

    private static final Logger log = LoggerFactory.getLogger(CodeTraceService.class);
    private static final Duration LIST_TTL = Duration.ofMinutes(2);
    private static final Duration ORIGIN_TTL = Duration.ofMinutes(30);
    private static final Pattern PR_URL = Pattern.compile("https://github\\.com/([^/\\s]+)/([^/\\s]+)/pull/(\\d{1,9})/?");
    private static final Pattern COMMIT_URL = Pattern.compile("https://github\\.com/([^/\\s]+)/([^/\\s]+)/commit/([0-9a-fA-F]{7,40})/?");

    private final QAIssueRepository issues;
    private final ProjectMemberRepository projectMembers;
    private final VerificationRepository verifications;
    private final ProjectRunRepository runs;
    private final DesignCodeMappingRepository mappings;
    private final ManualQaPinRepository pins;
    private final GithubClient github;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Map<String, Cached<?>> cache = new ConcurrentHashMap<>();

    private record Cached<T>(T value, Instant expiresAt) {
    }

    public CodeTraceService(QAIssueRepository issues, ProjectMemberRepository projectMembers, VerificationRepository verifications,
                            ProjectRunRepository runs, DesignCodeMappingRepository mappings, ManualQaPinRepository pins,
                            GithubClient github, TransactionTemplate tx, Clock clock) {
        this.pins = pins;
        this.issues = issues;
        this.projectMembers = projectMembers;
        this.verifications = verifications;
        this.runs = runs;
        this.mappings = mappings;
        this.github = github;
        this.tx = tx;
        this.clock = clock;
    }

    /** DB에서 꺼낸 값 — 트랜잭션 밖(GitHub 호출)에서 엔티티 대신 쓴다 */
    private record Snapshot(Long issueId, Instant createdAt, String repo, String branch, CodeTraceView.Code code,
                            List<CodeTraceView.Related> related, String fixLink, List<ReQa> reQa) {
    }

    private record ReQa(String sha, Instant at, String verdict) {
    }

    public CodeTraceView trace(Long projectId, String issueId) {
        Snapshot s = tx.execute(st -> snapshot(projectId, issueId));
        String key = key(s.issueId());
        String[] ownerRepo = split(s.repo());
        if (ownerRepo == null) {
            return new CodeTraceView(key, s.code(), s.related(), null, offlineFixes(s), "not_connected");
        }
        String status = "ok";
        CodeTraceView.Origin origin = null;
        List<CodeTraceView.Fix> fixes = new ArrayList<>();
        try {
            origin = origin(projectId, ownerRepo, s.code());
        } catch (GithubApiException e) {
            status = "unavailable";
            log.info("코드 추적 — 문제 코드 커밋 조회 실패: status={}", e.status());
        }
        try {
            fixes = fixes(projectId, ownerRepo, s, key);
        } catch (GithubApiException e) {
            status = "unavailable";
            fixes = offlineFixes(s);
            log.info("코드 추적 — 고친 커밋·PR 조회 실패: status={}", e.status());
        }
        return new CodeTraceView(key, s.code(), s.related(), origin, fixes, status);
    }

    /** PUT fix-link — 담당 팀 역할 멤버만(AC13과 같은 사람). 연결된 저장소의 PR·커밋 주소만 */
    public CodeTraceView attach(Long projectId, String issueId, String rawUrl, Member actor) {
        String url = rawUrl == null ? "" : rawUrl.trim();
        tx.executeWithoutResult(st -> {
            QAIssue issue = load(projectId, issueId);
            requireAssignee(projectId, issue, actor);
            String[] ownerRepo = split(issue.getProject().getGithubRepo());
            if (ownerRepo == null) {
                throw ApiException.badRequest("GitHub 저장소가 연결되지 않아 수정 링크를 붙일 수 없어요.");
            }
            if (url.length() > 500 || !sameRepo(url, ownerRepo)) {
                throw ApiException.badRequest("연결된 저장소(" + issue.getProject().getGithubRepo()
                    + ")의 PR 주소(…/pull/번호)나 커밋 주소(…/commit/sha)를 넣어 주세요.");
            }
            issue.attachFixLink(url.replaceAll("/$", ""), actor, clock.instant());
        });
        return trace(projectId, issueId);
    }

    public void detach(Long projectId, String issueId, Member actor) {
        tx.executeWithoutResult(st -> {
            QAIssue issue = load(projectId, issueId);
            requireAssignee(projectId, issue, actor);
            issue.clearFixLink();
        });
    }

    // ------------------------------------------------------------------ DB

    private Snapshot snapshot(Long projectId, String issueId) {
        QAIssue issue = load(projectId, issueId);
        Project p = issue.getProject();
        StyleMismatch m = issue.getStyleMismatch();
        CodeTraceView.Code code = null;
        List<CodeTraceView.Related> related = List.of();
        if (issue.getSource() == IssueSource.FIRST_QA && m != null && m.getCodePath() != null) {
            String sha = issue.getFirstQaRun() == null ? null : issue.getFirstQaRun().getCommitSha();
            String url = p.getGithubRepo() == null || sha == null ? null
                : "https://github.com/" + p.getGithubRepo() + "/blob/" + sha + "/" + m.getCodePath() + (m.getCodeLine() == null ? "" : "#L" + m.getCodeLine());
            code = new CodeTraceView.Code("element", m.getCodePath(), m.getCodeLine(), symbol(projectId, m), sha, url);
            if (m.getNodeId() != null) {
                related = issues.findFirstQaByElement(projectId, m.getNodeId()).stream()
                    .filter(i -> !i.getId().equals(issue.getId()) && i.getStyleMismatch() != null)
                    .map(i -> new CodeTraceView.Related(String.valueOf(i.getId()), lower(i.getStyleMismatch().getProperty().name()),
                        i.getStyleMismatch().getExpectedValue(), i.getStyleMismatch().getActualValue(), lower(i.getStatus().name())))
                    .toList();
            }
        }
        if (issue.getSource() == IssueSource.MANUAL) {
            code = pins.findByIssueId(issue.getId()).map(ManualQaPin::getFigmaFrameId).flatMap(f -> screenFile(projectId, p, f)).orElse(null);
        }
        List<ReQa> reQa = new ArrayList<>();
        for (Verification v : verifications.findByIssueIdOrderByVerifiedAtDescIdDesc(issue.getId())) {
            if (v.getSource() == VerificationSource.RE_QA && v.getReQaRun() != null && v.getReQaRun().getCommitSha() != null) {
                reQa.add(new ReQa(v.getReQaRun().getCommitSha(), v.getVerifiedAt(), lower(v.getResult().name())));
            }
        }
        return new Snapshot(issue.getId(), issue.getCreatedAt(), p.getGithubRepo(), p.getGithubBranch(), code, related,
            issue.getFixLinkUrl(), reQa);
    }

    /** 수동 QA — 기준 프레임을 최근 성공한 비교 분석이 짝지은 화면 파일(AC34, 줄 단위가 아니다) */
    private Optional<CodeTraceView.Code> screenFile(Long projectId, Project p, String frameId) {
        return runs.findFirstByProjectIdAndKindAndStatusOrderByFinishedAtDescIdDesc(projectId, RunKind.ANALYSIS, RunStatus.SUCCEEDED)
            .flatMap(run -> mappings.findFrame(run.getId(), frameId).stream().findFirst().map(d -> {
                String sha = run.getCommitSha();
                String url = p.getGithubRepo() == null || sha == null ? null
                    : "https://github.com/" + p.getGithubRepo() + "/blob/" + sha + "/" + d.getCodePath();
                return new CodeTraceView.Code("screen", d.getCodePath(), null, null, sha, url);
            }));
    }

    /** 매핑의 컴포넌트 — 같은 프레임·파일의 노드 짝이 한 컴포넌트를 가리킬 때만(여럿이면 어느 것인지 모른다) */
    private String symbol(Long projectId, StyleMismatch m) {
        Set<String> symbols = new LinkedHashSet<>();
        runs.findFirstByProjectIdAndKindAndStatusOrderByFinishedAtDescIdDesc(projectId, RunKind.ANALYSIS, RunStatus.SUCCEEDED)
            .ifPresent(run -> {
                for (DesignCodeMapping d : mappings.findOrdered(run.getId())) {
                    if (d.getCodeSymbol() != null && m.getCodePath().equals(d.getCodePath()) && d.getFrameId().equals(m.getFrameId())) {
                        symbols.add(d.getCodeSymbol());
                    }
                }
            });
        return symbols.size() == 1 ? symbols.iterator().next() : null;
    }

    private QAIssue load(Long projectId, String issueId) {
        Long id;
        try {
            id = issueId == null ? null : Long.valueOf(issueId.trim());
        } catch (NumberFormatException e) {
            id = null;
        }
        Long found = id;
        return found == null ? notFound() : issues.findByIdAndProjectId(found, projectId).orElseGet(CodeTraceService::notFound);
    }

    private static QAIssue notFound() {
        throw ApiException.notFound("이슈를 찾을 수 없습니다.");
    }

    private void requireAssignee(Long projectId, QAIssue issue, Member actor) {
        MemberRole team = issue.getAssignedTeam();
        MemberRole role = projectMembers.findByProjectIdAndMemberId(projectId, actor.getId()).map(pm -> pm.getRole()).orElse(null);
        if (team == null || team != role) {
            throw ApiException.forbidden("수정 링크는 이 이슈의 담당 팀 역할을 가진 멤버만 붙일 수 있습니다.");
        }
    }

    // ------------------------------------------------------------------ GitHub(읽기만)

    private CodeTraceView.Origin origin(Long projectId, String[] or, CodeTraceView.Code code) {
        if (code == null || code.commitSha() == null) {
            return null;
        }
        if (github.hasToken() && code.line() != null) {
            Optional<GithubClient.Origin> o = cached(projectId + "|blame|" + or[0] + "/" + or[1] + "|" + code.commitSha() + "|" + code.path() + "|" + code.line(),
                ORIGIN_TTL, () -> github.blameLine(or[0], or[1], code.commitSha(), code.path(), code.line()));
            if (o.isPresent()) {
                return new CodeTraceView.Origin("line", commit(o.get().commit()), o.get().pulls().stream().map(CodeTraceService::pull).toList());
            }
        }
        Optional<CommitInfo> c = cached(projectId + "|file|" + or[0] + "/" + or[1] + "|" + code.commitSha() + "|" + code.path(), ORIGIN_TTL,
            () -> github.lastCommitForPath(or[0], or[1], code.commitSha(), code.path()));
        if (c.isEmpty()) {
            return null;
        }
        List<PullInfo> pulls = cached(projectId + "|pulls-of|" + or[0] + "/" + or[1] + "|" + c.get().sha(), ORIGIN_TTL,
            () -> github.pullsForCommit(or[0], or[1], c.get().sha()));
        return new CodeTraceView.Origin("file", commit(c.get()), pulls.stream().map(CodeTraceService::pull).toList());
    }

    private List<CodeTraceView.Fix> fixes(Long projectId, String[] or, Snapshot s, String key) {
        Pattern exact = keyPattern(key);
        List<CodeTraceView.Fix> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        // ① 이슈 키 — 이슈 등록 뒤의 커밋·PR만(키는 이슈가 생긴 뒤에야 있다)
        if (s.branch() != null) {
            Instant since = s.createdAt().minusSeconds(60);
            List<CommitInfo> commits = cached(projectId + "|commits|" + or[0] + "/" + or[1] + "|" + s.branch() + "|" + since.getEpochSecond(), LIST_TTL,
                () -> github.commitsSince(or[0], or[1], s.branch(), since));
            for (CommitInfo c : commits) {
                if (c.message() != null && exact.matcher(c.message()).find() && after(c.committedAt(), s.createdAt()) && seen.add("c:" + c.sha())) {
                    out.add(CodeTraceView.Fix.ofCommit("key", commit(c), null));
                }
            }
        }
        List<PullInfo> pulls = cached(projectId + "|pulls|" + or[0] + "/" + or[1], LIST_TTL, () -> github.recentPulls(or[0], or[1]));
        for (PullInfo p : pulls) {
            boolean mentions = (p.title() != null && exact.matcher(p.title()).find()) || (p.body() != null && exact.matcher(p.body()).find())
                || (p.headRef() != null && exact.matcher(p.headRef()).find());
            if (mentions && after(p.createdAt(), s.createdAt()) && seen.add("p:" + p.number())) {
                out.add(CodeTraceView.Fix.ofPull("key", pull(p)));
            }
        }
        // ② 담당 팀이 붙인 링크
        if (s.fixLink() != null) {
            // 키로도 찾은 같은 커밋·PR이면 사람이 붙인 쪽 하나만 남긴다
            CodeTraceView.Fix manual = manual(projectId, or, s.fixLink());
            out.removeIf(f -> f.sameTarget(manual));
            out.add(manual);
        }
        // ③ Re-QA가 판정한 커밋 — 키·링크로 이미 있는 커밋이면 그 항목에 판정만 붙인다
        for (CodeTraceView.Fix r : reQaFixes(s, or)) {
            Optional<CodeTraceView.Fix> same = out.stream().filter(f -> f.sameTarget(r)).findFirst();
            if (same.isPresent()) {
                out.set(out.indexOf(same.get()), same.get().withVerdict(r.verdict()));
            } else {
                out.add(r);
            }
        }
        out.sort(Comparator.comparing(CodeTraceView.Fix::at, Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    private CodeTraceView.Fix manual(Long projectId, String[] or, String url) {
        Matcher pr = PR_URL.matcher(url);
        if (pr.matches()) {
            int n = Integer.parseInt(pr.group(3));
            Optional<PullInfo> p = cached(projectId + "|pull|" + or[0] + "/" + or[1] + "|" + n, LIST_TTL, () -> github.pull(or[0], or[1], n));
            return CodeTraceView.Fix.ofPull("manual", p.map(CodeTraceService::pull)
                .orElse(new CodeTraceView.Pull(n, null, null, null, null, url)));
        }
        Matcher cm = COMMIT_URL.matcher(url);
        String sha = cm.matches() ? cm.group(3) : url;
        Optional<CommitInfo> c = cached(projectId + "|commit|" + or[0] + "/" + or[1] + "|" + sha, LIST_TTL, () -> github.commit(or[0], or[1], sha));
        return CodeTraceView.Fix.ofCommit("manual", c.map(CodeTraceService::commit)
            .orElse(new CodeTraceView.Commit(sha, null, null, null, url)), null);
    }

    /** GitHub 없이 만들 수 있는 것 — 붙인 링크 주소와 Re-QA 커밋 */
    private static List<CodeTraceView.Fix> offlineFixes(Snapshot s) {
        String[] or = split(s.repo());
        List<CodeTraceView.Fix> out = new ArrayList<>();
        if (s.fixLink() != null) {
            Matcher pr = PR_URL.matcher(s.fixLink());
            if (pr.matches()) {
                out.add(CodeTraceView.Fix.ofPull("manual", new CodeTraceView.Pull(Integer.parseInt(pr.group(3)), null, null, null, null, s.fixLink())));
            } else {
                Matcher cm = COMMIT_URL.matcher(s.fixLink());
                out.add(CodeTraceView.Fix.ofCommit("manual", new CodeTraceView.Commit(cm.matches() ? cm.group(3) : s.fixLink(), null, null, null,
                    s.fixLink()), null));
            }
        }
        out.addAll(reQaFixes(s, or));
        return out;
    }

    private static List<CodeTraceView.Fix> reQaFixes(Snapshot s, String[] or) {
        List<CodeTraceView.Fix> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ReQa r : s.reQa()) {
            if (seen.add(r.sha())) {
                String url = or == null ? null : "https://github.com/" + or[0] + "/" + or[1] + "/commit/" + r.sha();
                out.add(CodeTraceView.Fix.ofCommit("re_qa", new CodeTraceView.Commit(r.sha(), null, null, r.at(), url), r.verdict()));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 도움

    /** 이슈 키 — 커밋 메시지·PR 제목에 적으면 연결된다. 화면 표시용 #id·QA-M###과 별개로 모든 이슈에 같은 형식 */
    public static String key(Long issueId) {
        return "QA-" + issueId;
    }

    /** QA-3이 QA-34·QA-3a에 맞지 않게 앞뒤를 막는다. 대소문자 무시 */
    static Pattern keyPattern(String key) {
        return Pattern.compile("(?<![A-Za-z0-9-])" + Pattern.quote(key) + "(?![0-9A-Za-z])", Pattern.CASE_INSENSITIVE);
    }

    private static boolean after(Instant at, Instant created) {
        return at == null || created == null || !at.isBefore(created.minusSeconds(60));
    }

    private static boolean sameRepo(String url, String[] or) {
        Matcher m = PR_URL.matcher(url);
        if (!m.matches()) {
            m = COMMIT_URL.matcher(url);
            if (!m.matches()) {
                return false;
            }
        }
        return m.group(1).equalsIgnoreCase(or[0]) && m.group(2).equalsIgnoreCase(or[1]);
    }

    private static String[] split(String repo) {
        return repo == null || !repo.contains("/") ? null : repo.split("/", 2);
    }

    private static CodeTraceView.Commit commit(CommitInfo c) {
        return new CodeTraceView.Commit(c.sha(), c.message(), c.author(), c.committedAt(), c.url());
    }

    private static CodeTraceView.Pull pull(PullInfo p) {
        return new CodeTraceView.Pull(p.number(), p.title(), p.state(), p.author(), p.createdAt(), p.url());
    }

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    /** 프로젝트마다 기억한다(SPEC AC34) — 키 앞에 프로젝트 id */
    private <T> T cached(String key, Duration ttl, Supplier<T> load) {
        Instant now = clock.instant();
        Cached<?> c = cache.get(key);
        if (c != null && c.expiresAt().isAfter(now)) {
            return (T) c.value();
        }
        T value = load.get();
        cache.put(key, new Cached<>(value, now.plus(ttl)));
        if (cache.size() > 2000) {
            cache.entrySet().removeIf(e -> !e.getValue().expiresAt().isAfter(now));
        }
        return value;
    }
}
