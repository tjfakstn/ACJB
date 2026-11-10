package com.acjb.server.liveqa;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.acjb.server.common.ApiException;
import com.acjb.server.domain.Member;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.RunKind;
import com.acjb.server.domain.RunStatus;
import com.acjb.server.domain.ScreenFrameLink;
import com.acjb.server.repository.DesignCodeMappingRepository;
import com.acjb.server.repository.ProjectRepository;
import com.acjb.server.repository.ProjectRunRepository;
import com.acjb.server.repository.ScreenFrameLinkRepository;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 배포 화면 경로 ↔ Figma 프레임 연결(AC33). 연결할 수 있는 프레임은 최근 성공한 비교 분석이 짝지은 프레임뿐이다 —
 * 근거 있는 프레임 id만 받고, 경로는 사람이 지정한 값만 둔다(코드에서 추정하지 않는다, N19)
 */
@Service
public class ScreenLinkService {

    private static final Pattern FRAME_ID = Pattern.compile("\\d+:\\d+");

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record LinkView(String frameId, String frameName, String pagePath, Instant updatedAt) {
        static LinkView of(ScreenFrameLink l) {
            return new LinkView(l.getFrameId(), l.getFrameName(), l.getPagePath(), l.getUpdatedAt());
        }
    }

    public record LinkList(List<LinkView> items) {
    }

    /** 최근 성공한 분석에 있는 프레임 — 이름은 그 분석 때 값 */
    public record AnalyzedFrame(String frameId, String frameName) {
    }

    private final ProjectRepository projects;
    private final ProjectRunRepository runs;
    private final DesignCodeMappingRepository mappings;
    private final ScreenFrameLinkRepository links;
    private final Clock clock;

    public ScreenLinkService(ProjectRepository projects, ProjectRunRepository runs, DesignCodeMappingRepository mappings,
                             ScreenFrameLinkRepository links, Clock clock) {
        this.projects = projects;
        this.runs = runs;
        this.mappings = mappings;
        this.links = links;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public LinkList list(Long projectId) {
        return new LinkList(links.findByProjectIdOrderByPagePathAscFrameNameAscFrameIdAsc(projectId).stream().map(LinkView::of).toList());
    }

    /** PUT — 검사 순서: 형식(400) → 권한(403) → 프레임(400). 어디서 거부돼도 바꾸지 않는다 */
    @Transactional
    public LinkView put(Long projectId, String frameId, String rawPath, Member requester) {
        requireFrameIdFormat(frameId);
        String path = normalize(rawPath);
        Project project = requireOwner(projectId, requester);
        AnalyzedFrame frame = analyzedFrame(projectId, frameId)
            .orElseThrow(() -> ApiException.badRequest("최근 비교 분석의 매핑에 없는 프레임입니다: " + frameId));
        Instant now = clock.instant();
        ScreenFrameLink link = links.findByProjectIdAndFrameId(projectId, frameId)
            .map(l -> {
                l.change(frame.frameName(), path, requester, now);
                return l;
            })
            .orElseGet(() -> links.save(new ScreenFrameLink(project, frameId, frame.frameName(), path, requester, now)));
        links.flush();
        return LinkView.of(link);
    }

    @Transactional
    public void delete(Long projectId, String frameId, Member requester) {
        requireFrameIdFormat(frameId);
        requireOwner(projectId, requester);
        links.findByProjectIdAndFrameId(projectId, frameId).ifPresent(links::delete);
    }

    /** 수동 QA 등록(AC33)에서도 쓴다 — 최근 성공한 분석이 짝지은 프레임이면 그 이름과 함께 */
    @Transactional(readOnly = true)
    public Optional<AnalyzedFrame> analyzedFrame(Long projectId, String frameId) {
        if (frameId == null || !FRAME_ID.matcher(frameId).matches()) {
            return Optional.empty();
        }
        return runs.findFirstByProjectIdAndKindAndStatusOrderByFinishedAtDescIdDesc(projectId, RunKind.ANALYSIS, RunStatus.SUCCEEDED)
            .flatMap(run -> mappings.findFrame(run.getId(), frameId).stream().findFirst())
            .map(m -> new AnalyzedFrame(m.getFrameId(), m.getFrameName()));
    }

    private Project requireOwner(Long projectId, Member requester) {
        Project project = projects.findById(projectId).orElseThrow(() -> ApiException.notFound("프로젝트를 찾을 수 없습니다."));
        if (!project.getOwner().getId().equals(requester.getId())) {
            throw ApiException.forbidden("화면 경로 연결은 소유자만 바꿀 수 있습니다.");
        }
        return project;
    }

    private static void requireFrameIdFormat(String frameId) {
        if (frameId == null || !FRAME_ID.matcher(frameId).matches()) {
            throw ApiException.badRequest("프레임 id는 숫자:숫자 형식이어야 합니다.");
        }
    }

    /** /로 시작, 500자 이하, 공백·?·# 없음. 끝의 /는 뗀다(루트 /는 그대로) */
    static String normalize(String raw) {
        String path = LiveQaValidation.pagePath(raw);
        if (path.indexOf('?') >= 0 || path.indexOf('#') >= 0) {
            throw ApiException.badRequest("page_path에는 ?·#를 넣지 않습니다 — 경로만 적어 주세요.");
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }
}
