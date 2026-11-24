package com.acjb.server.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.acjb.server.common.ApiException;
import com.acjb.server.common.ApiResponses;
import com.acjb.server.common.ErrorCodes;
import com.acjb.server.domain.Member;
import com.acjb.server.liveqa.CaptureService;
import com.acjb.server.liveqa.LiveQaRequests;
import com.acjb.server.liveqa.ManualQaService;
import com.acjb.server.liveqa.overlay.OverlayAuthFilter;
import com.acjb.server.liveqa.overlay.OverlayService;
import com.acjb.server.liveqa.overlay.OverlayTokens;
import com.acjb.server.repository.MemberRepository;

/**
 * 배포 사이트 위 QAting 툴바(오버레이)가 부르는 API(2026-11-24, docs/design-live-overlay.md 5절).
 * 인증은 OverlayAuthFilter(입장권·출처·멤버 확인)가 앞에서 끝낸다. 입장권으로는 이 네 가지만 할 수 있다 — 상태 변경·삭제는 불가.
 */
@RestController
@RequestMapping("/api/v1/overlay")
public class OverlayController {

    private final OverlayService overlay;
    private final CaptureService captures;
    private final ManualQaService manualQa;
    private final MemberRepository members;

    public OverlayController(OverlayService overlay, CaptureService captures, ManualQaService manualQa, MemberRepository members) {
        this.overlay = overlay;
        this.captures = captures;
        this.manualQa = manualQa;
        this.members = members;
    }

    @GetMapping("/context")
    public Map<String, Object> context(@RequestAttribute(OverlayAuthFilter.ATTR) OverlayTokens.Ticket ticket) {
        return ApiResponses.ok(overlay.context(ticket));
    }

    @GetMapping("/pins")
    public Map<String, Object> pins(@RequestAttribute(OverlayAuthFilter.ATTR) OverlayTokens.Ticket ticket,
                                    @RequestParam(name = "path", required = false) String path) {
        return ApiResponses.ok(overlay.pins(ticket, path));
    }

    @PostMapping(path = "/captures/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> upload(@RequestAttribute(OverlayAuthFilter.ATTR) OverlayTokens.Ticket ticket,
                                      @RequestParam(name = "file", required = false) MultipartFile file) {
        return ApiResponses.ok(captures.upload(ticket.projectId(), file, member(ticket)));
    }

    /** 위치 기준은 서버가 live_element로 고정한다 — 툴바는 실시간 화면의 요소를 골라서만 등록한다 */
    @PostMapping("/manual-qa")
    public ResponseEntity<Map<String, Object>> createManualQa(@RequestAttribute(OverlayAuthFilter.ATTR) OverlayTokens.Ticket ticket,
                                                              @RequestBody LiveQaRequests.ManualQaRequest r) {
        if (r == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED, "요청 본문이 필요합니다.");
        }
        LiveQaRequests.ManualQaRequest fixed = new LiveQaRequests.ManualQaRequest(r.clientRequestId(), r.title(), r.description(),
            r.pagePath(), r.anchor(), r.viewport(), "live_element", r.captureId(), r.captureStatus(), r.severity(), r.expectedResult(),
            r.actualResult(), r.figmaFrameId(), r.reproSteps(), "web", r.platformScope(), r.element());
        ManualQaService.Created result = manualQa.create(ticket.projectId(), fixed, member(ticket), true);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(ApiResponses.ok(result.issue()));
    }

    private Member member(OverlayTokens.Ticket ticket) {
        return members.findById(ticket.memberId())
            .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.FORBIDDEN, "이 프로젝트의 멤버가 아니에요."));
    }
}
