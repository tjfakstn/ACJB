package com.acjb.server.liveqa.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

/** 연결 화면(프록시)의 응답 고치기 규칙과 입장권(2026-11-20) */
class ProxyRewriteTest {

    @Test
    void head_바로_뒤에_주소_바꾸기와_스니펫을_넣어_사이트_스크립트보다_먼저_돈다() {
        String html = "<!doctype html><html><HEAD lang=\"ko\"><script src=\"/app.js\"></script></head><body></body></html>";
        String out = ProxyRewrite.injectHtml(html, "https://team.example", "https://qating.example/embed.js");
        int shim = out.indexOf("window.fetch");
        int snippet = out.indexOf("<script src=\"https://qating.example/embed.js\" async></script>");
        int app = out.indexOf("/app.js");
        assertThat(out).startsWith("<!doctype html><html><HEAD lang=\"ko\"><script>");
        assertThat(shim).isPositive().isLessThan(snippet);
        assertThat(snippet).isLessThan(app);
        assertThat(out).contains("'https://team.example'").contains("'/__qating/ext/'");
    }

    @Test
    void head가_없으면_만들어_넣고_html도_없으면_맨_앞에_넣는다() {
        assertThat(ProxyRewrite.injectHtml("<html><body>x</body></html>", "https://t.example", "https://q.example/embed.js"))
            .startsWith("<html><head><script>").contains("</head><body>x");
        assertThat(ProxyRewrite.injectHtml("<p>x</p>", "https://t.example", "https://q.example/embed.js")).startsWith("<script>").endsWith("<p>x</p>");
    }

    @Test
    void 원래_사이트로의_리다이렉트만_프록시_경로로_바꾸고_다른_사이트는_그대로() {
        URI req = URI.create("https://team.example/login");
        assertThat(ProxyRewrite.rewriteLocation("https://team.example/home?x=1", req, "https://team.example")).isEqualTo("/home?x=1");
        assertThat(ProxyRewrite.rewriteLocation("/cart", req, "https://team.example")).isEqualTo("/cart");
        assertThat(ProxyRewrite.rewriteLocation("https://accounts.google.com/o/oauth2", req, "https://team.example"))
            .isEqualTo("https://accounts.google.com/o/oauth2");
    }

    @Test
    void 쿠키는_도메인을_빼고_http_개발에서는_Secure와_SameSite_None도_뺀다() {
        String c = "sid=abc; Domain=.team.example; Path=/; Secure; HttpOnly; SameSite=None";
        assertThat(ProxyRewrite.rewriteSetCookie(c, true)).isEqualTo("sid=abc; Path=/; Secure; HttpOnly; SameSite=None");
        assertThat(ProxyRewrite.rewriteSetCookie(c, false)).isEqualTo("sid=abc; Path=/; HttpOnly; SameSite=Lax");
    }

    @Test
    void 주소_인코딩은_되돌릴_수_있다() {
        String url = "https://api.team.example/v1/items?q=한글&x=1";
        assertThat(ProxyRewrite.decodeUrl(ProxyRewrite.encodeUrl(url))).isEqualTo(url);
    }

    @Test
    void 입장권은_서명이_맞고_만료_전일_때만_통과한다() {
        Clock now = Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"), ZoneOffset.UTC);
        ProxyTokens tokens = new ProxyTokens("test-secret", now);
        String t = tokens.issue(7L, "https://team.example");
        assertThat(tokens.verify(t)).hasValueSatisfying(ticket -> {
            assertThat(ticket.projectId()).isEqualTo(7L);
            assertThat(ticket.targetOrigin()).isEqualTo("https://team.example");
        });
        // 내용을 바꾸면(다른 프로젝트·다른 사이트) 서명이 맞지 않는다
        String forged = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(("8|https://evil.example|" + now.instant().plusSeconds(3600).getEpochSecond()).getBytes()) + t.substring(t.indexOf('.'));
        assertThat(tokens.verify(forged)).isEmpty();
        assertThat(new ProxyTokens("other-secret", now).verify(t)).isEmpty();
        assertThat(tokens.verify(null)).isEmpty();
        assertThat(tokens.verify("garbage")).isEmpty();
        Clock later = Clock.fixed(now.instant().plus(ProxyTokens.TTL).plusSeconds(1), ZoneOffset.UTC);
        assertThat(new ProxyTokens("test-secret", later).verify(t)).isEmpty();
    }
}
