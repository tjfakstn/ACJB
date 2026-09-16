package com.acjb.server.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 구글 소셜로그인(OAuth2 Login) 초기 설정.
 *
 * 인증 관련 경로를 전부 /api/v1/auth/... 로 통일 (Spring 기본 경로 /oauth2/authorization/*,
 * /login/oauth2/code/* 대신). Spring이 각 provider 경로에 항상 registrationId를 접미사로
 * 붙이는 구조라 baseUri에는 registrationId("google")를 포함하지 않음 — 그래서 결과 경로가
 * "/api/v1/auth/login/google" 이 아니라 "/api/v1/auth/login/{provider}" 형태(현재는 google만 등록).
 *
 * ⚠️ 콜백 경로를 바꿨기 때문에 Google Cloud Console에 등록된 리디렉션 URI도
 * http://localhost:8080/api/v1/auth/callback/google 로 갱신해야 로그인이 동작함.
 *
 * 지금은 세션 기반 로그인만 붙어있는 상태 — SPA(React) + 별도 API(Spring) 구조에서
 * 쿠키 기반 세션을 계속 쓸지, 로그인 성공 후 우리 쪽 JWT를 발급해서 넘길지는
 * 아직 팀 확정 전이라 TODO로 남겨둠 (docs/api_spec.md 6장, docs/openapi.yaml Auth 태그 참고).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String AUTH_LOGIN_BASE_URI = "/api/v1/auth/login";
    private static final String AUTH_CALLBACK_BASE_URI = "/api/v1/auth/callback";
    private static final String AUTH_LOGOUT_URI = "/api/v1/auth/logout";

    private final CorsProperties corsProperties;

    @Value("${app.oauth2.login-success-redirect}")
    private String loginSuccessRedirect;

    public SecurityConfig(CorsProperties corsProperties) {
        this.corsProperties = corsProperties;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/", "/api/v1/health",
                    AUTH_LOGIN_BASE_URI + "/**", AUTH_CALLBACK_BASE_URI + "/**", AUTH_LOGOUT_URI
                ).permitAll()
                .anyRequest().authenticated()
            )
            .oauth2Login(oauth2 -> oauth2
                .authorizationEndpoint(authz -> authz.baseUri(AUTH_LOGIN_BASE_URI))
                .redirectionEndpoint(redir -> redir.baseUri(AUTH_CALLBACK_BASE_URI + "/*"))
                .defaultSuccessUrl(loginSuccessRedirect, true)
            )
            // GET으로 열어서(POST가 아니라) 로그인 버튼과 동일하게 <a href> 링크 클릭만으로 동작하게 함
            // — CSRF 토큰을 프론트에서 아직 다루지 않아서 POST 대신 선택 (TODO: 상태변경 API 늘어나면 재검토)
            .logout(logout -> logout
                .logoutRequestMatcher(PathPatternRequestMatcher.pathPattern(HttpMethod.GET, AUTH_LOGOUT_URI))
                .logoutSuccessUrl("/")
            )
            // 프론트가 fetch로 인증 여부를 확인할 때(/api/**), 미인증이면 구글 로그인으로
            // 리다이렉트하지 않고 401만 반환 — 리다이렉트를 fetch가 따라가면 accounts.google.com에서
            // CORS로 막혀 "Failed to fetch"가 나기 때문. 브라우저 직접 접근(로그인 버튼 클릭)은 영향 없음.
            .exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                PathPatternRequestMatcher.withDefaults().matcher("/api/**")
            ));

        return http.build();
    }

    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsProperties.getAllowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
