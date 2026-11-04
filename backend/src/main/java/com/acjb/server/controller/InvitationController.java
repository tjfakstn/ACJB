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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.acjb.server.auth.CurrentMember;
import com.acjb.server.common.ApiResponses;
import com.acjb.server.dto.InvitationDtos;
import com.acjb.server.service.InvitationService;

/**
 * 초대(AC17)·수락(AC18). /projects/{projectId}/invitations/** 는 그 프로젝트 멤버만(AC12),
 * /invitations/** 는 로그인만 필요하다(초대받은 사람은 아직 멤버가 아니다)
 */
@RestController
public class InvitationController {

    private final InvitationService service;
    private final CurrentMember currentMember;

    public InvitationController(InvitationService service, CurrentMember currentMember) {
        this.service = service;
        this.currentMember = currentMember;
    }

    @PostMapping("/api/v1/projects/{projectId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> invite(@PathVariable Long projectId, @RequestBody InvitationDtos.CreateRequest request,
                                      @AuthenticationPrincipal OAuth2User principal) {
        return ApiResponses.ok(service.invite(projectId, request == null ? null : request.email(), currentMember.require(principal)));
    }

    @GetMapping("/api/v1/projects/{projectId}/invitations")
    public Map<String, Object> pending(@PathVariable Long projectId) {
        return ApiResponses.ok(service.pending(projectId));
    }

    @DeleteMapping("/api/v1/projects/{projectId}/invitations/{invitationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable Long projectId, @PathVariable String invitationId, @AuthenticationPrincipal OAuth2User principal) {
        service.cancel(projectId, invitationId, currentMember.require(principal));
    }

    @GetMapping("/api/v1/invitations/mine")
    public Map<String, Object> mine(@AuthenticationPrincipal OAuth2User principal) {
        return ApiResponses.ok(service.mine(currentMember.require(principal).getEmail()));
    }

    @PostMapping("/api/v1/invitations/mine/{invitationId}/accept")
    public Map<String, Object> acceptMine(@PathVariable String invitationId, @RequestBody InvitationDtos.AcceptRequest request,
                                          @AuthenticationPrincipal OAuth2User principal) {
        return ApiResponses.ok(service.acceptById(invitationId, request == null ? null : request.role(), currentMember.require(principal)));
    }

    @GetMapping("/api/v1/invitations/{token}")
    public Map<String, Object> preview(@PathVariable String token) {
        return ApiResponses.ok(service.preview(token));
    }

    @PostMapping("/api/v1/invitations/{token}/accept")
    public Map<String, Object> accept(@PathVariable String token, @RequestBody InvitationDtos.AcceptRequest request,
                                      @AuthenticationPrincipal OAuth2User principal) {
        return ApiResponses.ok(service.acceptByToken(token, request == null ? null : request.role(), currentMember.require(principal)));
    }
}
