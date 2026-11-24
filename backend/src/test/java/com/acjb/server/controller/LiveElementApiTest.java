package com.acjb.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;

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

import com.acjb.server.domain.CaptureKind;
import com.acjb.server.domain.Member;
import com.acjb.server.domain.MemberRole;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.ProjectMember;
import com.acjb.server.domain.QaCapture;
import com.acjb.server.figma.FigmaClient;
import com.acjb.server.github.GithubClient;
import com.acjb.server.golden.GoldenCase;
import com.acjb.server.liveqa.EmbedChecker;
import com.acjb.server.liveqa.PageCapturer;
import com.acjb.server.repository.ManualQaPinRepository;
import com.acjb.server.repository.MemberRepository;
import com.acjb.server.repository.NotificationRepository;
import com.acjb.server.repository.ProjectMemberRepository;
import com.acjb.server.repository.ProjectRepository;
import com.acjb.server.repository.QAIssueRepository;
import com.acjb.server.repository.QaCaptureRepository;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;

/**
 * 스니펫(embed.js)으로 실시간 배포 화면의 요소를 골라 수동 QA 등록(2026-11-20, AC31 live_element).
 * 핀은 그 요소에 붙어 실시간 화면을 따라간다 — 서버는 사람이 고른 요소·점을 형식대로 받아 저장하고, 요소 밖의 점이나 빈 요소는 거부한다
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LiveElementApiTest {

    private static final String DEPLOY = "http://localhost:4173";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};

    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired ProjectRepository projects;
    @Autowired MemberRepository members;
    @Autowired ProjectMemberRepository projectMembers;
    @Autowired QAIssueRepository issues;
    @Autowired ManualQaPinRepository pins;
    @Autowired QaCaptureRepository captures;
    @Autowired NotificationRepository notifications;

    @MockitoBean PageCapturer capturer;
    @MockitoBean GithubClient github;
    @MockitoBean EmbedChecker embedChecker;
    /** LiveQaApiTest와 같은 가짜 묶음 — 같은 스프링 컨텍스트를 함께 써 DB 연결을 늘리지 않는다 */
    @MockitoBean FigmaClient figma;

    Project project;
    Member owner;
    Member front;

    @BeforeEach
    void setUp() {
        owner = members.save(new Member("le-owner@test.dev", "소유자"));
        front = members.save(new Member("le-front@test.dev", "프론트"));
        project = projects.save(new Project("스니펫 등록", owner));
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

    private String base() {
        return "/api/v1/projects/" + project.getId();
    }

    /** 스니펫이 보낸 것과 같은 모양 — 1280×720 화면, 첫 화면 아래(y 1380)의 결제 버튼 */
    private static String body(String requestId, int x, int y, String element, String extra) {
        return """
            {"client_request_id":"%s","title":"결제 버튼 색이 달라요","description":"결제하기 버튼이 회색으로 보입니다.",
             "page_path":"/checkout","anchor":{"x_px":%d,"y_px":%d},"viewport":{"width":1280,"height":720},
             "location_basis":"live_element","element":%s%s}""".formatted(requestId, x, y, element, extra);
    }

    private static final String PAY = "{\"selector\":\"#pay\",\"text\":\"결제하기\",\"box\":{\"x\":540,\"y\":1380,\"width\":200,\"height\":48}}";

    @Test
    @GoldenCase("mq-008")
    void 스니펫으로_고른_요소와_점을_핀에_남기고_재현_절차를_그_요소로_만든다() throws Exception {
        long notificationsBefore = notifications.count();
        mvc.perform(as(post(base() + "/manual-qa"), front).contentType(MediaType.APPLICATION_JSON)
                .content(body("le-1", 640, 1400, PAY, ",\"capture_status\":\"none\"")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.source").value("manual"))
            .andExpect(jsonPath("$.data.status").value("needs_check"))
            .andExpect(jsonPath("$.data.screen_location").value("/checkout (x 640, y 1400 · 페이지 맨 위 기준 · 1280×720 화면)"))
            .andExpect(jsonPath("$.data.repro_steps[0]").value("'" + DEPLOY + "/checkout'를 1280×720 화면으로 연다"))
            .andExpect(jsonPath("$.data.repro_steps[1]").value("페이지 맨 위에서 (x 640, y 1400) 위치의 「결제하기」 요소(#pay)를 확인한다"))
            .andExpect(jsonPath("$.data.manual_qa.location_basis").value("live_element"))
            .andExpect(jsonPath("$.data.manual_qa.page_url").value(DEPLOY + "/checkout"))
            .andExpect(jsonPath("$.data.manual_qa.capture_status").value("none"))
            .andExpect(jsonPath("$.data.manual_qa.element.selector").value("#pay"))
            .andExpect(jsonPath("$.data.manual_qa.element.text").value("결제하기"))
            .andExpect(jsonPath("$.data.manual_qa.element.box.y").value(1380))
            .andExpect(jsonPath("$.data.manual_qa.anchor.x_px").value(640))
            .andExpect(jsonPath("$.data.manual_qa.anchor.y_px").value(1400));
        assertThat(notifications.count()).isEqualTo(notificationsBefore);
    }

    @Test
    void 전체_페이지_증거_캡처를_붙이면_핀_화면_크기를_캡처_크기로_둔다() throws Exception {
        QaCapture shot = captures.save(new QaCapture(project, CaptureKind.AUTO, "image/png", DEPLOY + "/checkout", 1280, 2400, PNG,
            front, Instant.now(), true, 720, "[]"));
        mvc.perform(as(post(base() + "/manual-qa"), front).contentType(MediaType.APPLICATION_JSON)
                .content(body("le-2", 640, 1400, PAY, ",\"capture_id\":\"" + shot.getId() + "\"")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.manual_qa.capture_status").value("captured"))
            .andExpect(jsonPath("$.data.manual_qa.viewport.height").value(2400))
            .andExpect(jsonPath("$.data.manual_qa.anchor.y_ratio").value(0.5833));
    }

    @Test
    @GoldenCase("mq-009")
    void 요소가_없거나_점이_요소_밖이거나_형식이_틀리면_400이고_아무것도_저장하지_않는다() throws Exception {
        QaCapture upload = captures.save(new QaCapture(project, CaptureKind.UPLOAD, "image/png", null, 1280, 720, PNG,
            front, Instant.now()));
        long before = issues.count();
        String[] bad = {
            body("b1", 640, 1400, "null", ""),                                                                      // 요소 없음
            body("b2", 10, 10, PAY, ""),                                                                            // 요소 밖의 점
            body("b3", 640, 1400, "{\"selector\":\" \",\"text\":\"\",\"box\":{\"x\":540,\"y\":1380,\"width\":200,\"height\":48}}", ""),
            body("b4", 640, 1400, "{\"selector\":\"#pay\",\"text\":\"\",\"box\":{\"x\":540,\"y\":1380,\"width\":0,\"height\":48}}", ""),
            body("b5", 640, 1400, "{\"selector\":\"" + "a".repeat(501) + "\",\"text\":\"\",\"box\":{\"x\":540,\"y\":1380,\"width\":200,\"height\":48}}", ""),
            body("b6", 1300, 1400, PAY, ""),                                                                        // 화면 너비 밖
            body("b7", 640, 1400, PAY, ",\"capture_id\":\"" + upload.getId() + "\""),                               // 첨부 이미지는 증거 캡처가 아니다
            body("b8", 640, 1400, PAY, ",\"repro_steps\":[\"직접 적은 절차\"]"),
            body("b9", 640, 1400, PAY, "").replace("\"location_basis\":\"live_element\"", "\"location_basis\":\"live_overlay\""), // 요소는 live_element만
        };
        for (String b : bad) {
            mvc.perform(as(post(base() + "/manual-qa"), front).contentType(MediaType.APPLICATION_JSON).content(b))
                .andExpect(status().isBadRequest());
        }
        assertThat(issues.count()).isEqualTo(before);
        assertThat(pins.count()).isZero();
    }

    @Test
    void 배포_주소가_없으면_409_NOT_READY() throws Exception {
        project.changeDeployUrl(null, Instant.now());
        em.flush();
        mvc.perform(as(post(base() + "/manual-qa"), front).contentType(MediaType.APPLICATION_JSON).content(body("le-3", 640, 1400, PAY, "")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("NOT_READY"));
        assertThat(pins.count()).isZero();
    }
}
