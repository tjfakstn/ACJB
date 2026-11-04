package com.acjb.server.auth;

import java.util.function.Supplier;

import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

import com.acjb.server.repository.ProjectMemberRepository;

/**
 * AC12: /api/v1/projects/{projectId}/** 는 로그인한 그 프로젝트의 Member만 통과시킨다.
 * Security 단계에서 막아 컨트롤러가 실행되기 전에 거부한다 — 거부된 요청은 어떤 데이터도 읽거나 바꾸지 않는다.
 * 미인증은 여기서 거부되면 ExceptionTranslationFilter가 401(entry point)로, 인증됐지만 비멤버는 403으로 보낸다.
 * 존재하지 않는 프로젝트도 "멤버 아님"과 같은 403이다 — 프로젝트 존재 여부를 응답으로 드러내지 않는다.
 */
@Component
public class ProjectMemberAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private final ProjectMemberRepository projectMembers;

    public ProjectMemberAuthorizationManager(ProjectMemberRepository projectMembers) {
        this.projectMembers = projectMembers;
    }

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) {
        Authentication auth = authentication.get();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return new AuthorizationDecision(false);
        }
        String email = emailOf(auth);
        Long projectId = parseId(context.getVariables().get("projectId"));
        if (email == null || projectId == null) {
            return new AuthorizationDecision(false);
        }
        return new AuthorizationDecision(projectMembers.existsByProjectIdAndMemberEmail(projectId, email));
    }

    /** 구글 로그인 사용자의 이메일. Member는 이 이메일로 식별한다(db-schema.md members.email) */
    static String emailOf(Authentication auth) {
        if (auth.getPrincipal() instanceof OAuth2User user) {
            Object email = user.getAttribute("email");
            return email instanceof String s && !s.isBlank() ? s : null;
        }
        return null;
    }

    private static Long parseId(String raw) {
        try {
            return raw == null ? null : Long.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
