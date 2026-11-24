package com.acjb.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
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
import com.acjb.server.domain.PageElement;
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
 * 수동 QA를 서버 캡처 위에서 등록(2026-11-20, AC30 full_page·AC31 page_capture).
 * 배포 화면은 교차 출처 iframe이라 스크롤·요소를 읽을 수 없다 — 서버가 전체 페이지와 요소 위치를 찍고, 핀은 그 캡처 좌표와 요소로 남긴다.
 * 서버 브라우저(PageCapturer)는 가짜로 바꿔 끼운다
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PageCaptureApiTest {

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

    /** 1280×720 화면에서 찍은 2400px 페이지 — 버튼은 첫 화면 아래(y 1500)에 있다 */
    private static final List<PageElement> ELEMENTS = List.of(
        new PageElement("main", "주문 요약 결제하기", 0, 0, 1280, 2400),
        new PageElement("main > section:nth-of-type(2)", "결제하기", 0, 1400, 1280, 300),
        new PageElement("#pay", "결제하기", 540, 1480, 200, 48));

    @BeforeEach
    void setUp() {
        owner = members.save(new Member("pc-owner@test.dev", "소유자"));
        front = members.save(new Member("pc-front@test.dev", "프론트"));
        project = projects.save(new Project("캡처 위 등록", owner));
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

    /** 전체 페이지 캡처를 API로 만들고 id를 돌려준다 */
    private String fullPageCapture(String path) throws Exception {
        when(capturer.captureFullPage(DEPLOY + path, 1280, 720)).thenReturn(new PageCapturer.PageShot(PNG, 2400, ELEMENTS));
        String body = mvc.perform(as(post(base() + "/captures"), front).contentType(MediaType.APPLICATION_JSON)
                .content("{\"page_path\":\"" + path + "\",\"viewport\":{\"width\":1280,\"height\":720},\"full_page\":true}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"id\":\"(\\d+)\".*", "$1");
    }

    private String body(String requestId, String captureId, int x, int y, String extra) {
        return """
            {"client_request_id":"%s","title":"결제 버튼 색이 달라요","description":"결제하기 버튼이 회색으로 보입니다.",
             "page_path":"/checkout","anchor":{"x_px":%d,"y_px":%d},"location_basis":"page_capture","capture_id":"%s"%s}"""
            .formatted(requestId, x, y, captureId, extra);
    }

    @Test
    @GoldenCase("cp-005")
    void full_page로_찍으면_페이지_전체와_요소_위치를_남기고_화면_높이를_따로_준다() throws Exception {
        when(capturer.captureFullPage(DEPLOY + "/checkout", 1280, 720)).thenReturn(new PageCapturer.PageShot(PNG, 2400, ELEMENTS));
        mvc.perform(as(post(base() + "/captures"), front).contentType(MediaType.APPLICATION_JSON)
                .content("{\"page_path\":\"/checkout\",\"viewport\":{\"width\":1280,\"height\":720},\"full_page\":true}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.kind").value("auto"))
            .andExpect(jsonPath("$.data.full_page").value(true))
            .andExpect(jsonPath("$.data.width").value(1280))
            .andExpect(jsonPath("$.data.height").value(2400))
            .andExpect(jsonPath("$.data.viewport_height").value(720))
            .andExpect(jsonPath("$.data.page_url").value(DEPLOY + "/checkout"));
        verify(capturer, never()).capture(anyString(), anyInt(), anyInt());
        em.flush();
        em.clear();
        QaCapture saved = captures.findAll().get(0);
        assertThat(saved.isFullPage()).isTrue();
        assertThat(saved.getElements()).contains("#pay").contains("결제하기");
    }

    @Test
    void full_page를_빼면_예전처럼_첫_화면만_찍는다() throws Exception {
        when(capturer.capture(DEPLOY + "/checkout", 1280, 720)).thenReturn(PNG);
        mvc.perform(as(post(base() + "/captures"), front).contentType(MediaType.APPLICATION_JSON)
                .content("{\"page_path\":\"/checkout\",\"viewport\":{\"width\":1280,\"height\":720}}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.full_page").value(false))
            .andExpect(jsonPath("$.data.height").value(720))
            .andExpect(jsonPath("$.data.viewport_height").doesNotExist());
        verify(capturer, never()).captureFullPage(anyString(), anyInt(), anyInt());
    }

    @Test
    @GoldenCase("mq-006")
    void 캡처_위에서_찍으면_페이지_좌표와_그_점의_가장_작은_요소를_핀에_남긴다() throws Exception {
        String captureId = fullPageCapture("/checkout");
        long notificationsBefore = notifications.count();
        mvc.perform(as(post(base() + "/manual-qa"), front).contentType(MediaType.APPLICATION_JSON)
                .content(body("pc-1", captureId, 600, 1500, "")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.source").value("manual"))
            .andExpect(jsonPath("$.data.status").value("needs_check"))
            .andExpect(jsonPath("$.data.screen_location").value("/checkout (x 600, y 1500 · 페이지 맨 위 기준 · 1280×720 화면)"))
            .andExpect(jsonPath("$.data.repro_steps[0]").value("'" + DEPLOY + "/checkout'를 1280×720 화면으로 연다"))
            .andExpect(jsonPath("$.data.repro_steps[1]").value("페이지 맨 위에서 (x 600, y 1500) 위치의 「결제하기」 요소(#pay)를 확인한다"))
            .andExpect(jsonPath("$.data.manual_qa.location_basis").value("page_capture"))
            .andExpect(jsonPath("$.data.manual_qa.capture_id").value(captureId))
            .andExpect(jsonPath("$.data.manual_qa.capture_status").value("captured"))
            .andExpect(jsonPath("$.data.manual_qa.page_url").value(DEPLOY + "/checkout"))
            // 화면 크기는 캡처 이미지 크기 — 비율은 캡처 기준이라 캡처 위에 그대로 그릴 수 있다
            .andExpect(jsonPath("$.data.manual_qa.viewport.width").value(1280))
            .andExpect(jsonPath("$.data.manual_qa.viewport.height").value(2400))
            .andExpect(jsonPath("$.data.manual_qa.anchor.y_ratio").value(0.625))
            .andExpect(jsonPath("$.data.manual_qa.element.selector").value("#pay"))
            .andExpect(jsonPath("$.data.manual_qa.element.text").value("결제하기"))
            .andExpect(jsonPath("$.data.manual_qa.element.box.x").value(540))
            .andExpect(jsonPath("$.data.manual_qa.element.box.y").value(1480))
            .andExpect(jsonPath("$.data.manual_qa.element.box.width").value(200))
            .andExpect(jsonPath("$.data.manual_qa.element.box.height").value(48));
        // 담당 팀이 없으므로 알림 없음(AC3)
        assertThat(notifications.count()).isEqualTo(notificationsBefore);
    }

    @Test
    void 요소가_없는_빈_곳을_찍으면_요소_없이_좌표만_남긴다() throws Exception {
        when(capturer.captureFullPage(DEPLOY + "/checkout", 1280, 720))
            .thenReturn(new PageCapturer.PageShot(PNG, 2400, List.of(new PageElement("#pay", "결제하기", 540, 1480, 200, 48))));
        String id = mvc.perform(as(post(base() + "/captures"), front).contentType(MediaType.APPLICATION_JSON)
                .content("{\"page_path\":\"/checkout\",\"viewport\":{\"width\":1280,\"height\":720},\"full_page\":true}"))
            .andReturn().getResponse().getContentAsString().replaceAll(".*\"id\":\"(\\d+)\".*", "$1");
        mvc.perform(as(post(base() + "/manual-qa"), front).contentType(MediaType.APPLICATION_JSON).content(body("pc-2", id, 10, 10, "")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.manual_qa.element").doesNotExist())
            .andExpect(jsonPath("$.data.repro_steps[1]").value("페이지 맨 위에서 (x 10, y 10) 위치를 확인한다"));
    }

    @Test
    @GoldenCase("mq-007")
    void page_capture는_이_프로젝트의_전체_페이지_서버_캡처와_그_안의_좌표만_받고_아니면_아무것도_저장하지_않는다() throws Exception {
        String full = fullPageCapture("/checkout");
        when(capturer.capture(DEPLOY + "/checkout", 1280, 720)).thenReturn(PNG);
        String firstScreen = mvc.perform(as(post(base() + "/captures"), front).contentType(MediaType.APPLICATION_JSON)
                .content("{\"page_path\":\"/checkout\",\"viewport\":{\"width\":1280,\"height\":720}}"))
            .andReturn().getResponse().getContentAsString().replaceAll(".*\"id\":\"(\\d+)\".*", "$1");
        Project other = projects.save(new Project("다른 프로젝트", owner));
        QaCapture elsewhere = captures.save(new QaCapture(other, CaptureKind.AUTO, "image/png", DEPLOY + "/checkout", 1280, 2400, PNG,
            owner, Instant.now(), true, 720, "[]"));
        long issuesBefore = issues.count();
        String[] bad = {
            body("b1", full, 600, 1500, "").replace(",\"capture_id\":\"" + full + "\"", ""),           // 캡처 없음
            body("b2", firstScreen, 600, 100, ""),                                                       // 첫 화면만 찍은 캡처
            body("b3", String.valueOf(elsewhere.getId()), 600, 100, ""),                                 // 다른 프로젝트 캡처
            body("b4", full, 600, 2400, ""),                                                             // 캡처 밖 좌표
            body("b5", full, 600, 100, ",\"viewport\":{\"width\":1280,\"height\":720}"),                 // 화면 크기는 캡처가 정한다
            body("b6", full, 600, 100, "").replace("\"/checkout\"", "\"/cart\""),                        // 캡처와 다른 경로
            body("b7", full, 600, 100, ",\"repro_steps\":[\"직접 적은 절차\"]"),                          // 위치에서 만든다
        };
        for (String b : bad) {
            mvc.perform(as(post(base() + "/manual-qa"), front).contentType(MediaType.APPLICATION_JSON).content(b))
                .andExpect(status().isBadRequest());
        }
        assertThat(issues.count()).isEqualTo(issuesBefore);
        assertThat(pins.count()).isZero();
    }
}
