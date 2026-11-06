package com.acjb.server.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.acjb.server.auth.CurrentMember;
import com.acjb.server.domain.Member;

/**
 * 로그인 상태 확인 (AC10). 인증 여부와 무관하게 200.
 * 로그인했으면 내 멤버 id를 함께 준다 — 프론트가 owner_id·reported_by 등과 비교해 내 것인지 판단한다(2026-11-05).
 * 처음 로그인한 구글 계정은 여기서 Member가 만들어진다(AC16).
 */
@RestController
public class AuthController {

    private final CurrentMember currentMember;

    public AuthController(CurrentMember currentMember) {
        this.currentMember = currentMember;
    }

    @GetMapping("/api/v1/auth/me")
    public Map<String, Object> me(@AuthenticationPrincipal OAuth2User principal) {
        // 이메일이 없는 계정은 Member로 만들 수 없다 — 다른 API도 401이므로 로그인하지 않은 것으로 본다
        if (CurrentMember.emailOf(principal) == null) {
            return Map.of("authenticated", false);
        }
        Member me = currentMember.require(principal);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authenticated", true);
        body.put("id", String.valueOf(me.getId()));
        body.put("name", me.getName());
        body.put("email", me.getEmail());
        Object picture = principal.getAttribute("picture");
        body.put("picture", picture instanceof String s && !s.isBlank() ? s : null);
        return body;
    }
}
