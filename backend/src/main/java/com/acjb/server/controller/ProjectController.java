package com.acjb.server.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import com.acjb.server.dto.CreateProjectRequest;
import com.acjb.server.service.ProjectCommandService;
import com.acjb.server.service.ProjectQueryService;

/**
 * 프로젝트 만들기(AC16)·멤버 제거(AC19)와 프로젝트·멤버·알림 조회(AC14·AC15).
 * /projects는 로그인만 필요하고, /projects/{projectId}/** 는 Security 단계에서 그 프로젝트 멤버만 통과한다(AC12)
 */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectQueryService service;
    private final ProjectCommandService commands;
    private final CurrentMember currentMember;

    public ProjectController(ProjectQueryService service, ProjectCommandService commands, CurrentMember currentMember) {
        this.service = service;
        this.commands = commands;
        this.currentMember = currentMember;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody CreateProjectRequest request, @AuthenticationPrincipal OAuth2User principal) {
        return ApiResponses.ok(commands.create(request == null ? null : request.name(), request == null ? null : request.role(),
            currentMember.require(principal)));
    }

    @DeleteMapping("/{projectId}/members/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable Long projectId, @PathVariable String memberId,
                             @AuthenticationPrincipal OAuth2User principal) {
        commands.removeMember(projectId, memberId, currentMember.require(principal));
    }

    @GetMapping
    public Map<String, Object> myProjects(@AuthenticationPrincipal OAuth2User principal) {
        return ApiResponses.ok(service.myProjects(CurrentMember.emailOf(principal)));
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
