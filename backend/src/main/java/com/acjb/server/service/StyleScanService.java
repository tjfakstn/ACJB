package com.acjb.server.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.acjb.server.common.ApiException;
import com.acjb.server.common.ErrorCodes;
import com.acjb.server.domain.Member;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.StyleScan;
import com.acjb.server.dto.StyleScanDtos;
import com.acjb.server.figma.FigmaClient;
import com.acjb.server.repository.FigmaFrameRepository;
import com.acjb.server.repository.ProjectRepository;
import com.acjb.server.repository.StyleScanRepository;
import com.acjb.server.scan.StyleScanRequested;
import com.acjb.server.scan.TargetUrlPolicy;

/** 단일 컴포넌트 scan 요청·상태 조회(SPEC 4절·5절). 실제 비교는 StyleScanJob이 커밋 뒤에 한다 */
@Service
public class StyleScanService {

    /** FigmaSyncService와 같은 노드 id 형식(예 84:759, 인스턴스 하위 I1:2;3:4) */
    private static final Pattern NODE_ID = Pattern.compile("[A-Za-z0-9:;-]{1,50}");
    private static final int SELECTOR_MAX = 500;

    private final StyleScanRepository scans;
    private final FigmaFrameRepository frames;
    private final ProjectRepository projects;
    private final FigmaClient figma;
    private final TargetUrlPolicy urlPolicy;
    private final ApplicationEventPublisher events;

    public StyleScanService(StyleScanRepository scans, FigmaFrameRepository frames, ProjectRepository projects,
                            FigmaClient figma, TargetUrlPolicy urlPolicy, ApplicationEventPublisher events) {
        this.scans = scans;
        this.frames = frames;
        this.projects = projects;
        this.figma = figma;
        this.urlPolicy = urlPolicy;
        this.events = events;
    }

    @Transactional
    public StyleScanDtos.Accepted request(Long projectId, StyleScanDtos.ScanRequest req, Member requester) {
        String frameId = trim(req == null ? null : req.frameId());
        String nodeId = trim(req == null ? null : req.nodeId());
        String targetUrl = trim(req == null ? null : req.targetUrl());
        String selector = trim(req == null ? null : req.selector());
        List<String> missing = new ArrayList<>();
        if (frameId == null) missing.add("frame_id");
        if (nodeId == null) missing.add("node_id");
        if (targetUrl == null) missing.add("target_url");
        if (selector == null) missing.add("selector");
        if (!missing.isEmpty()) {
            throw ApiException.badRequest("필수 항목이 비어 있습니다: " + String.join(", ", missing));
        }
        if (!NODE_ID.matcher(frameId).matches()) {
            throw ApiException.badRequest("frame_id 형식이 올바르지 않습니다(예: 84:759).");
        }
        if (!NODE_ID.matcher(nodeId).matches()) {
            throw ApiException.badRequest("node_id 형식이 올바르지 않습니다(예: 84:760).");
        }
        if (selector.length() > SELECTOR_MAX) {
            throw ApiException.badRequest("selector는 " + SELECTOR_MAX + "자 이하여야 합니다.");
        }
        try {
            urlPolicy.check(targetUrl);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        if (!figma.isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.FIGMA_UNAVAILABLE,
                "Figma 연동이 설정되지 않았습니다(FIGMA_ACCESS_TOKEN).");
        }
        Project project = projects.findById(projectId).orElseThrow(() -> ApiException.notFound("프로젝트를 찾을 수 없습니다."));
        if (frames.findByProjectIdAndFrameId(projectId, frameId).isEmpty()) {
            throw ApiException.badRequest("frame_id는 figma/sync로 이 프로젝트에 동기화된 프레임이어야 합니다.");
        }
        StyleScan scan = scans.save(new StyleScan(project, frameId, nodeId, targetUrl, selector, requester));
        events.publishEvent(new StyleScanRequested(scan.getId()));
        return StyleScanDtos.Accepted.of(scan);
    }

    @Transactional(readOnly = true)
    public StyleScanDtos.Status status(Long projectId, String scanId) {
        Long id;
        try {
            id = Long.valueOf(scanId.trim());
        } catch (NumberFormatException e) {
            id = null;
        }
        StyleScan scan = id == null ? null : scans.findByIdAndProjectId(id, projectId).orElse(null);
        if (scan == null) {
            throw ApiException.notFound("scan 작업을 찾을 수 없습니다.");
        }
        return StyleScanDtos.Status.of(scan);
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
