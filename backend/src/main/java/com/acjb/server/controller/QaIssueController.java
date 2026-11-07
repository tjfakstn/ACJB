package com.acjb.server.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.acjb.server.auth.CurrentMember;
import com.acjb.server.common.ApiResponses;
import com.acjb.server.dto.CreateQaIssueRequest;
import com.acjb.server.dto.ParseRequest;
import com.acjb.server.service.ParseService;
import com.acjb.server.service.QaIssuePatchService;
import com.acjb.server.service.QaIssueService;

import tools.jackson.databind.JsonNode;

/** QA 이슈 등록·목록·상세 (SPEC 5절). 이 경로는 Security 단계에서 프로젝트 멤버만 통과한다(AC12) */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/qa-issues")
public class QaIssueController {

    private final QaIssueService service;
    private final QaIssuePatchService patchService;
    private final ParseService parseService;
    private final CurrentMember currentMember;

    public QaIssueController(QaIssueService service, QaIssuePatchService patchService, ParseService parseService,
                             CurrentMember currentMember) {
        this.service = service;
        this.patchService = patchService;
        this.parseService = parseService;
        this.currentMember = currentMember;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@PathVariable Long projectId, @RequestBody CreateQaIssueRequest request,
                                      @AuthenticationPrincipal OAuth2User principal) {
        return ApiResponses.ok(service.create(projectId, request, currentMember.require(principal)));
    }

    @GetMapping
    public Map<String, Object> list(@PathVariable Long projectId,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) String source,
                                    @RequestParam(name = "assigned_to", required = false) String assignedTo) {
        return ApiResponses.ok(service.list(projectId, status, source, assignedTo));
    }

    @GetMapping("/{issueId}")
    public Map<String, Object> get(@PathVariable Long projectId, @PathVariable String issueId) {
        return ApiResponses.ok(service.get(projectId, issueId));
    }

    /** body는 JsonNode로 받는다 — 필드가 "없음"과 "null"을 구분해야 해서(assigned_to: null은 400) */
    @PatchMapping("/{issueId}")
    public Map<String, Object> patch(@PathVariable Long projectId, @PathVariable String issueId, @RequestBody JsonNode body,
                                     @AuthenticationPrincipal OAuth2User principal) {
        return ApiResponses.ok(patchService.patch(projectId, issueId, body, currentMember.require(principal)));
    }

    /** 자유서술 텍스트 → IssueReportContext 초안(AC8). 이슈를 만들지 않는다(N9) */
    @PostMapping("/parse")
    public Map<String, Object> parse(@PathVariable Long projectId, @RequestBody ParseRequest request) {
        return ApiResponses.ok(parseService.parse(request == null ? null : request.text()));
    }
}
