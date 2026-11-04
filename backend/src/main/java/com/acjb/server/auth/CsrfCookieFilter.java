package com.acjb.server.auth;

import java.io.IOException;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Spring Security는 CSRF 토큰을 지연 로딩해서, 누군가 토큰을 읽기 전에는 XSRF-TOKEN 쿠키를 내려주지 않는다.
 * SPA는 첫 POST 전에 쿠키가 있어야 헤더에 실을 수 있으므로, 매 요청에서 토큰을 한 번 읽어 쿠키가 쓰이게 한다.
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token != null) {
            token.getToken();
        }
        chain.doFilter(request, response);
    }
}
