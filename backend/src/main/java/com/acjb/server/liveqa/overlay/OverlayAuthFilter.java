package com.acjb.server.liveqa.overlay;

import java.io.IOException;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import com.acjb.server.common.ApiResponses;
import com.acjb.server.common.ErrorCodes;
import com.acjb.server.repository.ProjectMemberRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * /api/v1/overlay/** 인증(2026-11-24, docs/design-live-overlay.md 2절) — 세션 쿠키 대신 오버레이 입장권(Authorization: Bearer).
 * 요청마다 ① 서명·만료·용도 ② Origin 헤더 = 입장권의 배포 출처 ③ 그 멤버가 아직 프로젝트 멤버인지(AC12)를 본다.
 * 하나라도 틀리면 아무것도 바꾸지 않고 401/403. CORS 사전 요청(OPTIONS)은 SecurityConfig의 CORS 설정이 답한다.
 */
public class OverlayAuthFilter extends OncePerRequestFilter {

    public static final String ATTR = "com.acjb.server.liveqa.overlay.ticket";

    private final OverlayTokens tokens;
    private final ProjectMemberRepository projectMembers;
    private final JsonMapper jsonMapper;

    public OverlayAuthFilter(OverlayTokens tokens, ProjectMemberRepository projectMembers, JsonMapper jsonMapper) {
        this.tokens = tokens;
        this.projectMembers = projectMembers;
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        String header = request.getHeader("Authorization");
        Optional<OverlayTokens.Ticket> ticket = header != null && header.startsWith("Bearer ")
            ? tokens.verify(header.substring(7).trim()) : Optional.empty();
        if (ticket.isEmpty()) {
            write(response, HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHORIZED, "QAting에서 [사이트에서 QA 시작]을 다시 눌러 주세요.");
            return;
        }
        OverlayTokens.Ticket t = ticket.get();
        // 다른 사이트가 입장권을 훔쳐 써도 브라우저는 자기 출처를 Origin에 싣는다 — 입장권을 받은 배포 사이트에서만 쓰게
        if (!t.targetOrigin().equals(request.getHeader("Origin"))) {
            write(response, HttpStatus.FORBIDDEN, ErrorCodes.FORBIDDEN, "이 입장권은 연결된 배포 사이트에서만 쓸 수 있어요.");
            return;
        }
        if (!projectMembers.existsByProjectIdAndMemberId(t.projectId(), t.memberId())) {
            write(response, HttpStatus.FORBIDDEN, ErrorCodes.FORBIDDEN, "이 프로젝트의 멤버가 아니에요.");
            return;
        }
        request.setAttribute(ATTR, t);
        chain.doFilter(request, response);
    }

    private void write(HttpServletResponse response, HttpStatus status, String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        jsonMapper.writeValue(response.getOutputStream(), ApiResponses.error(code, message));
    }
}
