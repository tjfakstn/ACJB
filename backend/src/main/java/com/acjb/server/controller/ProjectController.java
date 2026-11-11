package com.acjb.server.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.acjb.server.auth.CurrentMember;
import com.acjb.server.common.ApiResponses;
import com.acjb.server.dto.ChangeRoleRequest;
import com.acjb.server.dto.ConnectRequest;
import com.acjb.server.dto.GithubConnectRequest;
import com.acjb.server.dto.CreateProjectRequest;
import com.acjb.server.service.ConnectionService;
import com.acjb.server.service.ProjectCommandService;
import com.acjb.server.service.ProjectDeletionService;
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
    private final ConnectionService connections;
    private final ProjectDeletionService deletion;
    private final CurrentMember currentMember;

    /** 알림함(2026-11-11) */
    @org.springframework.beans.factory.annotation.Autowired
    private com.acjb.server.service.NotificationService notificationService;

    public ProjectController(ProjectQueryService service, ProjectCommandService commands, ConnectionService connections,
                             ProjectDeletionService deletion, CurrentMember currentMember) {
        this.service = service;
        this.commands = commands;
        this.connections = connections;
        this.deletion = deletion;
        this.currentMember = currentMember;
    }

    /** AC26 — 소유자만, 되돌릴 수 없다. 이름 입력 확인은 화면에서 */
    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long projectId, @AuthenticationPrincipal OAuth2User principal) {
        deletion.delete(projectId, currentMember.require(principal));
    }

    /** AC21 — Figma 파일 연결(소유자만, 보기 권한 확인 후 저장) */
    @PutMapping("/{projectId}/connections/figma")
    public Map<String, Object> connectFigma(@PathVariable Long projectId, @RequestBody(required = false) ConnectRequest request,
                                            @AuthenticationPrincipal OAuth2User principal) {
        return ApiResponses.ok(connections.connectFigma(projectId, request == null ? null : request.url(),
            request == null ? null : request.token(), currentMember.require(principal)));
    }

    /** AC21 — GitHub 공개 저장소 연결(소유자만, 읽기 확인 후 저장) */
    @PutMapping("/{projectId}/connections/github")
    public Map<String, Object> connectGithub(@PathVariable Long projectId, @RequestBody(required = false) GithubConnectRequest request,
                                             @AuthenticationPrincipal OAuth2User principal) {
        return ApiResponses.ok(connections.connectGithub(projectId, request == null ? null : request.url(),
            request == null ? null : request.branch(), currentMember.require(principal)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody CreateProjectRequest request, @AuthenticationPrincipal OAuth2User principal) {
        return ApiResponses.ok(commands.create(request == null ? null : request.name(), request == null ? null : request.description(),
            currentMember.require(principal)));
    }

    /** AC24 — body {role}. 소유자만 */
    @PatchMapping("/{projectId}/members/{memberId}")
    public Map<String, Object> changeRole(@PathVariable Long projectId, @PathVariable String memberId,
                                          @RequestBody(required = false) ChangeRoleRequest request,
                                          @AuthenticationPrincipal OAuth2User principal) {
        return ApiResponses.ok(commands.changeRole(projectId, memberId, request == null ? null : request.role(),
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

    /** AC25 — 작업공간 홈 */
    @GetMapping("/{projectId}")
    public Map<String, Object> detail(@PathVariable Long projectId, @AuthenticationPrincipal OAuth2User principal) {
        return ApiResponses.ok(service.detail(projectId, currentMember.require(principal)));
    }

    @GetMapping("/{projectId}/members")
    public Map<String, Object> members(@PathVariable Long projectId) {
        return ApiResponses.ok(service.members(projectId));
    }

    @GetMapping("/{projectId}/notifications")
    public Map<String, Object> notifications(@PathVariable Long projectId, @RequestParam(required = false) String event,
                                             @AuthenticationPrincipal OAuth2User principal) {
        return ApiResponses.ok(notificationService.inbox(projectId, currentMember.require(principal), event));
    }

    /** 알림 한 건 읽음(2026-11-11) — 본인 알림만, 아니면 404 */
    @PostMapping("/{projectId}/notifications/{notificationId}/read")
    public Map<String, Object> readNotification(@PathVariable Long projectId, @PathVariable String notificationId,
                                                @AuthenticationPrincipal OAuth2User principal) {
        return ApiResponses.ok(notificationService.markRead(projectId, notificationId, currentMember.require(principal)));
    }

    /** 이 프로젝트에서 내 알림 모두 읽음(2026-11-11) */
    @PostMapping("/{projectId}/notifications/read-all")
    public Map<String, Object> readAllNotifications(@PathVariable Long projectId, @AuthenticationPrincipal OAuth2User principal) {
        return ApiResponses.ok(notificationService.markAllRead(projectId, currentMember.require(principal)));
    }
}
