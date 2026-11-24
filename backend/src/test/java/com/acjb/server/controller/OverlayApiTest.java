package com.acjb.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.acjb.server.domain.Member;
import com.acjb.server.domain.MemberRole;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.ProjectMember;
import com.acjb.server.figma.FigmaClient;
import com.acjb.server.github.GithubClient;
import com.acjb.server.liveqa.EmbedChecker;
import com.acjb.server.liveqa.PageCapturer;
import com.acjb.server.repository.MemberRepository;
import com.acjb.server.repository.ProjectMemberRepository;
import com.acjb.server.repository.ProjectRepository;
import com.acjb.server.repository.QAIssueRepository;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import tools.jackson.databind.json.JsonMapper;

/**
 * 배포 사이트 위 QAting 툴바(오버레이) — 입장권 발급·검사와 등록·핀 조회(2026-11-24, docs/design-live-overlay.md).
 * 세션 쿠키가 없는 다른 출처 요청을 흉내 내려고 오버레이 요청에는 로그인·CSRF를 붙이지 않고 Origin과 Bearer만 싣는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OverlayApiTest {

    private static final String DEPLOY = "http://localhost:4173";
    private static final String PAY = "{\"selector\":\"#pay\",\"text\":\"결제하기\",\"box\":{\"x\":540,\"y\":1380,\"width\":200,\"height\":48}}";

    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired JsonMapper json;
    @Autowired ProjectRepository projects;
    @Autowired MemberRepository members;
    @Autowired ProjectMemberRepository projectMembers;
    @Autowired QAIssueRepository issues;

    /** LiveElementApiTest와 같은 가짜 묶음 — 같은 스프링 컨텍스트를 함께 써 DB 연결을 늘리지 않는다 */
    @MockitoBean PageCapturer capturer;
    @MockitoBean GithubClient github;
    @MockitoBean EmbedChecker embedChecker;
    @MockitoBean FigmaClient figma;

    Project project;
    Member owner;
    Member front;
    Member outsider;

    @BeforeEach
    void setUp() {
        owner = members.save(new Member("ov-owner@test.dev", "소유자"));
        front = members.save(new Member("ov-front@test.dev", "프론트"));
        outsider = members.save(new Member("ov-out@test.dev", "외부인"));
        project = projects.save(new Project("오버레이", owner));
        projectMembers.save(new ProjectMember(project, owner, null));
        projectMembers.save(new ProjectMember(project, front, MemberRole.FRONTEND));
        project.changeDeployUrl(DEPLOY, Instant.now());
        em.flush();
        when(embedChecker.check(anyString())).thenReturn(new EmbedChecker.Result(true, null));
        when(github.latestDeploymentUrl(anyString(), anyString())).thenReturn(Optional.empty());
    }

    private <B extends AbstractMockHttpServletRequestBuilder<B>> B as(B req, Member who) {
        return req.with(oauth2Login().attributes(a -> a.put("email", who.getEmail())))
            .cookie(new Cookie("XSRF-TOKEN", "t")).header("X-XSRF-TOKEN", "t");
    }

    private String openSession(Member who) throws Exception {
        String body = mvc.perform(as(post("/api/v1/projects/" + project.getId() + "/overlay-sessions"), who)
                .contentType(MediaType.APPLICATION_JSON).content("{\"page_path\":\"/checkout\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data").path("token").asString();
    }

    /** 배포 사이트의 툴바가 보내는 요청 — 쿠키·CSRF 없이 Origin과 Bearer만 */
    private <B extends AbstractMockHttpServletRequestBuilder<B>> B fromSite(B req, String token, String origin) {
        B r = req.header("Origin", origin);
        return token == null ? r : r.header("Authorization", "Bearer " + token);
    }

    private static String registerBody(String requestId, String basis) {
        return """
            {"client_request_id":"%s","title":"결제 버튼 색이 달라요","description":"결제하기 버튼이 회색으로 보입니다.",
             "page_path":"/checkout","anchor":{"x_px":640,"y_px":1400},"viewport":{"width":1280,"height":720},
             "location_basis":"%s","capture_status":"none","element":%s}""".formatted(requestId, basis, PAY);
    }

    @Test
    void 멤버는_입장권과_배포_사이트에서_열_주소를_받는다() throws Exception {
        mvc.perform(as(post("/api/v1/projects/" + project.getId() + "/overlay-sessions"), front)
                .contentType(MediaType.APPLICATION_JSON).content("{\"page_path\":\"/checkout\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.target_origin").value(DEPLOY))
            .andExpect(jsonPath("$.data.open_url").value(DEPLOY + "/checkout"))
            .andExpect(jsonPath("$.data.token").value(Matchers.not(Matchers.emptyString())))
            .andExpect(jsonPath("$.data.expires_at").exists());
    }

    @Test
    void 비멤버는_입장권을_못_받고_배포_주소가_없으면_409() throws Exception {
        mvc.perform(as(post("/api/v1/projects/" + project.getId() + "/overlay-sessions"), outsider)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
        project.changeDeployUrl(null, Instant.now());
        em.flush();
        mvc.perform(as(post("/api/v1/projects/" + project.getId() + "/overlay-sessions"), owner)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("NOT_READY"));
    }

    @Test
    void 입장권으로_등록하면_위치_기준은_서버가_live_element로_고정하고_핀_조회에_나온다() throws Exception {
        String token = openSession(front);
        long before = issues.count();
        mvc.perform(fromSite(post("/api/v1/overlay/manual-qa"), token, DEPLOY)
                .contentType(MediaType.APPLICATION_JSON).content(registerBody("ov-1", "page_capture")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.source").value("manual"))
            .andExpect(jsonPath("$.data.status").value("needs_check"))
            .andExpect(jsonPath("$.data.reported_by").value(String.valueOf(front.getId())))
            .andExpect(jsonPath("$.data.manual_qa.location_basis").value("live_element"))
            .andExpect(jsonPath("$.data.manual_qa.element.selector").value("#pay"));
        assertThat(issues.count()).isEqualTo(before + 1);

        mvc.perform(fromSite(get("/api/v1/overlay/pins").param("path", "/checkout"), token, DEPLOY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].selector").value("#pay"))
            .andExpect(jsonPath("$.data.items[0].rx").value(0.5))
            .andExpect(jsonPath("$.data.items[0].ry").value(Matchers.closeTo(20.0 / 48, 0.0001)))
            .andExpect(jsonPath("$.data.items[0].status").value("needs_check"));
        mvc.perform(fromSite(get("/api/v1/overlay/pins").param("path", "/other"), token, DEPLOY))
            .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void 툴바_등록은_브라우저가_찍은_캡처와_사람이_적은_재현_절차를_받는다() throws Exception {
        String token = openSession(front);
        String up = mvc.perform(fromSite(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/overlay/captures/upload")
                    .file(new org.springframework.mock.web.MockMultipartFile("file", "capture.png", "image/png", tinyPng())), token, DEPLOY))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String captureId = json.readTree(up).path("data").path("id").asString();
        String body = registerBody("ov-6", "live_element").replace("\"capture_status\":\"none\",",
            "\"capture_id\":\"" + captureId + "\",\"repro_steps\":[\"테스트 계정으로 로그인한다\"],");
        mvc.perform(fromSite(post("/api/v1/overlay/manual-qa"), token, DEPLOY)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.manual_qa.capture_status").value("user_attached"))
            .andExpect(jsonPath("$.data.repro_steps.length()").value(3))
            .andExpect(jsonPath("$.data.repro_steps[0]").value(Matchers.startsWith("'" + DEPLOY + "/checkout'를")))
            .andExpect(jsonPath("$.data.repro_steps[2]").value("테스트 계정으로 로그인한다"));
    }

    private static byte[] tinyPng() throws Exception {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void 입장권이_없거나_위조됐으면_401이고_아무것도_만들지_않는다() throws Exception {
        long before = issues.count();
        mvc.perform(fromSite(post("/api/v1/overlay/manual-qa"), null, DEPLOY)
                .contentType(MediaType.APPLICATION_JSON).content(registerBody("ov-2", "live_element")))
            .andExpect(status().isUnauthorized());
        String token = openSession(front);
        String forged = token.substring(0, token.indexOf('.') + 1) + "AAAA";
        mvc.perform(fromSite(post("/api/v1/overlay/manual-qa"), forged, DEPLOY)
                .contentType(MediaType.APPLICATION_JSON).content(registerBody("ov-3", "live_element")))
            .andExpect(status().isUnauthorized());
        assertThat(issues.count()).isEqualTo(before);
    }

    @Test
    void 다른_사이트에서_쓰거나_멤버에서_빠지면_403이고_아무것도_만들지_않는다() throws Exception {
        String token = openSession(front);
        long before = issues.count();
        mvc.perform(fromSite(post("/api/v1/overlay/manual-qa"), token, "https://evil.example")
                .contentType(MediaType.APPLICATION_JSON).content(registerBody("ov-4", "live_element")))
            .andExpect(status().isForbidden());
        mvc.perform(fromSite(get("/api/v1/overlay/context"), token, "https://evil.example"))
            .andExpect(status().isForbidden());

        projectMembers.delete(projectMembers.findByProjectIdAndMemberId(project.getId(), front.getId()).orElseThrow());
        em.flush();
        mvc.perform(fromSite(post("/api/v1/overlay/manual-qa"), token, DEPLOY)
                .contentType(MediaType.APPLICATION_JSON).content(registerBody("ov-5", "live_element")))
            .andExpect(status().isForbidden());
        assertThat(issues.count()).isEqualTo(before);
    }

    @Test
    void 입장권으로는_프로젝트_API를_부를_수_없다() throws Exception {
        String token = openSession(front);
        mvc.perform(fromSite(patch("/api/v1/projects/" + project.getId() + "/qa-issues/1"), token, DEPLOY)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"fixed\"}"))
            .andExpect(status().is(Matchers.anyOf(Matchers.is(401), Matchers.is(403))));
        // 세션이 없어 401이거나, 배포 출처가 QAting CORS 허용 목록에 없어 403 — 어느 쪽이든 데이터를 주지 않는다
        mvc.perform(fromSite(get("/api/v1/projects/" + project.getId() + "/qa-issues"), token, DEPLOY))
            .andExpect(status().is(Matchers.anyOf(Matchers.is(401), Matchers.is(403))))
            .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void 툴바_문맥은_프로젝트_이름과_내_이름과_배포_출처를_준다() throws Exception {
        String token = openSession(front);
        mvc.perform(fromSite(get("/api/v1/overlay/context"), token, DEPLOY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.project_name").value("오버레이"))
            .andExpect(jsonPath("$.data.member_name").value("프론트"))
            .andExpect(jsonPath("$.data.target_origin").value(DEPLOY));
    }

    @Test
    void 배포_사이트의_CORS_사전_요청에_답한다() throws Exception {
        mvc.perform(options("/api/v1/overlay/manual-qa").header("Origin", DEPLOY)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", DEPLOY))
            .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }
}
