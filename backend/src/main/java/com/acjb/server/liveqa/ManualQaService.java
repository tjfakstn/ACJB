package com.acjb.server.liveqa;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.acjb.server.common.ApiException;
import com.acjb.server.common.ErrorCodes;
import com.acjb.server.domain.CaptureKind;
import com.acjb.server.domain.CaptureStatus;
import com.acjb.server.domain.EnvPlatform;
import com.acjb.server.domain.Environment;
import com.acjb.server.domain.IssueStatus;
import com.acjb.server.domain.LocationBasis;
import com.acjb.server.domain.ManualQaPin;
import com.acjb.server.domain.PageElement;
import com.acjb.server.domain.Member;
import com.acjb.server.domain.PlatformScope;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.QAIssue;
import com.acjb.server.domain.QaCapture;
import com.acjb.server.domain.Severity;
import com.acjb.server.domain.StatusHistory;
import com.acjb.server.dto.QaIssueResponse;
import com.acjb.server.recheck.EmbeddingSourcesChanged;
import com.acjb.server.repository.ManualQaPinRepository;
import com.acjb.server.repository.ProjectRepository;
import com.acjb.server.repository.QAIssueRepository;
import com.acjb.server.repository.QaCaptureRepository;
import com.acjb.server.repository.StatusHistoryRepository;

/**
 * 수동 QA 등록(AC31) — 배포 화면 위에서 찍은 위치로 이슈와 핀을 한 트랜잭션으로 저장한다.
 * screen_location·repro_steps는 찍은 위치(경로·좌표·화면 크기·배포 주소)에서 만든다 — 사람이 찍은 사실값이라 AC1의 필수 항목을 지어내지 않는다(N1).
 * 담당 팀이 없으므로 알림은 만들지 않는다(AC3). 같은 client_request_id면 처음 만든 이슈를 그대로 돌려준다(재시도 멱등).
 */
@Service
public class ManualQaService {

    static final int TITLE_MAX = 100;
    static final int DESCRIPTION_MAX = 2000;
    static final int RESULT_MAX = 1000;

    public record Created(QaIssueResponse issue, boolean created) {
    }

    private final ProjectRepository projects;
    private final QAIssueRepository issues;
    private final ManualQaPinRepository pins;
    private final QaCaptureRepository captures;
    private final StatusHistoryRepository histories;
    private final DeployTargetService deployTargets;
    private final ScreenLinkService screenLinks;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ManualQaService(ProjectRepository projects, QAIssueRepository issues, ManualQaPinRepository pins, QaCaptureRepository captures,
                           StatusHistoryRepository histories, DeployTargetService deployTargets, ScreenLinkService screenLinks,
                           ApplicationEventPublisher events,
                           TransactionTemplate tx, Clock clock) {
        this.projects = projects;
        this.issues = issues;
        this.pins = pins;
        this.captures = captures;
        this.histories = histories;
        this.deployTargets = deployTargets;
        this.screenLinks = screenLinks;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    public Created create(Long projectId, LiveQaRequests.ManualQaRequest req, Member reporter) {
        return create(projectId, req, reporter, false);
    }

    /**
     * overlay(2026-11-24): 배포 사이트 위 QAting 툴바에서 온 등록 — 로그인한 화면은 서버가 다시 찍을 수 없어
     * 브라우저가 찍은 증거 캡처(첨부)를 받고, 사람이 적은 재현 절차(로그인 등)를 위치에서 만든 절차 뒤에 덧붙인다.
     * QAting 화면 안 등록(overlay=false)의 규칙(골든 mq-009)은 그대로다.
     */
    public Created create(Long projectId, LiveQaRequests.ManualQaRequest req, Member reporter, boolean overlay) {
        if (req == null) {
            throw ApiException.badRequest("요청 본문이 비어 있습니다.");
        }
        // 1) 값 검사 — 여기서 하나라도 걸리면 아무것도 저장하지 않는다(N2)
        String requestId = LiveQaValidation.requestId(req.clientRequestId());
        String title = LiveQaValidation.required(req.title(), "title", TITLE_MAX);
        String description = LiveQaValidation.required(req.description(), "description", DESCRIPTION_MAX);
        if (req.locationBasis() == null) {
            throw ApiException.badRequest("필수 항목이 비어 있습니다: location_basis");
        }
        LocationBasis basis = LiveQaValidation.parseEnum(LocationBasis.class, req.locationBasis(), "location_basis");
        // 위치 없이 등록(none, 2026-11-10) — 배포 화면이 없어도 된다. 좌표가 없으니 재현 절차는 사람이 적는다(AC1·N1)
        boolean none = basis == LocationBasis.NONE;
        // 서버 캡처 위에서 등록(2026-11-20) — 화면 크기는 캡처에서 정하고, 좌표는 페이지 맨 위 기준이라 화면 높이를 넘을 수 있다
        boolean pageCapture = basis == LocationBasis.PAGE_CAPTURE;
        // 스니펫으로 실시간 화면의 요소를 골랐다(2026-11-20) — 좌표는 페이지 맨 위 기준이라 화면 높이를 넘을 수 있다
        boolean liveElement = basis == LocationBasis.LIVE_ELEMENT;
        PageElement pickedElement = null;
        if (liveElement) {
            pickedElement = LiveQaValidation.element(req.element());
        } else if (req.element() != null) {
            throw ApiException.badRequest("element는 스니펫으로 고른 위치(live_element)일 때만 보냅니다.");
        }
        String path;
        LiveQaValidation.Viewport viewport = null;
        Integer x = null;
        Integer y = null;
        List<String> userSteps = List.of();
        if (none) {
            if (req.anchor() != null || req.viewport() != null) {
                throw ApiException.badRequest("위치 없이 등록(location_basis none)할 때는 anchor·viewport를 보내지 않습니다.");
            }
            // 앱 화면(aos·ios)은 URL 경로가 없다 — 사람이 알려 준 화면 이름("홈 화면")을 그대로 받는다(2026-11-11)
            boolean appScreen = req.platform() != null && List.of("aos", "ios").contains(req.platform().trim().toLowerCase(java.util.Locale.ROOT));
            path = req.pagePath() == null || req.pagePath().isBlank() ? null
                : appScreen && !req.pagePath().trim().startsWith("/") ? LiveQaValidation.appScreen(req.pagePath()) : LiveQaValidation.pagePath(req.pagePath());
            userSteps = LiveQaValidation.reproSteps(req.reproSteps());
        } else {
            if (req.reproSteps() != null && !req.reproSteps().isEmpty()) {
                if (!(overlay && liveElement)) {
                    throw ApiException.badRequest("위치를 찍어 등록하면 재현 절차는 위치에서 만듭니다 — repro_steps는 location_basis none일 때만 보냅니다.");
                }
                userSteps = LiveQaValidation.reproSteps(req.reproSteps());
            }
            path = LiveQaValidation.pagePath(req.pagePath());
            if (pageCapture) {
                if (req.viewport() != null) {
                    throw ApiException.badRequest("캡처 위에서 등록(page_capture)할 때는 viewport를 보내지 않습니다 — 캡처에서 정합니다.");
                }
            } else {
                viewport = LiveQaValidation.viewport(req.viewport());
            }
            if (req.anchor() == null || req.anchor().xPx() == null || req.anchor().yPx() == null) {
                throw ApiException.badRequest("필수 항목이 비어 있습니다: anchor");
            }
            x = req.anchor().xPx();
            y = req.anchor().yPx();
            if (x < 0 || y < 0) {
                throw ApiException.badRequest("anchor는 0 이상이어야 합니다.");
            }
            if (liveElement) {
                if (x >= viewport.width()) {
                    throw ApiException.badRequest("anchor가 viewport 너비(" + viewport.width() + ") 밖에 있습니다.");
                }
                if (!pickedElement.contains(x, y)) {
                    throw ApiException.badRequest("anchor가 element.box 안에 있어야 합니다 — 고른 요소 안의 점이어야 합니다.");
                }
            } else if (viewport != null && (x >= viewport.width() || y >= viewport.height())) {
                throw ApiException.badRequest("anchor가 viewport(" + viewport.width() + "×" + viewport.height() + ") 밖에 있습니다.");
            }
        }
        Long captureId = null;
        if (req.captureId() != null && !req.captureId().isBlank()) {
            captureId = LiveQaValidation.idOrNull(req.captureId());
            if (captureId == null) {
                throw ApiException.badRequest("capture_id가 이 프로젝트의 캡처가 아닙니다.");
            }
            if (req.captureStatus() != null) {
                throw ApiException.badRequest("capture_id가 있으면 capture_status를 보내지 않습니다 — 캡처 종류로 정합니다.");
            }
        }
        if (basis == LocationBasis.CAPTURE_IMAGE && captureId == null) {
            throw ApiException.badRequest("첨부 이미지 위에서 찍은 위치(capture_image)는 capture_id가 필요합니다.");
        }
        if (pageCapture && captureId == null) {
            throw ApiException.badRequest("캡처 위에서 찍은 위치(page_capture)는 capture_id가 필요합니다.");
        }
        CaptureStatus requestedStatus = CaptureStatus.NONE;
        if (captureId == null && req.captureStatus() != null) {
            requestedStatus = LiveQaValidation.parseEnum(CaptureStatus.class, req.captureStatus(), "capture_status");
            if (requestedStatus != CaptureStatus.FAILED && requestedStatus != CaptureStatus.NONE) {
                throw ApiException.badRequest("capture_id 없이 보낼 수 있는 capture_status는 failed·none뿐입니다.");
            }
        }
        Severity severity = req.severity() == null || req.severity().isBlank() ? null
            : LiveQaValidation.parseEnum(Severity.class, req.severity(), "severity");
        // 발생 환경(앱 지원, 2026-11-11) — 보내지 않으면 예전과 같이 웹·공통. 범위가 한 플랫폼이면 발생 환경도 그 플랫폼이어야 한다
        EnvPlatform platform = req.platform() == null || req.platform().isBlank() ? EnvPlatform.WEB
            : LiveQaValidation.parseEnum(EnvPlatform.class, req.platform(), "platform");
        PlatformScope platformScope = req.platformScope() == null || req.platformScope().isBlank() ? PlatformScope.COMMON
            : LiveQaValidation.parseEnum(PlatformScope.class, req.platformScope(), "platform_scope");
        if (platformScope != PlatformScope.COMMON && !platformScope.name().equals(platform.name())) {
            throw ApiException.badRequest("platform_scope가 " + platformScope.name().toLowerCase(java.util.Locale.ROOT)
                + "이면 platform도 같은 플랫폼이어야 합니다.");
        }
        String expected = LiveQaValidation.optional(req.expectedResult(), "expected_result", RESULT_MAX);
        String actual = LiveQaValidation.optional(req.actualResult(), "actual_result", RESULT_MAX);
        // 기준 프레임(AC33)은 사람이 확인해 보낸 값만 — 없으면 경로로 골라 넣지 않는다. 보냈다면 근거 있는(최근 분석이 짝지은) 프레임이어야 한다
        ScreenLinkService.AnalyzedFrame frame = null;
        if (req.figmaFrameId() != null && !req.figmaFrameId().isBlank()) {
            String frameId = req.figmaFrameId().trim();
            frame = screenLinks.analyzedFrame(projectId, frameId)
                .orElseThrow(() -> ApiException.badRequest("figma_frame_id가 최근 비교 분석의 매핑에 없는 프레임입니다: " + frameId));
        }
        if (none && path == null && frame == null) {
            throw ApiException.badRequest("위치 없이 등록할 때는 화면을 알려 주세요 — page_path나 figma_frame_id 중 하나 이상이 필요합니다.");
        }

        // 2) 재시도면 처음 것을 돌려준다(검사를 통과한 같은 요청만 여기까지 온다)
        Optional<Created> replay = replay(projectId, requestId);
        if (replay.isPresent()) {
            return replay.get();
        }

        // 3) 배포 주소는 트랜잭션 밖에서 정한다(GitHub 호출이 있을 수 있다). 실시간 화면 위에서 찍었다면 주소가 있어야 한다
        String pageUrl = null;
        if (basis == LocationBasis.LIVE_OVERLAY || liveElement) {
            DeployTargetService.Target target = deployTargets.resolve(projectId);
            if (target.url() == null) {
                throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.NOT_READY, "배포 주소가 없어 배포 화면 위치로 등록할 수 없어요.");
            }
            pageUrl = LiveQaValidation.join(target.url(), path);
        }
        final Long finalCaptureId = captureId;
        final CaptureStatus fallbackStatus = requestedStatus;
        final String finalPageUrl = pageUrl;
        final ScreenLinkService.AnalyzedFrame finalFrame = frame;
        final String finalPath = path;
        final LiveQaValidation.Viewport finalViewport = viewport;
        final Integer finalX = x;
        final Integer finalY = y;
        final List<String> finalUserSteps = userSteps;
        final PageElement finalPicked = pickedElement;

        return tx.execute(status -> {
            // 프로젝트 행을 잠가 핀 번호 발급과 요청 id 중복 확인을 줄 세운다
            Project project = projects.findByIdForUpdate(projectId)
                .orElseThrow(() -> ApiException.notFound("프로젝트를 찾을 수 없습니다."));
            Optional<ManualQaPin> again = pins.findByProjectIdAndClientRequestId(projectId, requestId);
            if (again.isPresent()) {
                return new Created(response(again.get()), false);
            }
            QaCapture capture = null;
            CaptureStatus captureStatus = fallbackStatus;
            if (finalCaptureId != null) {
                capture = captures.findByIdAndProjectId(finalCaptureId, projectId)
                    .orElseThrow(() -> ApiException.badRequest("capture_id가 이 프로젝트의 캡처가 아닙니다."));
                captureStatus = capture.getKind() == CaptureKind.AUTO ? CaptureStatus.CAPTURED : CaptureStatus.USER_ATTACHED;
            }

            String screenLocation;
            List<String> steps;
            String pinPageUrl = finalPageUrl;
            LiveQaValidation.Viewport pinViewport = finalViewport;
            PageElement element = null;
            if (basis == LocationBasis.PAGE_CAPTURE) {
                // 서버가 이 프로젝트의 배포 화면을 전체 페이지로 찍은 캡처여야 한다 — 첨부 이미지나 첫 화면만 찍은 캡처로는 페이지 좌표를 정할 수 없다
                if (capture.getKind() != CaptureKind.AUTO || !capture.isFullPage() || capture.getViewportHeight() == null) {
                    throw ApiException.badRequest("page_capture는 전체 페이지로 찍은 서버 캡처(full_page)가 필요합니다.");
                }
                if (capture.getPageUrl() == null || !capture.getPageUrl().endsWith(finalPath)) {
                    throw ApiException.badRequest("page_path가 캡처한 화면의 경로와 다릅니다.");
                }
                if (finalX >= capture.getWidth() || finalY >= capture.getHeight()) {
                    throw ApiException.badRequest("anchor가 캡처(" + capture.getWidth() + "×" + capture.getHeight() + ") 밖에 있습니다.");
                }
                pinPageUrl = capture.getPageUrl();
                pinViewport = new LiveQaValidation.Viewport(capture.getWidth(), capture.getHeight());
                element = PageElements.at(PageElements.read(capture.getElements()), finalX, finalY);
                String screen = capture.getWidth() + "×" + capture.getViewportHeight();
                screenLocation = truncate(finalPath + " (x " + finalX + ", y " + finalY + " · 페이지 맨 위 기준 · " + screen + " 화면)", 255);
                steps = List.of(truncate("'" + pinPageUrl + "'를 " + screen + " 화면으로 연다", 500),
                    truncate("페이지 맨 위에서 (x " + finalX + ", y " + finalY + ") 위치" + describe(element) + "를 확인한다", 500));
            } else if (basis == LocationBasis.LIVE_ELEMENT) {
                // 증거 캡처(선택)는 서버가 찍은 이 프로젝트의 배포 화면이어야 한다. 전체 페이지면 핀 화면 크기를 캡처 크기로 둬 캡처 위에도 그대로 그린다
                if (capture != null && capture.getKind() != CaptureKind.AUTO && !overlay) {
                    throw ApiException.badRequest("live_element의 capture_id는 서버가 찍은 배포 화면 캡처여야 합니다.");
                }
                if (capture != null && capture.isFullPage()) {
                    pinViewport = new LiveQaValidation.Viewport(capture.getWidth(), capture.getHeight());
                }
                element = finalPicked;
                String screen = finalViewport.width() + "×" + finalViewport.height();
                screenLocation = truncate(finalPath + " (x " + finalX + ", y " + finalY + " · 페이지 맨 위 기준 · " + screen + " 화면)", 255);
                List<String> located = List.of(truncate("'" + finalPageUrl + "'를 " + screen + " 화면으로 연다", 500),
                    truncate("페이지 맨 위에서 (x " + finalX + ", y " + finalY + ") 위치" + describe(element) + "를 확인한다", 500));
                // 툴바에서 사람이 적은 절차(로그인 등)는 위치에서 만든 절차 뒤에 붙인다 — 지어내지 않고 적은 그대로(N1)
                steps = finalUserSteps.isEmpty() ? located
                    : java.util.stream.Stream.concat(located.stream(), finalUserSteps.stream()).toList();
            } else if (basis == LocationBasis.NONE) {
                // 위치가 없으면 사람이 알려 준 화면(경로, 없으면 고른 프레임 이름)과 사람이 적은 재현 절차 그대로
                screenLocation = truncate(finalPath != null ? finalPath : finalFrame.frameName(), 255);
                steps = finalUserSteps;
            } else {
                String where = "(x " + finalX + ", y " + finalY + " · " + finalViewport.width() + "×" + finalViewport.height() + ")";
                screenLocation = truncate(finalPath + " " + where, 255);
                steps = basis == LocationBasis.LIVE_OVERLAY
                    ? List.of(truncate("'" + finalPageUrl + "'를 " + finalViewport.width() + "×" + finalViewport.height() + " 화면으로 연다", 500),
                        "(x " + finalX + ", y " + finalY + ") 위치를 확인한다")
                    : List.of("첨부 이미지(캡처 " + finalCaptureId + ")를 연다", "(x " + finalX + ", y " + finalY + ") 위치를 확인한다");
            }

            String figmaLink = finalFrame == null ? null : figmaFrameUrl(project.getFigmaFileKey(), finalFrame.frameId());
            QAIssue issue = issues.save(new QAIssue(project, description, screenLocation, steps, platformScope,
                new Environment(platform, null, null, null), List.of(), figmaLink, null, null, reporter));
            StatusHistory first = histories.save(new StatusHistory(issue, IssueStatus.NEEDS_CHECK, reporter));
            int pinNumber = pins.maxPinNumber(projectId) + 1;
            ManualQaPin newPin = new ManualQaPin(project, issue, pinNumber, requestId, title, finalPath, pinPageUrl, finalX, finalY,
                pinViewport == null ? null : pinViewport.width(), pinViewport == null ? null : pinViewport.height(), basis, captureStatus,
                capture, severity, expected, actual,
                finalFrame == null ? null : finalFrame.frameId(), finalFrame == null ? null : finalFrame.frameName(), clock.instant());
            newPin.attachElement(element);
            ManualQaPin pin = pins.save(newPin);
            issues.flush();
            // 이슈는 재검증 범위 제안(AC20)의 검색 문서가 된다 — 일반 등록과 같다
            events.publishEvent(new EmbeddingSourcesChanged(projectId));
            return new Created(QaIssueResponse.of(issue, List.of(first), pin), true);
        });
    }

    private Optional<Created> replay(Long projectId, String requestId) {
        return tx.execute(status -> pins.findByProjectIdAndClientRequestId(projectId, requestId)
            .map(pin -> new Created(response(pin), false)));
    }

    private QaIssueResponse response(ManualQaPin pin) {
        QAIssue issue = pin.getIssue();
        return QaIssueResponse.of(issue, histories.findByIssueIdOrderByChangedAtAscIdAsc(issue.getId()), pin);
    }

    /** 연결된 Figma 파일에서 그 프레임을 여는 주소 — 화면의 figmaFrameUrl과 같은 모양. 파일 연결이 없으면 남기지 않는다 */
    static String figmaFrameUrl(String fileKey, String frameId) {
        return fileKey == null ? null : "https://www.figma.com/design/" + fileKey + "/?node-id=" + frameId.replace(':', '-');
    }

    /** 재현 절차에 넣는 요소 설명 — 「글자」 요소(CSS 경로). 요소가 없으면 빈 문자열 */
    private static String describe(PageElement element) {
        if (element == null) {
            return "";
        }
        return "의 " + (element.text().isBlank() ? "" : "「" + truncate(element.text(), 40) + "」 ") + "요소(" + truncate(element.selector(), 200) + ")";
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
