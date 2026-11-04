package com.acjb.server.auth;

import java.io.IOException;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Security 단계에서 거부된 /api/** 요청을 openapi ErrorResponse 형태({success:false, error:{code, message}})로 응답한다.
 * data 키는 넣지 않는다 — 거부 응답에 데이터가 실리지 않음을 형태로도 보장한다(골든 케이스 au-002 body_not_has: [data]).
 */
@Component
public class JsonErrorResponder {

    private final JsonMapper jsonMapper;

    public JsonErrorResponder(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public AuthenticationEntryPoint unauthorized() {
        return (request, response, ex) -> write(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다.");
    }

    /** 비멤버(AC12)와 CSRF 토큰 누락·불일치가 모두 여기로 온다 — openapi Forbidden 설명과 같다 */
    public AccessDeniedHandler forbidden() {
        return (request, response, ex) -> write(response, HttpStatus.FORBIDDEN, "FORBIDDEN", "이 요청을 수행할 권한이 없습니다.");
    }

    private void write(HttpServletResponse response, HttpStatus status, String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        jsonMapper.writeValue(response.getOutputStream(),
            Map.of("success", false, "error", Map.of("code", code, "message", message)));
    }
}
