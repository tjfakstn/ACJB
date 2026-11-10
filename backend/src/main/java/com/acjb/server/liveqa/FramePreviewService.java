package com.acjb.server.liveqa;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.acjb.server.common.ApiException;
import com.acjb.server.common.ErrorCodes;
import com.acjb.server.domain.Project;
import com.acjb.server.figma.FigmaApiException;
import com.acjb.server.figma.FigmaClient;
import com.acjb.server.repository.ProjectRepository;

/**
 * 자동 QA(첫 QA) 항목을 고르면 배포 화면 자리에 그 항목의 Figma 프레임을 보여 준다(AC32, 2026-11-10 설만수 결정).
 * 첫 QA 이슈에는 배포 경로가 없어 실제 배포 화면으로 옮길 수 없기 때문이다 — 경로를 추측하지 않는다.
 * Figma 렌더링 API는 호출 한도가 낮아(2026-11-07 429 대응) 같은 프레임·노드 결과를 잠시 기억해 둔다. 이미지 주소는 Figma가 준 임시 주소다.
 */
@Service
public class FramePreviewService {

    private static final Pattern NODE_ID = Pattern.compile("\\d+:\\d+");
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    /** 노드 상자는 프레임 기준 비율 — 프레임 밖으로 나간 부분은 잘라 낸다 */
    public record NodeBox(double xRatio, double yRatio, double wRatio, double hRatio) {
    }

    public record FramePreview(String frameId, String nodeId, String imageUrl, int width, int height, NodeBox nodeBox) {
    }

    private record Cached(FramePreview value, Instant until) {
    }

    private final ProjectRepository projects;
    private final FigmaClient figma;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public FramePreviewService(ProjectRepository projects, FigmaClient figma, Clock clock) {
        this.projects = projects;
        this.figma = figma;
        this.clock = clock;
    }

    public FramePreviewView preview(Long projectId, String frameId, String nodeId) {
        if (frameId == null || !NODE_ID.matcher(frameId).matches() || (nodeId != null && !NODE_ID.matcher(nodeId).matches())) {
            throw ApiException.badRequest("frame_id·node_id는 Figma 노드 id 형식(예: 12:34)이어야 합니다.");
        }
        Project project = projects.findById(projectId).orElseThrow(() -> ApiException.notFound("프로젝트를 찾을 수 없습니다."));
        String fileKey = project.getFigmaFileKey();
        if (fileKey == null) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.NOT_READY, "Figma 파일이 연결되지 않았어요.");
        }
        if (!figma.isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.FIGMA_UNAVAILABLE, "서버에 Figma 토큰이 설정되지 않았어요.");
        }
        // 캐시 키에 파일 키를 넣는다 — 다른 프로젝트·다른 파일의 결과를 섞지 않는다(N10)
        String key = fileKey + "|" + frameId + "|" + (nodeId == null ? "" : nodeId);
        Instant now = clock.instant();
        Cached hit = cache.get(key);
        if (hit != null && hit.until().isAfter(now)) {
            return FramePreviewView.of(hit.value());
        }
        FramePreview fresh;
        try {
            fresh = load(fileKey, frameId, nodeId);
        } catch (FigmaApiException e) {
            String message = e.status() == 429
                ? "Figma 요청 한도를 넘었어요. " + (e.retryAfterSeconds() > 0 ? e.retryAfterSeconds() + "초 뒤" : "잠시 뒤") + " 다시 시도해 주세요."
                : e.getMessage();
            throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCodes.CONNECTION_FAILED, message);
        }
        cache.put(key, new Cached(fresh, now.plus(CACHE_TTL)));
        return FramePreviewView.of(fresh);
    }

    private FramePreview load(String fileKey, String frameId, String nodeId) {
        List<String> ids = nodeId == null || nodeId.equals(frameId) ? List.of(frameId) : List.of(frameId, nodeId);
        Map<String, FigmaClient.Box> boxes = figma.bounds(fileKey, ids);
        FigmaClient.Box frame = boxes.get(frameId);
        if (frame == null) {
            throw ApiException.notFound("Figma에서 프레임을 찾을 수 없어요(삭제됐거나 이동했을 수 있어요).");
        }
        Optional<String> url = figma.renderUrl(fileKey, frameId, 1);
        if (url.isEmpty()) {
            throw ApiException.notFound("Figma가 이 프레임을 이미지로 그리지 못했어요.");
        }
        NodeBox nodeBox = null;
        FigmaClient.Box node = nodeId == null ? null : boxes.get(nodeId);
        if (node != null && !nodeId.equals(frameId)) {
            double x1 = clamp((node.x() - frame.x()) / frame.width());
            double y1 = clamp((node.y() - frame.y()) / frame.height());
            double x2 = clamp((node.x() + node.width() - frame.x()) / frame.width());
            double y2 = clamp((node.y() + node.height() - frame.y()) / frame.height());
            if (x2 > x1 && y2 > y1) {
                nodeBox = new NodeBox(round(x1), round(y1), round(x2 - x1), round(y2 - y1));
            }
        }
        return new FramePreview(frameId, nodeId, url.get(), (int) Math.round(frame.width()), (int) Math.round(frame.height()), nodeBox);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static double round(double v) {
        return Math.round(v * 10000) / 10000.0;
    }
}
