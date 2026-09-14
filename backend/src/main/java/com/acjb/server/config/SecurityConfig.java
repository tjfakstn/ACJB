package com.acjb.server.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 * 지금은 세션 기반 로그인만 붙어있는 상태 — SPA(React) + 별도 API(Spring) 구조에서
 * 쿠키 기반 세션을 계속 쓸지, 로그인 성공 후 우리 쪽 JWT를 발급해서 넘길지는
 * 아직 팀 확정 전이라 TODO로 남겨둠 (docs/api_spec.md 6장 참고).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

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
                .requestMatchers("/", "/api/v1/health", "/login/**", "/oauth2/**").permitAll()
                .anyRequest().authenticated()
            )
            .oauth2Login(oauth2 -> oauth2
                .defaultSuccessUrl(loginSuccessRedirect, true)
            )
            .logout(logout -> logout.logoutSuccessUrl("/"))
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
