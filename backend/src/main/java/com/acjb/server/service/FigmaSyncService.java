package com.acjb.server.service;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.acjb.server.common.ApiException;
import com.acjb.server.common.ErrorCodes;
import com.acjb.server.domain.FigmaSync;
import com.acjb.server.domain.Member;
import com.acjb.server.domain.Project;
import com.acjb.server.dto.FigmaDtos;
import com.acjb.server.dto.FigmaSyncRequest;
import com.acjb.server.figma.FigmaClient;
import com.acjb.server.figma.FigmaSyncRequested;
import com.acjb.server.repository.FigmaFrameRepository;
import com.acjb.server.repository.FigmaSyncRepository;
import com.acjb.server.repository.ProjectRepository;

/** Figma 동기화 요청·상태 조회·프레임 목록. 실제 Figma 호출은 FigmaSyncJob이 커밋 뒤에 한다 */
@Service
public class FigmaSyncService {

    /** Figma 파일 키는 영숫자. 경로에 들어가므로 형식을 먼저 막는다 */
    private static final Pattern FILE_KEY = Pattern.compile("[A-Za-z0-9_-]{1,100}");
    /** 노드 id 예: 84:759, 인스턴스 하위는 I1:2;3:4 */
    private static final Pattern NODE_ID = Pattern.compile("[A-Za-z0-9:;-]{1,50}");
    private static final int FRAME_IDS_MAX = 50;

    private final FigmaSyncRepository syncs;
    private final FigmaFrameRepository frames;
    private final ProjectRepository projects;
    private final FigmaClient figma;
    private final ApplicationEventPublisher events;

    public FigmaSyncService(FigmaSyncRepository syncs, FigmaFrameRepository frames, ProjectRepository projects,
                            FigmaClient figma, ApplicationEventPublisher events) {
        this.syncs = syncs;
        this.frames = frames;
        this.projects = projects;
        this.figma = figma;
        this.events = events;
    }

    @Transactional
    public FigmaDtos.SyncAccepted request(Long projectId, FigmaSyncRequest req, Member requester) {
        String fileKey = req == null ? null : req.figmaFileKey();
        if (fileKey == null || fileKey.isBlank()) {
            throw ApiException.badRequest("필수 항목이 비어 있습니다: figma_file_key");
        }
        if (!FILE_KEY.matcher(fileKey).matches()) {
            throw ApiException.badRequest("figma_file_key 형식이 올바르지 않습니다.");
        }
        List<String> frameIds = req.frameIds();
        if (frameIds != null) {
            // 빈 배열은 "전체"와 헷갈리므로 받지 않는다 — 전체 동기화는 frame_ids를 빼고 보낸다
            if (frameIds.isEmpty() || frameIds.size() > FRAME_IDS_MAX) {
                throw ApiException.badRequest("frame_ids는 1~" + FRAME_IDS_MAX + "개여야 합니다. 전체 동기화는 frame_ids를 빼고 보내세요.");
            }
            if (frameIds.stream().anyMatch(id -> id == null || !NODE_ID.matcher(id).matches())) {
                throw ApiException.badRequest("frame_ids에 형식이 올바르지 않은 id가 있습니다(예: 84:759).");
            }
            frameIds = frameIds.stream().distinct().toList();
        }
        if (!figma.isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.FIGMA_UNAVAILABLE,
                "Figma 연동이 설정되지 않았습니다(FIGMA_ACCESS_TOKEN).");
        }
        Project project = projects.findById(projectId).orElseThrow(() -> ApiException.notFound("프로젝트를 찾을 수 없습니다."));
        FigmaSync sync = syncs.save(new FigmaSync(project, fileKey, frameIds, requester));
        events.publishEvent(new FigmaSyncRequested(sync.getId()));
        return FigmaDtos.SyncAccepted.of(sync);
    }

    @Transactional(readOnly = true)
    public FigmaDtos.SyncStatus status(Long projectId, String syncId) {
        Long id;
        try {
            id = Long.valueOf(syncId.trim());
        } catch (NumberFormatException e) {
            id = null;
        }
        FigmaSync sync = id == null ? null : syncs.findByIdAndProjectId(id, projectId).orElse(null);
        if (sync == null) {
            throw ApiException.notFound("동기화 작업을 찾을 수 없습니다.");
        }
        return FigmaDtos.SyncStatus.of(sync);
    }

    @Transactional(readOnly = true)
    public FigmaDtos.FrameList frames(Long projectId) {
        return new FigmaDtos.FrameList(frames.findByProjectIdOrderByNameAscIdAsc(projectId).stream().map(FigmaDtos.Frame::of).toList());
    }
}
