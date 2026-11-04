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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.acjb.server.auth.CurrentMember;
import com.acjb.server.common.ApiResponses;
import com.acjb.server.dto.CreateVerificationRequest;
import com.acjb.server.service.VerificationService;

/** 재검증 등록·조회 (SPEC 5절, AC5·AC7·AC11·AC13). 프로젝트 멤버 여부는 Security 단계에서 확인했다(AC12) */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/qa-issues/{issueId}/verifications")
public class VerificationController {

    private final VerificationService service;
    private final CurrentMember currentMember;

    public VerificationController(VerificationService service, CurrentMember currentMember) {
        this.service = service;
        this.currentMember = currentMember;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@PathVariable Long projectId, @PathVariable String issueId,
                                      @RequestBody CreateVerificationRequest request,
                                      @AuthenticationPrincipal OAuth2User principal) {
        return ApiResponses.ok(service.create(projectId, issueId, request, currentMember.require(principal)));
    }

    @GetMapping
    public Map<String, Object> list(@PathVariable Long projectId, @PathVariable String issueId) {
        return ApiResponses.ok(service.list(projectId, issueId));
    }
}
