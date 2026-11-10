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
import com.acjb.server.domain.RunKind;
import com.acjb.server.service.QaRunService;

import tools.jackson.databind.JsonNode;

/** AC23 첫 QA·AC28 자동 Re-QA 시작·상태. 멤버 확인(AC12)은 /projects/{id}/** 공통 검사가 한다 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class QaRunController {

    private final QaRunService service;
    private final CurrentMember currentMember;

    public QaRunController(QaRunService service, CurrentMember currentMember) {
        this.service = service;
        this.currentMember = currentMember;
    }

    @PostMapping("/first-qa")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> startFirstQa(@PathVariable Long projectId, @RequestBody(required = false) JsonNode body,
                                            @AuthenticationPrincipal OAuth2User principal) {
        return ApiResponses.ok(service.startFirstQa(projectId, body, currentMember.require(principal)));
    }

    /** 실행한 적이 없으면 data null */
    @GetMapping("/first-qa")
    public Map<String, Object> latestFirstQa(@PathVariable Long projectId) {
        return ApiResponses.ok(service.latest(projectId, RunKind.FIRST_QA));
    }

    @PostMapping("/re-qa")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> startReQa(@PathVariable Long projectId, @RequestBody(required = false) JsonNode body,
                                         @AuthenticationPrincipal OAuth2User principal) {
        return ApiResponses.ok(service.startReQa(projectId, body, currentMember.require(principal)));
    }

    @GetMapping("/re-qa")
    public Map<String, Object> latestReQa(@PathVariable Long projectId) {
        return ApiResponses.ok(service.latest(projectId, RunKind.RE_QA));
    }
}
