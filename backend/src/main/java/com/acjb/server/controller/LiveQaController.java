package com.acjb.server.controller;

import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.acjb.server.auth.CurrentMember;
import com.acjb.server.common.ApiResponses;
import com.acjb.server.liveqa.CaptureService;
import com.acjb.server.liveqa.DeployTargetService;
import com.acjb.server.liveqa.FramePreviewService;
import com.acjb.server.liveqa.LiveQaRequests;
import com.acjb.server.liveqa.ManualQaService;
import com.acjb.server.liveqa.ScreenLinkService;

/**
 * 실시간 QA 워크스페이스(SPEC AC29~AC33) — 배포 주소, 화면 캡처·첨부, 수동 QA 등록, 화면 경로 ↔ Figma 프레임 연결.
 * 이 경로는 Security 단계에서 프로젝트 멤버만 통과한다(AC12)
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class LiveQaController {

    private final DeployTargetService deployTargets;
    private final CaptureService captures;
    private final ManualQaService manualQa;
    private final FramePreviewService framePreviews;
    private final ScreenLinkService screenLinks;
    private final CurrentMember currentMember;

    public LiveQaController(DeployTargetService deployTargets, CaptureService captures, ManualQaService manualQa,
                            FramePreviewService framePreviews, ScreenLinkService screenLinks, CurrentMember currentMember) {
        this.screenLinks = screenLinks;
        this.framePreviews = framePreviews;
        this.deployTargets = deployTargets;
        this.captures = captures;
        this.manualQa = manualQa;
        this.currentMember = currentMember;
    }

    @GetMapping("/deploy-target")
    public Map<String, Object> deployTarget(@PathVariable Long projectId) {
        return ApiResponses.ok(deployTargets.get(projectId));
    }

    @PutMapping("/deploy-url")
    public Map<String, Object> updateDeployUrl(@PathVariable Long projectId, @RequestBody LiveQaRequests.DeployUrlRequest request,
                                               @AuthenticationPrincipal OAuth2User principal) {
        return ApiResponses.ok(deployTargets.update(projectId, request == null ? null : request.url(), currentMember.require(principal)));
    }

    @PostMapping("/captures")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> capture(@PathVariable Long projectId, @RequestBody LiveQaRequests.CaptureRequest request,
                                       @AuthenticationPrincipal OAuth2User principal) {
        return ApiResponses.ok(captures.capture(projectId, request, currentMember.require(principal)));
    }

    @PostMapping(path = "/captures/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> upload(@PathVariable Long projectId, @RequestParam(name = "file", required = false) MultipartFile file,
                                      @AuthenticationPrincipal OAuth2User principal) {
        return ApiResponses.ok(captures.upload(projectId, file, currentMember.require(principal)));
    }

    /** 이미지는 바뀌지 않으므로 브라우저 캐시를 허용한다(로그인한 그 사용자만 — private) */
    @GetMapping("/captures/{captureId}/image")
    public ResponseEntity<byte[]> image(@PathVariable Long projectId, @PathVariable String captureId) {
        CaptureService.Image image = captures.image(projectId, captureId);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(image.contentType()))
            .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(7)).cachePrivate())
            .header("X-Content-Type-Options", "nosniff")
            .body(image.bytes());
    }

    /** 자동 QA 항목의 Figma 프레임(과 불일치 노드 상자) — 배포 경로가 없는 첫 QA 이슈를 고르면 미리보기 자리에 보여 준다(AC32) */
    @GetMapping("/figma/frames/{frameId}/preview")
    public Map<String, Object> framePreview(@PathVariable Long projectId, @PathVariable String frameId,
                                            @RequestParam(name = "node_id", required = false) String nodeId) {
        return ApiResponses.ok(framePreviews.preview(projectId, frameId, nodeId));
    }

    @GetMapping("/screen-links")
    public Map<String, Object> screenLinks(@PathVariable Long projectId) {
        return ApiResponses.ok(screenLinks.list(projectId));
    }

    @PutMapping("/screen-links/{frameId}")
    public Map<String, Object> putScreenLink(@PathVariable Long projectId, @PathVariable String frameId,
                                             @RequestBody LiveQaRequests.ScreenLinkRequest request,
                                             @AuthenticationPrincipal OAuth2User principal) {
        return ApiResponses.ok(screenLinks.put(projectId, frameId, request == null ? null : request.pagePath(), currentMember.require(principal)));
    }

    @DeleteMapping("/screen-links/{frameId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteScreenLink(@PathVariable Long projectId, @PathVariable String frameId, @AuthenticationPrincipal OAuth2User principal) {
        screenLinks.delete(projectId, frameId, currentMember.require(principal));
    }

    @PostMapping("/manual-qa")
    public ResponseEntity<Map<String, Object>> createManualQa(@PathVariable Long projectId,
                                                              @RequestBody LiveQaRequests.ManualQaRequest request,
                                                              @AuthenticationPrincipal OAuth2User principal) {
        ManualQaService.Created result = manualQa.create(projectId, request, currentMember.require(principal));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(ApiResponses.ok(result.issue()));
    }
}
