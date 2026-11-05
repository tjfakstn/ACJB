package com.acjb.server.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.acjb.server.auth.CsrfCookieFilter;
import com.acjb.server.auth.JsonErrorResponder;
import com.acjb.server.auth.ProjectMemberAuthorizationManager;

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
 * 인증은 구글 로그인 세션 쿠키 + 상태 변경 요청의 CSRF 토큰(X-XSRF-TOKEN)으로 확정
 * (2026-10-28, docs/SPEC.md 5절 · docs/openapi.yaml securitySchemes).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String AUTH_LOGIN_BASE_URI = "/api/v1/auth/login";
    private static final String AUTH_CALLBACK_BASE_URI = "/api/v1/auth/callback";
    private static final String AUTH_LOGOUT_URI = "/api/v1/auth/logout";
    private static final String AUTH_ME_URI = "/api/v1/auth/me";

    private static final String PROJECT_SCOPED_PATH = "/api/v1/projects/{projectId}/**";

    private final CorsProperties corsProperties;
    private final ProjectMemberAuthorizationManager projectMemberAuthorization;
    private final JsonErrorResponder errorResponder;

    @Value("${app.oauth2.login-success-redirect}")
    private String loginSuccessRedirect;

    // 상대 경로("/")로 두면 백엔드 주소(localhost:8080/)로 가서 404가 났다 — 로그인과 같이 프론트 절대 주소로 보낸다
    @Value("${app.oauth2.logout-success-redirect}")
    private String logoutSuccessRedirect;

    public SecurityConfig(CorsProperties corsProperties, ProjectMemberAuthorizationManager projectMemberAuthorization,
                          JsonErrorResponder errorResponder) {
        this.corsProperties = corsProperties;
        this.projectMemberAuthorization = projectMemberAuthorization;
        this.errorResponder = errorResponder;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, ClientRegistrationRepository clientRegistrations) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            // 세션 쿠키 인증이라 상태 변경 요청(POST·PATCH)에 CSRF 토큰을 요구한다(SPEC 5절).
            // spa(): 토큰을 JS가 읽을 수 있는 XSRF-TOKEN 쿠키로 내려주고, 요청 헤더 X-XSRF-TOKEN 값과 대조한다.
            // GET은 검사하지 않는다. 토큰이 없거나 틀리면 AccessDeniedHandler(JsonErrorResponder.forbidden)로 403.
            .csrf(csrf -> csrf.spa())
            .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/", "/error", "/api/v1/health",
                    // AC10: 미인증이어도 200 {authenticated: false}를 돌려줘야 해서 열어 둔다(컨트롤러가 principal null을 처리)
                    AUTH_ME_URI,
                    AUTH_LOGIN_BASE_URI + "/**", AUTH_CALLBACK_BASE_URI + "/**", AUTH_LOGOUT_URI
                ).permitAll()
                // AC12: 프로젝트 하위 경로는 그 프로젝트 멤버만 (미인증 401, 비멤버 403)
                .requestMatchers(PathPatternRequestMatcher.withDefaults().matcher(PROJECT_SCOPED_PATH))
                    .access(projectMemberAuthorization)
                .anyRequest().authenticated()
            )
            .oauth2Login(oauth2 -> oauth2
                .authorizationEndpoint(authz -> authz
                    .baseUri(AUTH_LOGIN_BASE_URI)
                    .authorizationRequestResolver(selectAccountResolver(clientRegistrations)))
                .redirectionEndpoint(redir -> redir.baseUri(AUTH_CALLBACK_BASE_URI + "/*"))
                .defaultSuccessUrl(loginSuccessRedirect, true)
            )
            // GET으로 열어서(POST가 아니라) 로그인 버튼과 동일하게 <a href> 링크 클릭만으로 동작하게 함
            // — CSRF 토큰을 프론트에서 아직 다루지 않아서 POST 대신 선택 (TODO: 상태변경 API 늘어나면 재검토)
            .logout(logout -> logout
                .logoutRequestMatcher(PathPatternRequestMatcher.pathPattern(HttpMethod.GET, AUTH_LOGOUT_URI))
                .logoutSuccessUrl(logoutSuccessRedirect)
            )
            // 프론트가 fetch로 인증 여부를 확인할 때(/api/**), 미인증이면 구글 로그인으로
            // 리다이렉트하지 않고 401만 반환 — 리다이렉트를 fetch가 따라가면 accounts.google.com에서
            // CORS로 막혀 "Failed to fetch"가 나기 때문. 브라우저 직접 접근(로그인 버튼 클릭)은 영향 없음.
            // 401·403 본문은 openapi ErrorResponse 형태로 통일한다(골든 케이스 au-001·au-002).
            .exceptionHandling(ex -> ex
                .defaultAuthenticationEntryPointFor(errorResponder.unauthorized(),
                    PathPatternRequestMatcher.withDefaults().matcher("/api/**"))
                .defaultAccessDeniedHandlerFor(errorResponder.forbidden(),
                    PathPatternRequestMatcher.withDefaults().matcher("/api/**"))
            );

        return http.build();
    }

    /**
     * 구글에 prompt=select_account를 붙여 로그인할 때마다 계정 선택창을 띄운다(2026-11-05 설만수 결정).
     * 붙이지 않으면 QAting에서 로그아웃해도 브라우저에 남은 구글 로그인으로 같은 계정에 바로 다시 들어가,
     * 한 브라우저에서 다른 계정(예: 초대받은 계정)으로 바꿔 로그인할 수 없다.
     */
    private static OAuth2AuthorizationRequestResolver selectAccountResolver(ClientRegistrationRepository clientRegistrations) {
        DefaultOAuth2AuthorizationRequestResolver resolver =
            new DefaultOAuth2AuthorizationRequestResolver(clientRegistrations, AUTH_LOGIN_BASE_URI);
        resolver.setAuthorizationRequestCustomizer(request ->
            request.additionalParameters(params -> params.put("prompt", "select_account")));
        return resolver;
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
