package com.acjb.server.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.acjb.server.auth.CurrentMember;
import com.acjb.server.common.ApiResponses;
import com.acjb.server.dto.CreateIssueFromMismatchRequest;
import com.acjb.server.service.QaIssueService;
import com.acjb.server.service.StyleMismatchQueryService;

/** 스타일 차이 목록·상세와, 기록된 차이로 이슈 만들기 (SPEC 5절). scan(자동 추출)은 아직 없다 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/style-mismatches")
public class StyleMismatchController {

    private final StyleMismatchQueryService queryService;
    private final QaIssueService issueService;
    private final CurrentMember currentMember;

    public StyleMismatchController(StyleMismatchQueryService queryService, QaIssueService issueService, CurrentMember currentMember) {
        this.queryService = queryService;
        this.issueService = issueService;
        this.currentMember = currentMember;
    }

    @GetMapping
    public Map<String, Object> list(@PathVariable Long projectId,
                                    @RequestParam(name = "frame_id", required = false) String frameId,
                                    @RequestParam(required = false) String property) {
        return ApiResponses.ok(queryService.list(projectId, frameId, property));
    }

    @GetMapping("/{mismatchId}")
    public Map<String, Object> get(@PathVariable Long projectId, @PathVariable String mismatchId) {
        return ApiResponses.ok(queryService.get(projectId, mismatchId));
    }

    @PostMapping("/{mismatchId}/issues")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createIssue(@PathVariable Long projectId, @PathVariable String mismatchId,
                                           @RequestBody CreateIssueFromMismatchRequest request,
                                           @AuthenticationPrincipal OAuth2User principal) {
        return ApiResponses.ok(issueService.createFromMismatch(projectId, mismatchId, request, currentMember.require(principal)));
    }
}
