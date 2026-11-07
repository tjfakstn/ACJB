package com.acjb.server.service;

import java.time.Clock;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.acjb.server.common.ApiException;
import com.acjb.server.common.ErrorCodes;
import com.acjb.server.domain.Member;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.RunKind;
import com.acjb.server.domain.RunStatus;
import com.acjb.server.dto.ProjectDetailResponse;
import com.acjb.server.figma.FigmaApiException;
import com.acjb.server.figma.FigmaClient;
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

    private static final Pattern FIGMA_URL = Pattern.compile("^(?:https?://)?(?:www\\.)?figma\\.com/(?:design|file)/([A-Za-z0-9]+)(?:[/?#].*)?$");
    private static final Pattern GITHUB_URL = Pattern.compile("^(?:https?://)?(?:www\\.)?github\\.com/([A-Za-z0-9-]+)/([A-Za-z0-9._-]+?)(?:\\.git)?/?(?:[?#].*)?$");
    private static final int URL_MAX = 500;
    private static final HttpStatus UNPROCESSABLE = HttpStatus.valueOf(422);

    private final ProjectRepository projects;
    private final ProjectRunRepository runs;
    private final FigmaClient figma;
    private final GithubClient github;
    private final Clock clock;

    public ConnectionService(ProjectRepository projects, ProjectRunRepository runs, FigmaClient figma, GithubClient github, Clock clock) {
        this.projects = projects;
        this.runs = runs;
        this.figma = figma;
        this.github = github;
        this.clock = clock;
    }

    @Transactional
    public ProjectDetailResponse.FigmaConnection connectFigma(Long projectId, String rawUrl, Member requester) {
        Project project = ownedProject(projectId, requester);
        String url = trimmed(rawUrl);
        Matcher m = url == null ? null : FIGMA_URL.matcher(url);
        if (m == null || !m.matches()) {
            throw ApiException.badRequest("올바른 Figma 파일 링크가 아니에요 (예: https://www.figma.com/design/파일키/이름)");
        }
        rejectAfterAnalysis(projectId);
        if (!figma.isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.FIGMA_UNAVAILABLE, "서버에 Figma 연동이 설정되지 않았습니다.");
        }
        String fileKey = m.group(1);
        String fileName;
        try {
            fileName = figma.fileName(fileKey);
        } catch (FigmaApiException e) {
            log.warn("Figma 파일 확인 실패: status={} message={}", e.status(), e.getMessage());
            if (e.status() == 403) {
                throw new ApiException(UNPROCESSABLE, ErrorCodes.CONNECTION_FORBIDDEN,
                    "Figma 파일 보기 권한이 없어요. 파일 소유자에게 보기 권한을 요청한 뒤 다시 확인해 주세요.");
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCodes.CONNECTION_FAILED,
                "파일을 찾을 수 없거나 Figma 응답이 지연되고 있어요. 링크를 확인한 뒤 다시 시도해 주세요.");
        }
        Instant now = clock.instant();
        project.connectFigma(url, fileKey, fileName, now);
        return new ProjectDetailResponse.FigmaConnection(url, fileKey, fileName, now);
    }

    @Transactional
    public ProjectDetailResponse.GithubConnection connectGithub(Long projectId, String rawUrl, Member requester) {
        Project project = ownedProject(projectId, requester);
        String url = trimmed(rawUrl);
        Matcher m = url == null ? null : GITHUB_URL.matcher(url);
        if (m == null || !m.matches()) {
            throw ApiException.badRequest("올바른 GitHub 저장소 링크가 아니에요 (예: https://github.com/조직/저장소)");
        }
        rejectAfterAnalysis(projectId);
        GithubClient.RepoInfo repo;
        try {
            repo = github.repository(m.group(1), m.group(2));
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
        Instant now = clock.instant();
        project.connectGithub(url, repo.fullName(), repo.defaultBranch(), now);
        return new ProjectDetailResponse.GithubConnection(url, repo.fullName(), repo.defaultBranch(), now);
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
