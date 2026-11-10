package com.acjb.server.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.acjb.server.auth.CurrentMember;
import com.acjb.server.common.ApiResponses;
import com.acjb.server.trace.CodeTraceService;

/** 이슈 상세 코드 추적(SPEC AC34). 멤버 확인(AC12)은 /projects/{id}/** 공통 검사가 한다 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/qa-issues/{issueId}")
public class CodeTraceController {

    public record FixLinkRequest(String url) {
    }

    private final CodeTraceService service;
    private final CurrentMember currentMember;

    public CodeTraceController(CodeTraceService service, CurrentMember currentMember) {
        this.service = service;
        this.currentMember = currentMember;
    }

    @GetMapping("/code-trace")
    public Map<String, Object> trace(@PathVariable Long projectId, @PathVariable String issueId) {
        return ApiResponses.ok(service.trace(projectId, issueId));
    }

    @PutMapping("/fix-link")
    public Map<String, Object> attach(@PathVariable Long projectId, @PathVariable String issueId, @RequestBody FixLinkRequest body,
                                      @AuthenticationPrincipal OAuth2User principal) {
        return ApiResponses.ok(service.attach(projectId, issueId, body == null ? null : body.url(), currentMember.require(principal)));
    }

    @DeleteMapping("/fix-link")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void detach(@PathVariable Long projectId, @PathVariable String issueId, @AuthenticationPrincipal OAuth2User principal) {
        service.detach(projectId, issueId, currentMember.require(principal));
    }
}
