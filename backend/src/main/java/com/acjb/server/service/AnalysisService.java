package com.acjb.server.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.acjb.server.analysis.AnalysisRequested;
import com.acjb.server.common.ApiException;
import com.acjb.server.common.ErrorCodes;
import com.acjb.server.domain.DesignCodeMapping;
import com.acjb.server.domain.Member;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.ProjectRun;
import com.acjb.server.domain.RunKind;
import com.acjb.server.domain.RunStatus;
import com.acjb.server.dto.MappingListResponse;
import com.acjb.server.dto.ProjectRunView;
import com.acjb.server.repository.DesignCodeMappingRepository;
import com.acjb.server.repository.ProjectRepository;
import com.acjb.server.repository.ProjectRunRepository;

import tools.jackson.databind.json.JsonMapper;

/**
 * AC22 비교 분석 시작·상태, AC27 매핑 조회. 시작 검사 순서: 소유자(403) → 연결 둘 다(409) → 진행 중·이미 성공(409).
 * 실제 분석은 커밋 뒤 AnalysisJob이 한다
 */
@Service
public class AnalysisService {

    private final ProjectRepository projects;
    private final ProjectRunRepository runs;
    private final DesignCodeMappingRepository mappings;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final JsonMapper json;

    public AnalysisService(ProjectRepository projects, ProjectRunRepository runs, DesignCodeMappingRepository mappings,
                           ApplicationEventPublisher events, Clock clock, JsonMapper json) {
        this.projects = projects;
        this.runs = runs;
        this.mappings = mappings;
        this.events = events;
        this.clock = clock;
        this.json = json;
    }

    /** 사용량 한도(2026-11-11) */
    @org.springframework.beans.factory.annotation.Autowired
    private UsageLimits limits;

    @Transactional
    public ProjectRunView start(Long projectId, Member requester) {
        Project project = projects.findByIdForUpdate(projectId).orElseThrow(() -> ApiException.notFound("프로젝트를 찾을 수 없습니다."));
        if (!project.getOwner().getId().equals(requester.getId())) {
            throw ApiException.forbidden("비교 분석은 프로젝트 소유자만 시작할 수 있어요.");
        }
        if (project.getFigmaFileKey() == null || project.getGithubRepo() == null) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.NOT_READY, "Figma와 GitHub를 모두 연결한 뒤에 시작할 수 있어요.");
        }
        if (runs.existsByProjectIdAndKindAndStatusIn(projectId, RunKind.ANALYSIS, List.of(RunStatus.QUEUED, RunStatus.RUNNING))) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.NOT_READY, "비교 분석이 이미 진행 중이에요.");
        }
        if (runs.existsByProjectIdAndKindAndStatus(projectId, RunKind.ANALYSIS, RunStatus.SUCCEEDED)) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.NOT_READY, "비교 분석이 이미 끝났어요.");
        }
        limits.checkRunStart(projectId, RunKind.ANALYSIS);
        ProjectRun run = runs.save(new ProjectRun(project, RunKind.ANALYSIS, requester, clock.instant()));
        events.publishEvent(new AnalysisRequested(run.getId()));
        return ProjectRunView.of(run);
    }

    @Transactional(readOnly = true)
    public ProjectRunView latest(Long projectId) {
        return runs.findFirstByProjectIdAndKindOrderByCreatedAtDescIdDesc(projectId, RunKind.ANALYSIS).map(ProjectRunView::of).orElse(null);
    }

    @Transactional(readOnly = true)
    public MappingListResponse mappings(Long projectId) {
        ProjectRun run = runs.findFirstByProjectIdAndKindAndStatusOrderByFinishedAtDescIdDesc(projectId, RunKind.ANALYSIS, RunStatus.SUCCEEDED)
            .orElse(null);
        if (run == null) {
            return new MappingListResponse(null, null, List.of(), List.of());
        }
        // 정렬은 쿼리가 고정한다(프레임 이름·id → 프레임 짝 먼저 → 노드 이름·id)
        Map<String, MappingListResponse.FrameMapping> frames = new LinkedHashMap<>();
        for (DesignCodeMapping m : mappings.findOrdered(run.getId())) {
            if (m.getNodeId() == null) {
                frames.put(m.getFrameId(), new MappingListResponse.FrameMapping(m.getFrameId(), m.getFrameName(), m.getCodePath(), new ArrayList<>()));
            } else {
                MappingListResponse.FrameMapping f = frames.get(m.getFrameId());
                if (f != null) {
                    f.nodes().add(new MappingListResponse.NodeMapping(m.getNodeId(), m.getNodeName(), m.getCodePath(), m.getCodeSymbol()));
                }
            }
        }
        return new MappingListResponse(ProjectRunView.of(run), run.getCommitSha(), List.copyOf(frames.values()), unmapped(run));
    }

    /** 2026-11-10 이전 분석은 기록이 없어 빈 목록이다 */
    private List<MappingListResponse.UnmappedFrame> unmapped(ProjectRun run) {
        if (run.getUnmappedFrames() == null || run.getUnmappedFrames().isBlank()) {
            return List.of();
        }
        return List.of(json.readValue(run.getUnmappedFrames(), MappingListResponse.UnmappedFrame[].class));
    }
}
