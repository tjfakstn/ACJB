package com.acjb.server.controller;

import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.acjb.server.auth.CurrentMember;
import com.acjb.server.common.ApiResponses;
import com.acjb.server.service.ProjectQueryService;

/**
 * 프로젝트·멤버·알림 조회 (SPEC 5절, AC14·AC15). 프로젝트·멤버 등록 API는 v1에 없다(시드 데이터).
 * /projects는 로그인만 필요하고, /projects/{projectId}/** 는 Security 단계에서 그 프로젝트 멤버만 통과한다(AC12)
 */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectQueryService service;
    private final CurrentMember currentMember;

    public ProjectController(ProjectQueryService service, CurrentMember currentMember) {
        this.service = service;
        this.currentMember = currentMember;
    }

    @GetMapping
    public Map<String, Object> myProjects(@AuthenticationPrincipal OAuth2User principal) {
        return ApiResponses.ok(service.myProjects(principal == null ? null : principal.getAttribute("email")));
    }

    @GetMapping("/{projectId}/members")
    public Map<String, Object> members(@PathVariable Long projectId) {
        return ApiResponses.ok(service.members(projectId));
    }

    @GetMapping("/{projectId}/notifications")
    public Map<String, Object> notifications(@PathVariable Long projectId, @RequestParam(required = false) String event,
                                             @AuthenticationPrincipal OAuth2User principal) {
        return ApiResponses.ok(service.myNotifications(projectId, currentMember.require(principal), event));
    }
}
