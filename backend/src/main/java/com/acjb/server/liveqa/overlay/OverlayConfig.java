package com.acjb.server.liveqa.overlay;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.acjb.server.repository.ProjectMemberRepository;

import tools.jackson.databind.json.JsonMapper;

/** 오버레이 입장권 필터는 /api/v1/overlay/** 에만 건다 — Spring Security 뒤(기본 순서)라 CORS 응답 헤더가 먼저 붙는다 */
@Configuration
public class OverlayConfig {

    @Bean
    public FilterRegistrationBean<OverlayAuthFilter> overlayAuthFilter(OverlayTokens tokens, ProjectMemberRepository projectMembers,
                                                                       JsonMapper jsonMapper) {
        FilterRegistrationBean<OverlayAuthFilter> bean = new FilterRegistrationBean<>(new OverlayAuthFilter(tokens, projectMembers, jsonMapper));
        bean.addUrlPatterns("/api/v1/overlay/*");
        return bean;
    }
}
