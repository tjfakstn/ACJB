package com.acjb.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.acjb.server.domain.CaptureKind;
import com.acjb.server.domain.Member;
import com.acjb.server.domain.MemberRole;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.ProjectMember;
import com.acjb.server.domain.ProjectRun;
import com.acjb.server.domain.QaCapture;
import com.acjb.server.domain.DesignCodeMapping;
import com.acjb.server.domain.RunKind;
import com.acjb.server.domain.RunStatus;
import com.acjb.server.figma.FigmaApiException;
import com.acjb.server.figma.FigmaClient;
import com.acjb.server.github.GithubApiException;
import com.acjb.server.github.GithubClient;
import com.acjb.server.liveqa.CaptureFailedException;
import com.acjb.server.liveqa.EmbedChecker;
import com.acjb.server.liveqa.PageCapturer;
import com.acjb.server.repository.DesignCodeMappingRepository;
import com.acjb.server.repository.ManualQaPinRepository;
import com.acjb.server.repository.MemberRepository;
import com.acjb.server.repository.NotificationRepository;
import com.acjb.server.repository.ProjectMemberRepository;
import com.acjb.server.repository.ProjectRepository;
import com.acjb.server.repository.QAIssueRepository;
import com.acjb.server.repository.ProjectRunRepository;
import com.acjb.server.repository.QaCaptureRepository;
import com.acjb.server.repository.ScreenFrameLinkRepository;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;

/**
 * 실시간 QA 워크스페이스 AC29(배포 대상)·AC30(캡처·첨부)·AC31(수동 QA 등록)·AC32(프레임 미리보기)·AC33(화면 경로 ↔ 프레임)·N19.
 * 서버 브라우저(PageCapturer)·GitHub·헤더 확인(EmbedChecker)은 가짜로 바꿔 끼운다 — 외부 네트워크를 쓰지 않는다
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LiveQaApiTest {

    private static final String DEPLOY = "http://localhost:4173";

    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired ProjectRepository projects;
    @Autowired MemberRepository members;
    @Autowired ProjectMemberRepository projectMembers;
    @Autowired QAIssueRepository issues;
    @Autowired ManualQaPinRepository pins;
    @Autowired QaCaptureRepository captures;
    @Autowired NotificationRepository notifications;
    @Autowired ProjectRunRepository runs;
    @Autowired DesignCodeMappingRepository mappings;
    @Autowired ScreenFrameLinkRepository links;
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
        owner = members.save(new Member("lq-owner@test.dev", "소유자"));
        front = members.save(new Member("lq-front@test.dev", "프론트"));
        outsider = members.save(new Member("lq-out@test.dev", "외부인"));
        project = projects.save(new Project("실시간 QA", owner));
        projectMembers.save(new ProjectMember(project, owner, null));
        projectMembers.save(new ProjectMember(project, front, MemberRole.FRONTEND));
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

    private void deployed() {
        project.changeDeployUrl(DEPLOY, Instant.now());
        em.flush();
    }

    private static byte[] png(int w, int h) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    private static String manualBody(String requestId, String extra) {
        return """
            {"client_request_id":"%s","title":"로그인 버튼 클릭 시 화면이 이동하지 않음","description":"로그인 버튼을 눌러도 페이지가 바뀌지 않습니다.",
             "page_path":"/","anchor":{"x_px":1058,"y_px":35},"viewport":{"width":1280,"height":720},"location_basis":"live_overlay"%s}"""
            .formatted(requestId, extra);
    }

    // ---------------------------------------------------------------- AC29 배포 대상

    @Test
    void 소유자가_배포_주소를_저장하면_manual로_돌려준다() throws Exception {
        mvc.perform(as(put(base() + "/deploy-url"), owner).contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"" + DEPLOY + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.url").value(DEPLOY))
            .andExpect(jsonPath("$.data.source").value("manual"))
            .andExpect(jsonPath("$.data.manual_url").value(DEPLOY))
            .andExpect(jsonPath("$.data.embeddable").value(true));
        em.flush();
        em.clear();
        assertThat(projects.findById(project.getId()).orElseThrow().getDeployUrl()).isEqualTo(DEPLOY);
    }

    @Test
    void 소유자가_아니면_배포_주소를_바꿀_수_없다() throws Exception {
        mvc.perform(as(put(base() + "/deploy-url"), front).contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"" + DEPLOY + "\"}"))
            .andExpect(status().isForbidden());
        em.clear();
        assertThat(projects.findById(project.getId()).orElseThrow().getDeployUrl()).isNull();
    }

    @Test
    void 내부망_주소나_http가_아닌_주소는_400이고_저장하지_않는다() throws Exception {
        for (String url : new String[] {"http://10.0.0.5/", "http://169.254.169.254/latest/", "file:///etc/passwd", "ftp://x.dev"}) {
            mvc.perform(as(put(base() + "/deploy-url"), owner).contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"" + url + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        em.clear();
        assertThat(projects.findById(project.getId()).orElseThrow().getDeployUrl()).isNull();
    }

    @Test
    void 저장된_주소가_없으면_GitHub_최근_성공_배포_주소를_쓴다() throws Exception {
        project.connectGithub("https://github.com/o/r", "o/r", "main", "main", Instant.now());
        em.flush();
        when(github.latestDeploymentUrl("o", "r")).thenReturn(Optional.of("http://localhost:4174"));
        mvc.perform(as(get(base() + "/deploy-target"), front))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.url").value("http://localhost:4174"))
            .andExpect(jsonPath("$.data.source").value("github_deployment"))
            .andExpect(jsonPath("$.data.manual_url").doesNotExist());
    }

    @Test
    void 저장된_주소가_GitHub_배포보다_우선이고_url_null이면_지운다() throws Exception {
        project.connectGithub("https://github.com/o/r", "o/r", "main", "main", Instant.now());
        deployed();
        when(github.latestDeploymentUrl("o", "r")).thenReturn(Optional.of("http://localhost:4174"));
        mvc.perform(as(get(base() + "/deploy-target"), front)).andExpect(jsonPath("$.data.source").value("manual"));
        mvc.perform(as(put(base() + "/deploy-url"), owner).contentType(MediaType.APPLICATION_JSON).content("{\"url\":null}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.source").value("github_deployment"));
    }

    @Test
    void 배포_기록이_없거나_GitHub_오류면_none이다() throws Exception {
        project.connectGithub("https://github.com/o/r", "o/r", "main", "main", Instant.now());
        em.flush();
        mvc.perform(as(get(base() + "/deploy-target"), front))
            .andExpect(jsonPath("$.data.url").doesNotExist())
            .andExpect(jsonPath("$.data.source").value("none"));
        when(github.latestDeploymentUrl("o", "r")).thenThrow(new GithubApiException("x", 500, null));
        mvc.perform(as(get(base() + "/deploy-target"), front))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.source").value("none"));
        verify(embedChecker, never()).check(anyString());
    }

    @Test
    void GitHub가_준_내부망_주소는_쓰지_않는다() throws Exception {
        project.connectGithub("https://github.com/o/r", "o/r", "main", "main", Instant.now());
        em.flush();
        when(github.latestDeploymentUrl("o", "r")).thenReturn(Optional.of("http://169.254.169.254/"));
        mvc.perform(as(get(base() + "/deploy-target"), front)).andExpect(jsonPath("$.data.source").value("none"));
    }

    @Test
    void 화면_삽입이_막혔으면_embeddable_false와_이유를_준다() throws Exception {
        deployed();
        when(embedChecker.check(DEPLOY)).thenReturn(new EmbedChecker.Result(false, "X-Frame-Options: DENY"));
        mvc.perform(as(get(base() + "/deploy-target"), front))
            .andExpect(jsonPath("$.data.embeddable").value(false))
            .andExpect(jsonPath("$.data.embed_blocked_reason").value("X-Frame-Options: DENY"));
    }

    @Test
    void 비멤버는_배포_대상을_볼_수_없다() throws Exception {
        mvc.perform(as(get(base() + "/deploy-target"), outsider)).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- AC30 캡처·첨부

    @Test
    void 배포_주소가_없으면_캡처는_409_NOT_READY() throws Exception {
        mvc.perform(as(post(base() + "/captures"), front).contentType(MediaType.APPLICATION_JSON)
                .content("{\"page_path\":\"/\",\"viewport\":{\"width\":1280,\"height\":720}}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("NOT_READY"));
        assertThat(captures.count()).isZero();
    }

    @Test
    void 배포_주소에_경로를_붙여_같은_크기로_찍고_저장한다() throws Exception {
        deployed();
        byte[] shot = png(1280, 720);
        when(capturer.capture(DEPLOY + "/pricing", 1280, 720)).thenReturn(shot);
        String body = mvc.perform(as(post(base() + "/captures"), front).contentType(MediaType.APPLICATION_JSON)
                .content("{\"page_path\":\"/pricing\",\"viewport\":{\"width\":1280,\"height\":720}}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.kind").value("auto"))
            .andExpect(jsonPath("$.data.page_url").value(DEPLOY + "/pricing"))
            .andExpect(jsonPath("$.data.width").value(1280))
            .andReturn().getResponse().getContentAsString();
        String id = body.replaceAll(".*\"id\":\"(\\d+)\".*", "$1");
        mvc.perform(as(get(base() + "/captures/" + id + "/image"), owner))
            .andExpect(status().isOk())
            .andExpect(content().contentType("image/png"))
            .andExpect(content().bytes(shot));
    }

    @Test
    void 캡처에_실패하면_502_CAPTURE_FAILED이고_저장하지_않는다() throws Exception {
        deployed();
        when(capturer.capture(anyString(), anyInt(), anyInt())).thenThrow(new CaptureFailedException("배포 화면이 시간 안에 열리지 않았어요."));
        mvc.perform(as(post(base() + "/captures"), front).contentType(MediaType.APPLICATION_JSON)
                .content("{\"page_path\":\"/\",\"viewport\":{\"width\":1280,\"height\":720}}"))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.error.code").value("CAPTURE_FAILED"))
            .andExpect(jsonPath("$.error.message").value("배포 화면이 시간 안에 열리지 않았어요."));
        assertThat(captures.count()).isZero();
    }

    @Test
    void 캡처_요청의_경로나_크기가_잘못되면_400() throws Exception {
        deployed();
        for (String body : new String[] {
            "{\"page_path\":\"pricing\",\"viewport\":{\"width\":1280,\"height\":720}}",
            "{\"page_path\":\"//evil.dev/x\",\"viewport\":{\"width\":1280,\"height\":720}}",
            "{\"page_path\":\"/\",\"viewport\":{\"width\":100,\"height\":720}}",
            "{\"page_path\":\"/\"}"}) {
            mvc.perform(as(post(base() + "/captures"), front).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        }
        verify(capturer, never()).capture(anyString(), anyInt(), anyInt());
    }

    @Test
    void 이미지를_첨부하면_내용으로_형식을_확인해_upload로_저장한다() throws Exception {
        mvc.perform(as(multipart(base() + "/captures/upload").file(new MockMultipartFile("file", "a.png", "image/png", png(800, 600))), front))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.kind").value("upload"))
            .andExpect(jsonPath("$.data.width").value(800))
            .andExpect(jsonPath("$.data.height").value(600));
        // 이름·Content-Type이 이미지여도 내용이 아니면 400
        mvc.perform(as(multipart(base() + "/captures/upload")
                .file(new MockMultipartFile("file", "fake.png", "image/png", "<svg onload=alert(1)>".getBytes())), front))
            .andExpect(status().isBadRequest());
        assertThat(captures.count()).isEqualTo(1);
    }

    @Test
    void 다른_프로젝트의_캡처_이미지는_404() throws Exception {
        Project other = projects.save(new Project("다른 프로젝트", outsider));
        projectMembers.save(new ProjectMember(other, outsider, null));
        projectMembers.save(new ProjectMember(other, front, MemberRole.FRONTEND));
        QaCapture c = captures.save(new QaCapture(other, CaptureKind.UPLOAD, "image/png", null, 10, 10, png(10, 10), outsider, Instant.now()));
        mvc.perform(as(get(base() + "/captures/" + c.getId() + "/image"), front)).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- AC31 수동 QA 등록

    @Test
    void 수동_QA를_등록하면_위치에서_재현_정보를_만들고_핀_1번을_발급한다() throws Exception {
        deployed();
        long notificationsBefore = notifications.count();
        mvc.perform(as(post(base() + "/manual-qa"), front).contentType(MediaType.APPLICATION_JSON)
                .content(manualBody("req-1", ",\"severity\":\"high\",\"expected_result\":\"로그인 화면으로 이동\"")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.source").value("manual"))
            .andExpect(jsonPath("$.data.status").value("needs_check"))
            .andExpect(jsonPath("$.data.assigned_team").doesNotExist())
            .andExpect(jsonPath("$.data.platform_scope").value("common"))
            .andExpect(jsonPath("$.data.environment.platform").value("web"))
            .andExpect(jsonPath("$.data.description").value("로그인 버튼을 눌러도 페이지가 바뀌지 않습니다."))
            .andExpect(jsonPath("$.data.screen_location").value("/ (x 1058, y 35 · 1280×720)"))
            .andExpect(jsonPath("$.data.repro_steps[0]").value("'" + DEPLOY + "/'를 1280×720 화면으로 연다"))
            .andExpect(jsonPath("$.data.repro_steps[1]").value("(x 1058, y 35) 위치를 확인한다"))
            .andExpect(jsonPath("$.data.reported_by").value(String.valueOf(front.getId())))
            .andExpect(jsonPath("$.data.status_history.length()").value(1))
            .andExpect(jsonPath("$.data.manual_qa.pin_number").value(1))
            .andExpect(jsonPath("$.data.manual_qa.title").value("로그인 버튼 클릭 시 화면이 이동하지 않음"))
            .andExpect(jsonPath("$.data.manual_qa.page_url").value(DEPLOY + "/"))
            .andExpect(jsonPath("$.data.manual_qa.anchor.x_ratio").value(0.8266))
            .andExpect(jsonPath("$.data.manual_qa.location_basis").value("live_overlay"))
            .andExpect(jsonPath("$.data.manual_qa.capture_status").value("none"))
            .andExpect(jsonPath("$.data.manual_qa.severity").value("high"))
            .andExpect(jsonPath("$.data.manual_qa.expected_result").value("로그인 화면으로 이동"))
            .andExpect(jsonPath("$.data.manual_qa.actual_result").doesNotExist());
        // 담당 팀이 없으므로 알림 없음(AC3)
        assertThat(notifications.count()).isEqualTo(notificationsBefore);
        mvc.perform(as(post(base() + "/manual-qa"), owner).contentType(MediaType.APPLICATION_JSON).content(manualBody("req-2", "")))
            .andExpect(jsonPath("$.data.manual_qa.pin_number").value(2));
    }

    @Test
    void 같은_요청_id로_다시_보내면_새로_만들지_않고_200으로_처음_것을_준다() throws Exception {
        deployed();
        String first = mvc.perform(as(post(base() + "/manual-qa"), front).contentType(MediaType.APPLICATION_JSON).content(manualBody("same", "")))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = first.replaceAll("^\\{\"success\":true,\"data\":\\{\"id\":\"(\\d+)\".*", "$1");
        mvc.perform(as(post(base() + "/manual-qa"), front).contentType(MediaType.APPLICATION_JSON).content(manualBody("same", "")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(id))
            .andExpect(jsonPath("$.data.manual_qa.pin_number").value(1));
        assertThat(pins.count()).isEqualTo(1);
    }

    @Test
    void 필수값이_비거나_값이_잘못되면_400이고_아무것도_저장하지_않는다() throws Exception {
        deployed();
        long before = issues.count();
        String[] bad = {
            manualBody("r1", "").replace("로그인 버튼 클릭 시 화면이 이동하지 않음", "   "),
            manualBody("r2", "").replace("로그인 버튼을 눌러도 페이지가 바뀌지 않습니다.", ""),
            manualBody("r3", "").replace("\"x_px\":1058", "\"x_px\":1280"),
            manualBody("r4", "").replace("\"page_path\":\"/\"", "\"page_path\":\"login\""),
            manualBody("r5", "").replace("live_overlay", "somewhere"),
            manualBody("r6", ",\"severity\":\"urgent\""),
            manualBody("r7", "").replace("live_overlay", "capture_image"),
            manualBody("r8", ",\"capture_status\":\"captured\""),
            manualBody("bad id!", ""),
            manualBody("r9", "").replace("로그인 버튼 클릭 시 화면이 이동하지 않음", "가".repeat(101)),
        };
        for (String body : bad) {
            mvc.perform(as(post(base() + "/manual-qa"), front).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        assertThat(issues.count()).isEqualTo(before);
        assertThat(pins.count()).isZero();
    }

    @Test
    void 캡처를_연결하면_종류로_capture_status를_정하고_다른_프로젝트_캡처는_400() throws Exception {
        deployed();
        QaCapture auto = captures.save(new QaCapture(project, CaptureKind.AUTO, "image/png", DEPLOY + "/", 1280, 720, png(4, 4), front,
            Instant.now()));
        mvc.perform(as(post(base() + "/manual-qa"), front).contentType(MediaType.APPLICATION_JSON)
                .content(manualBody("c1", ",\"capture_id\":\"" + auto.getId() + "\"")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.manual_qa.capture_status").value("captured"))
            .andExpect(jsonPath("$.data.manual_qa.capture_id").value(String.valueOf(auto.getId())));

        Project other = projects.save(new Project("남의 프로젝트", outsider));
        QaCapture foreign = captures.save(new QaCapture(other, CaptureKind.UPLOAD, "image/png", null, 4, 4, png(4, 4), outsider,
            Instant.now()));
        mvc.perform(as(post(base() + "/manual-qa"), front).contentType(MediaType.APPLICATION_JSON)
                .content(manualBody("c2", ",\"capture_id\":\"" + foreign.getId() + "\"")))
            .andExpect(status().isBadRequest());
        assertThat(pins.count()).isEqualTo(1);
    }

    @Test
    void 캡처에_실패했으면_failed로_남기고_첨부_이미지_위치는_이미지_기준_재현_절차를_만든다() throws Exception {
        deployed();
        mvc.perform(as(post(base() + "/manual-qa"), front).contentType(MediaType.APPLICATION_JSON)
                .content(manualBody("f1", ",\"capture_status\":\"failed\"")))
            .andExpect(jsonPath("$.data.manual_qa.capture_status").value("failed"))
            .andExpect(jsonPath("$.data.manual_qa.capture_id").doesNotExist());

        QaCapture upload = captures.save(new QaCapture(project, CaptureKind.UPLOAD, "image/png", null, 1280, 720, png(4, 4), front,
            Instant.now()));
        mvc.perform(as(post(base() + "/manual-qa"), front).contentType(MediaType.APPLICATION_JSON)
                .content(manualBody("i1", ",\"capture_id\":\"" + upload.getId() + "\"").replace("live_overlay", "capture_image")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.manual_qa.capture_status").value("user_attached"))
            .andExpect(jsonPath("$.data.manual_qa.location_basis").value("capture_image"))
            .andExpect(jsonPath("$.data.manual_qa.page_url").doesNotExist())
            .andExpect(jsonPath("$.data.repro_steps[0]").value("첨부 이미지(캡처 " + upload.getId() + ")를 연다"));
    }

    @Test
    void 배포_주소_없이_실시간_화면_위치로는_등록할_수_없다() throws Exception {
        mvc.perform(as(post(base() + "/manual-qa"), front).contentType(MediaType.APPLICATION_JSON).content(manualBody("n1", "")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("NOT_READY"));
        assertThat(pins.count()).isZero();
    }

    @Test
    void 목록과_상세에_manual_qa가_담기고_첫_QA_이슈는_null이다() throws Exception {
        deployed();
        mvc.perform(as(post(base() + "/manual-qa"), front).contentType(MediaType.APPLICATION_JSON).content(manualBody("l1", "")));
        mvc.perform(as(post(base() + "/qa-issues"), front).contentType(MediaType.APPLICATION_JSON).content("""
                {"description":"d","screen_location":"홈 / 버튼","repro_steps":["열기"],"platform_scope":"common"}"""))
            .andExpect(status().isCreated());
        mvc.perform(as(get(base() + "/qa-issues"), owner))
            .andExpect(jsonPath("$.data.items.length()").value(2))
            // created_at desc — 나중에 등록한 일반 이슈가 먼저
            .andExpect(jsonPath("$.data.items[0].screen_location").value("홈 / 버튼"))
            .andExpect(jsonPath("$.data.items[0].manual_qa").doesNotExist())
            .andExpect(jsonPath("$.data.items[1].manual_qa.pin_number").value(1));
    }

    @Test
    void 비멤버는_수동_QA를_등록할_수_없다() throws Exception {
        deployed();
        mvc.perform(as(post(base() + "/manual-qa"), outsider).contentType(MediaType.APPLICATION_JSON).content(manualBody("o1", "")))
            .andExpect(status().isForbidden());
        assertThat(pins.count()).isZero();
    }

    @Test
    void 프로젝트를_지우면_핀과_캡처도_함께_지운다() throws Exception {
        deployed();
        QaCapture c = captures.save(new QaCapture(project, CaptureKind.AUTO, "image/png", DEPLOY, 4, 4, png(4, 4), front, Instant.now()));
        mvc.perform(as(post(base() + "/manual-qa"), front).contentType(MediaType.APPLICATION_JSON)
            .content(manualBody("d1", ",\"capture_id\":\"" + c.getId() + "\""))).andExpect(status().isCreated());
        mvc.perform(as(delete(base()), owner)).andExpect(status().isNoContent());
        em.clear();
        assertThat(pins.count()).isZero();
        assertThat(captures.count()).isZero();
        verify(capturer, never()).capture(eq(DEPLOY), anyInt(), anyInt());
    }

    // ---------------------------------------------------------------- AC32 자동 QA 항목의 Figma 프레임 미리보기

    private void figmaConnected() {
        project.connectFigma("https://www.figma.com/design/KEY/x?node-id=1-1", "KEY", "파일", "1:1", "페이지",
            com.acjb.server.domain.FigmaScope.PAGE, Instant.now());
        em.flush();
        when(figma.isConfigured()).thenReturn(true);
    }

    @Test
    void 프레임_이미지와_노드_상자를_프레임_기준_비율로_주고_잠시_기억한다() throws Exception {
        figmaConnected();
        when(figma.bounds("KEY", List.of("10:1", "10:5"))).thenReturn(Map.of(
            "10:1", new FigmaClient.Box(100, 200, 1280, 720),
            "10:5", new FigmaClient.Box(1100, 210, 128, 72)));
        when(figma.renderUrl("KEY", "10:1", 1)).thenReturn(Optional.of("https://figma-alpha-api.s3/x.png"));
        for (int i = 0; i < 2; i++) {
            mvc.perform(as(get(base() + "/figma/frames/10:1/preview?node_id=10:5"), front))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.image_url").value("https://figma-alpha-api.s3/x.png"))
                .andExpect(jsonPath("$.data.width").value(1280))
                .andExpect(jsonPath("$.data.height").value(720))
                .andExpect(jsonPath("$.data.node_box.x_ratio").value(0.7813))
                .andExpect(jsonPath("$.data.node_box.y_ratio").value(0.0139))
                .andExpect(jsonPath("$.data.node_box.w_ratio").value(0.1))
                .andExpect(jsonPath("$.data.node_box.h_ratio").value(0.1));
        }
        // 두 번째는 기억해 둔 결과 — Figma 호출 한도(429)를 아낀다
        verify(figma, times(1)).renderUrl("KEY", "10:1", 1);
    }

    @Test
    void Figma가_없거나_id가_잘못되거나_프레임이_없으면_거부한다() throws Exception {
        mvc.perform(as(get(base() + "/figma/frames/10:1/preview"), front))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("NOT_READY"));
        figmaConnected();
        mvc.perform(as(get(base() + "/figma/frames/abc/preview"), front)).andExpect(status().isBadRequest());
        when(figma.bounds("KEY", List.of("10:9"))).thenReturn(Map.of());
        mvc.perform(as(get(base() + "/figma/frames/10:9/preview"), front)).andExpect(status().isNotFound());
        when(figma.bounds("KEY", List.of("10:8"))).thenThrow(new FigmaApiException("한도", 429, false, 30, null));
        mvc.perform(as(get(base() + "/figma/frames/10:8/preview"), front))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.error.code").value("CONNECTION_FAILED"))
            .andExpect(jsonPath("$.error.message").value("Figma 요청 한도를 넘었어요. 30초 뒤 다시 시도해 주세요."));
        mvc.perform(as(get(base() + "/figma/frames/10:1/preview"), outsider)).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- AC33 화면 경로 ↔ Figma 프레임

    /** 비교 분석을 성공시키고 프레임 짝을 남긴다. 실패한 더 최근 분석은 기준이 아니다 */
    private void analyzed(String... frames) {
        Instant now = Instant.now();
        ProjectRun ok = runs.save(new ProjectRun(project, RunKind.ANALYSIS, owner, now.minusSeconds(60)));
        ok.start();
        ok.succeed("abc123", frames.length / 2, 0, now.minusSeconds(30));
        for (int i = 0; i < frames.length; i += 2) {
            mappings.save(DesignCodeMapping.frame(project, ok, frames[i], frames[i + 1], "src/pages/X.tsx", now));
        }
        mappings.save(DesignCodeMapping.frame(project, runs.save(failedRun(now)), "99:1", "실패한 분석의 프레임", "src/X.tsx", now));
        em.flush();
    }

    private ProjectRun failedRun(Instant now) {
        ProjectRun r = new ProjectRun(project, RunKind.ANALYSIS, owner, now);
        r.start();
        r.fail("실패", now);
        return r;
    }

    private String linkBody(String path) {
        return "{\"page_path\":\"" + path + "\"}";
    }

    @Test
    void 소유자가_프레임에_경로를_연결하면_끝의_슬래시를_떼고_저장하고_다시_넣으면_바꾼다() throws Exception {
        analyzed("10:1", "LOGIN-01 로그인", "10:2", "LOGIN-02 로그인 실패", "10:3", "WS-01 작업공간");
        mvc.perform(as(put(base() + "/screen-links/10:1"), owner).contentType(MediaType.APPLICATION_JSON).content(linkBody("/login/")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.frame_id").value("10:1"))
            .andExpect(jsonPath("$.data.frame_name").value("LOGIN-01 로그인"))
            .andExpect(jsonPath("$.data.page_path").value("/login"));
        mvc.perform(as(put(base() + "/screen-links/10:2"), owner).contentType(MediaType.APPLICATION_JSON).content(linkBody("/login")))
            .andExpect(status().isOk());
        mvc.perform(as(put(base() + "/screen-links/10:3"), owner).contentType(MediaType.APPLICATION_JSON).content(linkBody("/app/projects/:id")))
            .andExpect(status().isOk());
        mvc.perform(as(put(base() + "/screen-links/10:3"), owner).contentType(MediaType.APPLICATION_JSON).content(linkBody("/")))
            .andExpect(jsonPath("$.data.page_path").value("/"));
        // 멤버 누구나 본다 — 경로, 프레임 이름, 프레임 id 순
        mvc.perform(as(get(base() + "/screen-links"), front))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(3))
            .andExpect(jsonPath("$.data.items[0].frame_id").value("10:3"))
            .andExpect(jsonPath("$.data.items[1].frame_id").value("10:1"))
            .andExpect(jsonPath("$.data.items[2].frame_id").value("10:2"));
        assertThat(links.count()).isEqualTo(3);
    }

    @Test
    void 소유자가_아니거나_매핑에_없는_프레임이거나_경로가_잘못되면_거부하고_바꾸지_않는다() throws Exception {
        analyzed("10:1", "LOGIN-01 로그인");
        mvc.perform(as(put(base() + "/screen-links/10:1"), front).contentType(MediaType.APPLICATION_JSON).content(linkBody("/login")))
            .andExpect(status().isForbidden());
        mvc.perform(as(put(base() + "/screen-links/99:1"), owner).contentType(MediaType.APPLICATION_JSON).content(linkBody("/login")))
            .andExpect(status().isBadRequest());
        mvc.perform(as(put(base() + "/screen-links/abc"), owner).contentType(MediaType.APPLICATION_JSON).content(linkBody("/login")))
            .andExpect(status().isBadRequest());
        for (String bad : List.of("login", "", "/login?x=1", "/a#b", "//evil.com", "/" + "a".repeat(500))) {
            mvc.perform(as(put(base() + "/screen-links/10:1"), owner).contentType(MediaType.APPLICATION_JSON).content(linkBody(bad)))
                .andExpect(status().isBadRequest());
        }
        mvc.perform(as(get(base() + "/screen-links"), outsider)).andExpect(status().isForbidden());
        mvc.perform(as(delete(base() + "/screen-links/10:1"), front)).andExpect(status().isForbidden());
        assertThat(links.count()).isZero();
    }

    @Test
    void 연결을_지우면_204이고_없어도_204다() throws Exception {
        analyzed("10:1", "LOGIN-01 로그인");
        mvc.perform(as(put(base() + "/screen-links/10:1"), owner).contentType(MediaType.APPLICATION_JSON).content(linkBody("/login")))
            .andExpect(status().isOk());
        mvc.perform(as(delete(base() + "/screen-links/10:1"), owner)).andExpect(status().isNoContent());
        mvc.perform(as(delete(base() + "/screen-links/10:1"), owner)).andExpect(status().isNoContent());
        assertThat(links.count()).isZero();
    }

    @Test
    void 수동_QA에_기준_프레임을_보내면_핀과_figma_link에_남기고_없으면_고르지_않는다() throws Exception {
        deployed();
        figmaConnected();
        analyzed("10:1", "LOGIN-01 로그인");
        mvc.perform(as(put(base() + "/screen-links/10:1"), owner).contentType(MediaType.APPLICATION_JSON).content(linkBody("/")))
            .andExpect(status().isOk());
        mvc.perform(as(post(base() + "/manual-qa"), front).contentType(MediaType.APPLICATION_JSON)
                .content(manualBody("f1", ",\"figma_frame_id\":\"10:1\"")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.figma_link").value("https://www.figma.com/design/KEY/?node-id=10-1"))
            .andExpect(jsonPath("$.data.manual_qa.figma_frame.frame_id").value("10:1"))
            .andExpect(jsonPath("$.data.manual_qa.figma_frame.frame_name").value("LOGIN-01 로그인"));
        // 같은 경로에 연결이 있어도 보내지 않았으면 서버가 골라 넣지 않는다(사람이 확인한 값만)
        mvc.perform(as(post(base() + "/manual-qa"), front).contentType(MediaType.APPLICATION_JSON).content(manualBody("f2", "")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.figma_link").doesNotExist())
            .andExpect(jsonPath("$.data.manual_qa.figma_frame").doesNotExist());
        // 최근 성공한 분석에 없는 프레임(실패한 분석에만 있는 것 포함)은 400이고 저장하지 않는다
        long before = pins.count();
        for (String bad : List.of("99:1", "12:34", "abc")) {
            mvc.perform(as(post(base() + "/manual-qa"), front).contentType(MediaType.APPLICATION_JSON)
                    .content(manualBody("bad-" + bad, ",\"figma_frame_id\":\"" + bad + "\"")))
                .andExpect(status().isBadRequest());
        }
        assertThat(pins.count()).isEqualTo(before);
    }

    @Test
    void 프로젝트를_지우면_화면_경로_연결도_지운다() throws Exception {
        analyzed("10:1", "LOGIN-01 로그인");
        mvc.perform(as(put(base() + "/screen-links/10:1"), owner).contentType(MediaType.APPLICATION_JSON).content(linkBody("/login")))
            .andExpect(status().isOk());
        mvc.perform(as(delete(base()), owner)).andExpect(status().isNoContent());
        em.clear();
        assertThat(links.count()).isZero();
    }
}
