package com.acjb.server.liveqa;

import java.io.IOException;
import java.time.Clock;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.acjb.server.common.ApiException;
import com.acjb.server.common.ErrorCodes;
import com.acjb.server.domain.CaptureKind;
import com.acjb.server.domain.Member;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.QaCapture;
import com.acjb.server.repository.ProjectRepository;
import com.acjb.server.repository.QaCaptureRepository;
import com.acjb.server.scan.TargetUrlPolicy;

/**
 * 배포 화면 캡처와 이미지 첨부(AC30). 서버 브라우저 작업은 트랜잭션 밖에서 하고, 찍은 뒤에만 저장한다 — 실패하면 아무것도 남기지 않는다.
 */
@Service
public class CaptureService {

    public static final int MAX_UPLOAD_BYTES = 5 * 1024 * 1024;

    private final DeployTargetService deployTargets;
    private final PageCapturer capturer;
    private final TargetUrlPolicy policy;
    private final ProjectRepository projects;
    private final QaCaptureRepository captures;
    private final TransactionTemplate tx;
    private final Clock clock;

    public CaptureService(DeployTargetService deployTargets, PageCapturer capturer, TargetUrlPolicy policy, ProjectRepository projects,
                          QaCaptureRepository captures, TransactionTemplate tx, Clock clock) {
        this.deployTargets = deployTargets;
        this.capturer = capturer;
        this.policy = policy;
        this.projects = projects;
        this.captures = captures;
        this.tx = tx;
        this.clock = clock;
    }

    public QaCaptureView capture(Long projectId, LiveQaRequests.CaptureRequest req, Member requester) {
        if (req == null) {
            throw ApiException.badRequest("요청 본문이 비어 있습니다.");
        }
        String path = LiveQaValidation.pagePath(req.pagePath());
        LiveQaValidation.Viewport viewport = LiveQaValidation.viewport(req.viewport());
        DeployTargetService.Target target = deployTargets.resolve(projectId);
        if (target.url() == null) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.NOT_READY, "배포 주소가 없어요. 연결 관리에서 배포 주소를 입력해 주세요.");
        }
        String pageUrl = LiveQaValidation.join(target.url(), path);
        if (!policy.allows(pageUrl)) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCodes.CAPTURE_FAILED, "허용되지 않은 주소라 캡처하지 않았어요.");
        }
        byte[] png;
        try {
            png = capturer.capture(pageUrl, viewport.width(), viewport.height());
        } catch (CaptureFailedException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCodes.CAPTURE_FAILED, e.getMessage());
        } catch (com.acjb.server.scan.BrowserSlots.Busy e) {
            // 동시에 띄우는 브라우저 수를 넘었다(2026-11-11) — 서버 문제가 아니라 잠시 뒤 다시 하면 된다
            throw com.acjb.server.service.UsageLimits.limited(e.getMessage());
        }
        return save(projectId, requester, CaptureKind.AUTO, "image/png", pageUrl, viewport.width(), viewport.height(), png);
    }

    public QaCaptureView upload(Long projectId, MultipartFile file, Member requester) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("첨부할 이미지(file)가 없습니다.");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw ApiException.badRequest("이미지는 5MB 이하만 첨부할 수 있어요.");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("이미지를 읽지 못했어요.");
        }
        ImageFormats.Info info = ImageFormats.detect(bytes);
        if (info == null) {
            throw ApiException.badRequest("png·jpeg·webp 이미지만 첨부할 수 있어요.");
        }
        return save(projectId, requester, CaptureKind.UPLOAD, info.contentType(), null, info.width(), info.height(), bytes);
    }

    /** 이미지 바이트와 형식 — 다른 프로젝트의 캡처는 404(N10) */
    @Transactional(readOnly = true)
    public Image image(Long projectId, String captureId) {
        Long id = LiveQaValidation.idOrNull(captureId);
        QaCapture c = id == null ? null : captures.findByIdAndProjectId(id, projectId).orElse(null);
        if (c == null) {
            throw ApiException.notFound("캡처를 찾을 수 없습니다.");
        }
        return new Image(c.getContentType(), c.getImage());
    }

    public record Image(String contentType, byte[] bytes) {
    }

    private QaCaptureView save(Long projectId, Member requester, CaptureKind kind, String contentType, String pageUrl, int width,
                               int height, byte[] bytes) {
        return tx.execute(status -> {
            Project project = projects.findById(projectId).orElseThrow(() -> ApiException.notFound("프로젝트를 찾을 수 없습니다."));
            QaCapture saved = captures.save(new QaCapture(project, kind, contentType, pageUrl, width, height, bytes, requester,
                clock.instant()));
            return QaCaptureView.of(saved);
        });
    }
}
