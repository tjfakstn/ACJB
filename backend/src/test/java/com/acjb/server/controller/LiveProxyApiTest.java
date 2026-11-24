package com.acjb.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.acjb.server.domain.Member;
import com.acjb.server.domain.MemberRole;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.ProjectMember;
import com.acjb.server.github.GithubClient;
import com.acjb.server.golden.GoldenCase;
import com.acjb.server.liveqa.proxy.LiveProxyFilter;
import com.acjb.server.liveqa.proxy.ProxyRewrite;
import com.acjb.server.repository.MemberRepository;
import com.acjb.server.repository.ProjectMemberRepository;
import com.acjb.server.repository.ProjectRepository;
import com.sun.net.httpserver.HttpServer;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;

/**
 * 연결 화면(프록시, 2026-11-20) — 배포 사이트를 QAting과 다른 호스트로 대신 띄우고 HTML에 스니펫을 넣는다(AC29).
 * 원래 사이트는 이 테스트가 띄운 로컬 HTTP 서버다(테스트 설정은 루프백을 허용한다).
 */
@SpringBootTest(properties = {"app.proxy.origin=http://view.test", "app.proxy.secret=test-proxy-secret",
    "app.proxy.snippet-url=http://qating.test/embed.js"})
@AutoConfigureMockMvc
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LiveProxyApiTest {

    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired ProjectRepository projects;
    @Autowired MemberRepository members;
    @Autowired ProjectMemberRepository projectMembers;
    @MockitoBean GithubClient github;

    HttpServer site;
    String siteOrigin;
    final List<String> hits = new CopyOnWriteArrayList<>();
    Project project;
    Member front;
    Member outsider;

    @BeforeEach
    void setUp() throws IOException {
        site = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        site.createContext("/", ex -> {
            hits.add(ex.getRequestMethod() + " " + ex.getRequestURI() + " origin=" + ex.getRequestHeaders().getFirst("Origin")
                + " cookie=" + ex.getRequestHeaders().getFirst("Cookie"));
            byte[] body;
            if (ex.getRequestURI().getPath().equals("/old")) {
                ex.getResponseHeaders().add("Location", siteOrigin + "/new?x=1");
                ex.sendResponseHeaders(302, -1);
                ex.close();
                return;
            }
            if (ex.getRequestURI().getPath().startsWith("/api")) {
                body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
            } else {
                body = "<html><head><title>팀</title></head><body><button id=\"pay\">결제하기</button></body></html>".getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                ex.getResponseHeaders().add("X-Frame-Options", "DENY");
                ex.getResponseHeaders().add("Content-Security-Policy", "frame-ancestors 'none'");
                ex.getResponseHeaders().add("Set-Cookie", "sid=abc; Domain=127.0.0.1; Path=/; HttpOnly");
            }
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        site.start();
        siteOrigin = "http://127.0.0.1:" + site.getAddress().getPort();

        Member owner = members.save(new Member("lp-owner@test.dev", "소유자"));
        front = members.save(new Member("lp-front@test.dev", "프론트"));
        outsider = members.save(new Member("lp-out@test.dev", "외부"));
        project = projects.save(new Project("연결 화면", owner));
        projectMembers.save(new ProjectMember(project, owner, null));
        projectMembers.save(new ProjectMember(project, front, MemberRole.FRONTEND));
        project.changeDeployUrl(siteOrigin, Instant.now());
        em.flush();
        when(github.latestDeploymentUrl(anyString(), anyString())).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        site.stop(0);
    }

    private String enterUrl() throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/projects/" + project.getId() + "/live-proxy")
                .with(oauth2Login().attributes(a -> a.put("email", front.getEmail()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.enabled").value(true))
            .andExpect(jsonPath("$.data.origin").value("http://view.test"))
            .andReturn();
        return r.getResponse().getContentAsString().replaceAll(".*\"enter_url\":\"([^\"]+)\".*", "$1");
    }

    private String token(String enterUrl) {
        return enterUrl.substring(enterUrl.indexOf("t=") + 2);
    }

    @Test
    @GoldenCase("lp-001")
    void 멤버가_입장권을_받아_들어가면_원래_사이트를_대신_보여_주고_스니펫을_넣으며_삽입_차단_헤더를_뺀다() throws Exception {
        String enter = enterUrl();
        assertThat(enter).startsWith("http://view.test/__qating/enter?t=");
        mvc.perform(get("/__qating/enter").param("t", token(enter)).param("path", "/checkout").with(r -> { r.setServerName("view.test"); return r; }))
            .andExpect(status().isFound())
            .andExpect(header().string("Location", "/checkout"))
            .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.startsWith(LiveProxyFilter.COOKIE + "=")));

        MvcResult page = mvc.perform(get("/checkout").cookie(new Cookie(LiveProxyFilter.COOKIE, token(enter)))
                .with(r -> { r.setServerName("view.test"); return r; }))
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("X-Frame-Options"))
            .andExpect(header().doesNotExist("Content-Security-Policy"))
            .andExpect(header().string("Set-Cookie", "sid=abc; Path=/; HttpOnly"))
            .andReturn();
        String html = page.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("<script src=\"http://qating.test/embed.js\" async></script>").contains("<button id=\"pay\">결제하기</button>")
            .contains("'" + siteOrigin + "'");
        // 원래 사이트에는 원래 출처에서 부른 것처럼 간다
        assertThat(hits).anyMatch(h -> h.startsWith("GET /checkout origin=" + siteOrigin));
    }

    @Test
    void 다른_도메인_API_호출은_프록시가_대신_부르고_사이트_쿠키만_넘기며_같은_사이트_리다이렉트는_프록시_경로로_바꾼다() throws Exception {
        String t = token(enterUrl());
        mvc.perform(post(ProxyRewrite.EXT_PREFIX + ProxyRewrite.encodeUrl(siteOrigin + "/api/items?q=1"))
                .cookie(new Cookie(LiveProxyFilter.COOKIE, t), new Cookie("sid", "abc"))
                .contentType("application/json").content("{\"a\":1}")
                .with(r -> { r.setServerName("view.test"); return r; }))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));
        assertThat(hits).anyMatch(h -> h.equals("POST /api/items?q=1 origin=" + siteOrigin + " cookie=sid=abc"));
        mvc.perform(get("/old").cookie(new Cookie(LiveProxyFilter.COOKIE, t)).with(r -> { r.setServerName("view.test"); return r; }))
            .andExpect(status().isFound())
            .andExpect(header().string("Location", "/new?x=1"));
    }

    @Test
    @GoldenCase("lp-002")
    void 입장권이_없거나_틀리면_403이고_원래_사이트를_부르지_않으며_내부망_주소는_열지_않는다() throws Exception {
        mvc.perform(get("/checkout").with(r -> { r.setServerName("view.test"); return r; })).andExpect(status().isForbidden());
        mvc.perform(get("/checkout").cookie(new Cookie(LiveProxyFilter.COOKIE, "forged.token")).with(r -> { r.setServerName("view.test"); return r; }))
            .andExpect(status().isForbidden());
        mvc.perform(get("/__qating/enter").param("t", "forged.token").with(r -> { r.setServerName("view.test"); return r; }))
            .andExpect(status().isForbidden());
        assertThat(hits).isEmpty();
        String t = token(enterUrl());
        mvc.perform(get(ProxyRewrite.EXT_PREFIX + ProxyRewrite.encodeUrl("http://169.254.169.254/latest/meta-data"))
                .cookie(new Cookie(LiveProxyFilter.COOKIE, t)).with(r -> { r.setServerName("view.test"); return r; }))
            .andExpect(status().isForbidden());
        mvc.perform(get(ProxyRewrite.EXT_PREFIX + ProxyRewrite.encodeUrl("file:///etc/passwd"))
                .cookie(new Cookie(LiveProxyFilter.COOKIE, t)).with(r -> { r.setServerName("view.test"); return r; }))
            .andExpect(status().isForbidden());
    }

    @Test
    void 비멤버는_입장권을_받을_수_없고_QAting_호스트_요청은_프록시가_가로채지_않는다() throws Exception {
        mvc.perform(get("/api/v1/projects/" + project.getId() + "/live-proxy").with(oauth2Login().attributes(a -> a.put("email", outsider.getEmail()))))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok"));
        assertThat(hits).isEmpty();
    }
}
