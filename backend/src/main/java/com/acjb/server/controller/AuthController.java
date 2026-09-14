package com.acjb.server.controller;

import java.util.Map;

import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로그인 상태 확인용 임시 엔드포인트.
 * TODO: DB에 유저 저장/조회 붙으면 OAuth2User 대신 우리 User 엔티티 기준으로 교체
 */
@RestController
public class AuthController {

    @GetMapping("/api/v1/auth/me")
    public Map<String, Object> me(OAuth2User principal) {
        if (principal == null) {
            return Map.of("authenticated", false);
        }
        return Map.of(
            "authenticated", true,
            "name", principal.getAttribute("name"),
            "email", principal.getAttribute("email"),
            "picture", principal.getAttribute("picture")
        );
    }
}
