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
import com.acjb.server.dto.FigmaSyncRequest;
import com.acjb.server.service.FigmaSyncService;

/** Figma 동기화(비동기)와 동기화된 프레임 목록 (SPEC 5절). 프로젝트 멤버만(AC12) */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/figma")
public class FigmaController {

    private final FigmaSyncService service;
    private final CurrentMember currentMember;

    public FigmaController(FigmaSyncService service, CurrentMember currentMember) {
        this.service = service;
        this.currentMember = currentMember;
    }

    @PostMapping("/sync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> sync(@PathVariable Long projectId, @RequestBody FigmaSyncRequest request,
                                    @AuthenticationPrincipal OAuth2User principal) {
        return ApiResponses.ok(service.request(projectId, request, currentMember.require(principal)));
    }

    @GetMapping("/sync/{syncId}")
    public Map<String, Object> status(@PathVariable Long projectId, @PathVariable String syncId) {
        return ApiResponses.ok(service.status(projectId, syncId));
    }

    @GetMapping("/frames")
    public Map<String, Object> frames(@PathVariable Long projectId) {
        return ApiResponses.ok(service.frames(projectId));
    }
}
