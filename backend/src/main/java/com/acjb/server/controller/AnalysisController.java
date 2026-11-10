package com.acjb.server.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.acjb.server.auth.CurrentMember;
import com.acjb.server.common.ApiResponses;
import com.acjb.server.service.AnalysisService;

/** AC22 비교 분석 시작·상태, AC27 디자인-코드 매핑 조회. 멤버 확인(AC12)은 /projects/{id}/** 공통 검사가 한다 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/analysis")
public class AnalysisController {

    private final AnalysisService service;
    private final CurrentMember currentMember;

    public AnalysisController(AnalysisService service, CurrentMember currentMember) {
        this.service = service;
        this.currentMember = currentMember;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> start(@PathVariable Long projectId, @AuthenticationPrincipal OAuth2User principal) {
        return ApiResponses.ok(service.start(projectId, currentMember.require(principal)));
    }

    /** 실행한 적이 없으면 data null */
    @GetMapping
    public Map<String, Object> latest(@PathVariable Long projectId) {
        return ApiResponses.ok(service.latest(projectId));
    }

    @GetMapping("/mappings")
    public Map<String, Object> mappings(@PathVariable Long projectId) {
        return ApiResponses.ok(service.mappings(projectId));
    }
}
