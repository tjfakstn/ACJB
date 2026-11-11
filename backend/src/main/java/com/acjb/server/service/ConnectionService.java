package com.acjb.server.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.acjb.server.common.ApiException;
import com.acjb.server.common.ErrorCodes;
import com.acjb.server.domain.FigmaScope;
import com.acjb.server.domain.Member;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.RunKind;
import com.acjb.server.domain.RunStatus;
import com.acjb.server.dto.GithubConnectResponse;
import com.acjb.server.dto.ProjectDetailResponse;
import com.acjb.server.figma.FigmaApiException;
import com.acjb.server.figma.FigmaErrors;
import com.acjb.server.figma.FigmaTokenCipher;
import com.acjb.server.figma.FigmaTokenScope;
import com.acjb.server.figma.FigmaClient;
import com.acjb.server.figma.FigmaNode;
import com.acjb.server.github.GithubApiException;
import com.acjb.server.github.GithubClient;
import com.acjb.server.repository.ProjectRepository;
import com.acjb.server.repository.ProjectRunRepository;

/**
 * AC21 — Figma 파일·GitHub 공개 저장소 연결. 소유자만, 서버 자격 증명으로 실제 접근이 되는지 확인한 뒤에만 저장한다.
 * 검사 순서: 소유자(403) → 링크 형식(400) → 비교 분석 성공 후 변경 금지(409) → 외부 확인(422·502·503) → 저장.
 * 거부되면 기존 연결을 바꾸지 않는다
 */
@Service
public class ConnectionService {

    private static final Logger log = LoggerFactory.getLogger(ConnectionService.class);

    /**
     * 최종 시안만 담은 페이지([공유하기]) 또는 섹션([링크 복사])의 링크를 받는다(2026-11-07): figma.com/design/{key}/{이름}?node-id={페이지·섹션}&t=…
     * node-id는 주소에서 "424-2"(또는 "424:2", "424%3A2")로 온다. 프로토타입·브랜치·프로젝트 링크는 이 형식이 아니라서 400
     */
    private static final Pattern FIGMA_URL = Pattern.compile("^(?:https?://)?(?:www\\.)?figma\\.com/design/([A-Za-z0-9]+)(?:/[^/?#]*)?\\?([^#]*)(?:#.*)?$");
    private static final Pattern NODE_ID_PARAM = Pattern.compile("(?:^|&)node-id=([0-9]+)(?:-|:|%3A|%3a)([0-9]+)(?:&|$)");
    private static final String PAGE_LINK_GUIDE = "최종 시안만 담은 페이지의 [공유하기] 링크나, 섹션을 우클릭해 [링크 복사]한 링크를 붙여 넣어 주세요.";
    private static final Pattern GITHUB_URL = Pattern.compile("^(?:https?://)?(?:www\\.)?github\\.com/([A-Za-z0-9-]+)/([A-Za-z0-9._-]+?)(?:\\.git)?/?(?:[?#].*)?$");
    /** 주소창에서 복사한 브랜치·파일 주소(`/tree/…`, `/blob/…`) — 저장소 주소만 넣으라고 따로 안내한다. 브랜치는 연결 뒤 고른다(2026-11-11) */
    private static final Pattern GITHUB_SUBPATH_URL = Pattern.compile("^(?:https?://)?(?:www\\.)?github\\.com/[A-Za-z0-9-]+/[A-Za-z0-9._-]+/(?:tree|blob)/.*$");
    private static final String NOT_EXPORTABLE = "파일 소유자가 이 Figma 파일의 내보내기를 막아 두어서 QAting 서버 계정이 시안을 읽을 수 없어요. "
        + "본인 Figma 토큰을 넣거나, 파일 소유자가 [공유] 창의 설정에서 뷰어의 복사·공유·내보내기 허용을 켠 뒤 다시 확인해 주세요.";
    /** Figma 개인 액세스 토큰 — figd_로 시작한다(예전 토큰은 접두어가 없을 수 있어 길이·문자만 본다) */
    private static final Pattern FIGMA_TOKEN = Pattern.compile("^[A-Za-z0-9_-]{20,200}$");
    private static final int URL_MAX = 500;
    private static final HttpStatus UNPROCESSABLE = HttpStatus.valueOf(422);

    private final ProjectRepository projects;
    private final ProjectRunRepository runs;
    private final FigmaClient figma;
    private final FigmaTokenCipher cipher;
    private final GithubClient github;
    private final Clock clock;

    public ConnectionService(ProjectRepository projects, ProjectRunRepository runs, FigmaClient figma, FigmaTokenCipher cipher,
                             GithubClient github, Clock clock) {
        this.cipher = cipher;
        this.projects = projects;
        this.runs = runs;
        this.figma = figma;
        this.github = github;
        this.clock = clock;
    }

    @Transactional
    public ProjectDetailResponse.FigmaConnection connectFigma(Long projectId, String rawUrl, String rawToken, Member requester) {
        Project project = ownedProject(projectId, requester);
        String url = trimmed(rawUrl);
        Matcher m = url == null ? null : FIGMA_URL.matcher(url);
        Matcher node = m != null && m.matches() ? NODE_ID_PARAM.matcher(m.group(2)) : null;
        if (node == null || !node.find()) {
            throw ApiException.badRequest("Figma 페이지·섹션 링크가 아니에요. " + PAGE_LINK_GUIDE);
        }
        String token = trimmed(rawToken);
        if (token != null && !FIGMA_TOKEN.matcher(token).matches()) {
            throw ApiException.badRequest("Figma 토큰 형식이 아니에요. Figma 설정 → Security → Personal access tokens에서 만든 토큰(figd_로 시작)을 붙여 넣어 주세요.");
        }
        String pageId = node.group(1) + ":" + node.group(2);
        rejectAfterAnalysis(projectId);
        if (token != null && !cipher.enabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.FIGMA_UNAVAILABLE, "서버에 Figma 토큰 저장 설정이 없어 본인 토큰을 받을 수 없어요. 관리자에게 알려 주세요.");
        }
        // 새로 넣은 토큰 → 이미 넣어 둔 토큰 → 서버 토큰 순으로 확인한다(2026-11-11)
        String useToken = token != null ? token : cipher.decrypt(project.getFigmaTokenEnc());
        if (useToken == null && !figma.isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.FIGMA_UNAVAILABLE, "서버에 Figma 연동이 설정되지 않았습니다.");
        }
        String fileKey = m.group(1);
        Checked checked = FigmaTokenScope.with(useToken, () -> check(fileKey, pageId));
        Instant now = clock.instant();
        project.connectFigma(url, fileKey, checked.outline().name(), pageId, checked.scopeName(), checked.scope(), now);
        if (token != null) {
            project.changeFigmaToken(cipher.encrypt(token), token.substring(token.length() - 4));
        }
        return new ProjectDetailResponse.FigmaConnection(url, fileKey, checked.outline().name(), pageId, checked.scopeName(),
            checked.scope().name().toLowerCase(java.util.Locale.ROOT), now, project.getFigmaTokenEnc() != null, project.getFigmaTokenHint());
    }

    private record Checked(FigmaClient.FileOutline outline, String scopeName, FigmaScope scope) {
    }

    /**
     * 파일을 읽고 링크가 페이지·섹션인지 확인한다. 페이지도 노드를 한 번 읽는다 — 파일 목록(depth=1)은 읽혀도
     * 노드 요청은 Figma 한도에 막히는 파일이 있어, 비교 분석에서야 실패하지 않게 연결할 때 알린다(2026-11-11)
     */
    private Checked check(String fileKey, String pageId) {
        FigmaClient.FileOutline outline;
        java.util.Optional<FigmaNode> target;
        try {
            outline = figma.outline(fileKey);
            target = figma.nodes(fileKey, List.of(pageId)).getOrDefault(pageId, java.util.Optional.empty());
        } catch (FigmaApiException e) {
            log.warn("Figma 파일 확인 실패: status={} userToken={} message={}", e.status(), e.userToken(), e.getMessage());
            if (e.tokenProblem() && !e.userToken()) {
                // 사용자 파일 권한이 아니라 서버 설정 문제다 — 관리자가 FIGMA_ACCESS_TOKEN을 새로 넣어야 한다
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.FIGMA_UNAVAILABLE,
                    "서버의 Figma 연동 토큰이 만료됐거나 올바르지 않아요. 관리자에게 알려 주세요.");
            }
            if (e.tokenProblem()) {
                throw ApiException.badRequest(FigmaErrors.message(e));
            }
            if (e.notExportable() && !e.userToken()) {
                throw new ApiException(UNPROCESSABLE, ErrorCodes.CONNECTION_FORBIDDEN, NOT_EXPORTABLE);
            }
            if (e.status() == 403 || e.longRateLimit()) {
                throw new ApiException(UNPROCESSABLE, ErrorCodes.CONNECTION_FORBIDDEN, FigmaErrors.message(e));
            }
            if (e.status() == 429) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCodes.CONNECTION_FAILED, FigmaErrors.message(e));
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCodes.CONNECTION_FAILED,
                "파일을 찾을 수 없거나 Figma 응답이 지연되고 있어요. 링크를 확인한 뒤 다시 시도해 주세요.");
        }
        // 페이지면 그대로, 아니면 섹션인지 확인한다 — 프레임 등 다른 노드는 대조 범위가 아니라 받지 않는다(2026-11-07)
        FigmaClient.Page page = outline.pages().stream().filter(p -> p.id().equals(pageId)).findFirst().orElse(null);
        if (page != null) {
            return new Checked(outline, page.name(), FigmaScope.PAGE);
        }
        if (target.isEmpty() || !"SECTION".equals(target.get().type())) {
            throw ApiException.badRequest("링크가 페이지나 섹션이 아니라 프레임 같은 다른 요소를 가리켜요. " + PAGE_LINK_GUIDE);
        }
        return new Checked(outline, target.get().name(), FigmaScope.SECTION);
    }

    /** 브랜치 이름 — GitHub가 허용하는 문자만 대략 거른다(실제 존재는 GitHub로 확인) */
    private static final Pattern BRANCH_NAME = Pattern.compile("^[A-Za-z0-9._/-]{1,100}$");

    @Transactional
    public GithubConnectResponse connectGithub(Long projectId, String rawUrl, String rawBranch, Member requester) {
        Project project = ownedProject(projectId, requester);
        String url = trimmed(rawUrl);
        Matcher m = url == null ? null : GITHUB_URL.matcher(url);
        if (url != null && GITHUB_SUBPATH_URL.matcher(url).matches()) {
            throw ApiException.badRequest("브랜치 주소가 아니라 저장소 주소만 넣어 주세요 (예: https://github.com/조직/저장소). 브랜치는 연결한 뒤 고를 수 있어요.");
        }
        if (m == null || !m.matches()) {
            throw ApiException.badRequest("올바른 GitHub 저장소 링크가 아니에요 (예: https://github.com/조직/저장소)");
        }
        String requestedBranch = rawBranch == null || rawBranch.isBlank() ? null : rawBranch.trim();
        if (requestedBranch != null && !BRANCH_NAME.matcher(requestedBranch).matches()) {
            throw ApiException.badRequest("브랜치 이름이 올바르지 않아요.");
        }
        rejectAfterAnalysis(projectId);
        GithubClient.RepoInfo repo;
        List<String> branches;
        boolean branchOk;
        try {
            repo = github.repository(m.group(1), m.group(2));
            branches = github.branches(m.group(1), m.group(2)).stream().sorted().toList();
            branchOk = requestedBranch == null || branches.contains(requestedBranch)
                || github.branchExists(m.group(1), m.group(2), requestedBranch);
        } catch (GithubApiException e) {
            log.warn("GitHub 저장소 확인 실패: status={} message={} cause={}", e.status(), e.getMessage(),
                e.getCause() == null ? null : e.getCause().toString());
            // GitHub는 비공개 저장소와 없는 저장소를 똑같이 404로 준다 — 둘 다 422로 안내한다(2026-11-07 설만수 결정)
            if (e.status() == 404) {
                throw new ApiException(UNPROCESSABLE, ErrorCodes.CONNECTION_FORBIDDEN,
                    "저장소가 없거나 비공개예요. 공개 저장소만 연결할 수 있어요.");
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCodes.CONNECTION_FAILED,
                "GitHub 응답이 지연되거나 실패했어요. 잠시 뒤 다시 시도해 주세요.");
        }
        if (repo.isPrivate()) {
            // 서버 토큰이 비공개 저장소를 읽을 수 있어도 v1은 공개 저장소만 연결한다(N13)
            throw new ApiException(UNPROCESSABLE, ErrorCodes.CONNECTION_FORBIDDEN, "비공개 저장소예요. 공개 저장소만 연결할 수 있어요.");
        }
        if (!branchOk) {
            throw ApiException.badRequest("저장소에 '" + requestedBranch + "' 브랜치가 없어요.");
        }
        String branch = requestedBranch == null ? repo.defaultBranch() : requestedBranch;
        Instant now = clock.instant();
        project.connectGithub(url, repo.fullName(), repo.defaultBranch(), branch, now);
        return new GithubConnectResponse(url, repo.fullName(), repo.defaultBranch(), branch, branches, now);
    }

    private Project ownedProject(Long projectId, Member requester) {
        Project project = projects.findById(projectId).orElseThrow(() -> ApiException.notFound("프로젝트를 찾을 수 없습니다."));
        if (!project.getOwner().getId().equals(requester.getId())) {
            throw ApiException.forbidden("Figma·GitHub 연결은 프로젝트 소유자만 할 수 있습니다.");
        }
        return project;
    }

    /** 매핑이 가리키는 파일·노드가 바뀌므로 비교 분석이 성공한 뒤에는 링크를 바꾸지 않는다(AC21) */
    private void rejectAfterAnalysis(Long projectId) {
        boolean analyzed = runs.findFirstByProjectIdAndKindOrderByCreatedAtDescIdDesc(projectId, RunKind.ANALYSIS)
            .map(r -> r.getStatus() == RunStatus.SUCCEEDED).orElse(false);
        if (analyzed) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.NOT_READY, "비교 분석이 끝난 뒤에는 연결을 바꿀 수 없어요.");
        }
    }

    private static String trimmed(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String t = raw.trim();
        return t.length() > URL_MAX ? null : t;
    }
}
