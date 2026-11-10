package com.acjb.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.acjb.server.domain.Member;
import com.acjb.server.domain.MemberRole;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.ProjectMember;
import com.acjb.server.parse.IssueTextParser;
import com.acjb.server.parse.ParseFailedException;
import com.acjb.server.repository.MemberRepository;
import com.acjb.server.repository.NotificationRepository;
import com.acjb.server.repository.ProjectMemberRepository;
import com.acjb.server.repository.ProjectRepository;
import com.acjb.server.repository.QAIssueRepository;
import com.acjb.server.repository.StyleMismatchRepository;

import jakarta.servlet.http.Cookie;

/**
 * POST /projects/{projectId}/qa-issues/parse (AC8, N9). LLM은 가짜(IssueTextParser mock)로 바꿔 끼워
 * "모델이 이런 JSON을 냈을 때 서버가 어떻게 응답하는가"를 검증한다. 모델 자체의 출력 품질은 parse_query.md 점검표가 맡는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ParseApiTest {

    private static final String CSRF = "test-csrf-token";

    @Autowired MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired MemberRepository members;
    @Autowired ProjectMemberRepository projectMembers;
    @Autowired QAIssueRepository issues;
    @Autowired StyleMismatchRepository mismatches;
    @Autowired NotificationRepository notifications;
    @MockitoBean IssueTextParser parser;

    Project project;
    Member me;

    @BeforeEach
    void setUp() {
        project = projects.save(new Project("parse-api", owner()));
        me = members.save(new Member("parse@test.dev", "멤버"));
        projectMembers.save(new ProjectMember(project, me, MemberRole.DESIGNER));
    }

    private ResultActions parse(String body) throws Exception {
        return mvc.perform(post("/api/v1/projects/" + project.getId() + "/qa-issues/parse")
            .with(oauth2Login().attributes(a -> a.put("email", me.getEmail())))
            .cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF)
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void pc001_재현_절차가_없으면_지어내지_않고_missing에_담는다() throws Exception {
        // parse_query.md 예시 2 / 점검표 #2의 실제 모델 출력
        when(parser.parse("아이콘이 너무 커요")).thenReturn("""
            {"screen":null,"element":"아이콘","symptom":"너무 큼","repro_steps":[],"platform_scope":null}""");
        parse("{\"text\":\"아이콘이 너무 커요\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.context").exists())
            .andExpect(jsonPath("$.data.context.screen").isEmpty())
            .andExpect(jsonPath("$.data.context.element").value("아이콘"))
            .andExpect(jsonPath("$.data.context.repro_steps.length()").value(0))
            .andExpect(jsonPath("$.data.missing.length()").value(2))
            .andExpect(jsonPath("$.data.missing[0]").value("screen"))
            .andExpect(jsonPath("$.data.missing[1]").value("repro_steps"));
    }

    @Test
    void 정보가_충분하면_missing이_비어_있다() throws Exception {
        when(parser.parse(anyString())).thenReturn("""
            {"screen":"설정 화면","element":"저장 버튼","symptom":"버튼을 눌러도 반응 없음",
             "repro_steps":["설정 진입","저장 버튼 클릭"],"platform_scope":"ios"}""");
        parse("{\"text\":\"설정 화면에서 저장 버튼을 눌러도 반응이 없어요. 설정 진입 → 저장 버튼 클릭 순서로 재현돼요. 아이폰에서만 그래요.\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.context.platform_scope").value("ios"))
            .andExpect(jsonPath("$.data.context.repro_steps[1]").value("저장 버튼 클릭"))
            .andExpect(jsonPath("$.data.missing.length()").value(0));
    }

    @Test
    void ng009_파싱은_이슈를_만들지_않는다() throws Exception {
        when(parser.parse(anyString())).thenReturn("""
            {"screen":"로그인 화면","element":"버튼","symptom":"앱이 꺼짐","repro_steps":["버튼 누르기"],"platform_scope":"aos"}""");
        parse("{\"text\":\"로그인 화면에서 버튼 누르면 앱이 꺼져요. 안드로이드만 그래요\"}").andExpect(status().isOk());
        assertThat(issues.count()).isZero();
        assertThat(mismatches.count()).isZero();
        assertThat(notifications.count()).isZero();
    }

    // --- 스키마를 벗어난 출력은 실패로 응답한다(N9, AGENTS.md 5절) ---

    @Test
    void 정의되지_않은_키가_있으면_502() throws Exception {
        when(parser.parse(anyString())).thenReturn("""
            {"screen":null,"element":null,"symptom":null,"repro_steps":[],"platform_scope":null,"severity":"high"}""");
        parse("{\"text\":\"t\"}")
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("PARSE_FAILED"))
            .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void enum_밖의_platform_scope면_502() throws Exception {
        when(parser.parse(anyString())).thenReturn("""
            {"screen":null,"element":null,"symptom":null,"repro_steps":[],"platform_scope":"web"}""");
        parse("{\"text\":\"PC 크롬에서만 그래요\"}").andExpect(status().isBadGateway());
    }

    @Test
    void 필수_키가_빠지면_502() throws Exception {
        when(parser.parse(anyString())).thenReturn("{\"screen\":null,\"element\":null,\"repro_steps\":[]}");
        parse("{\"text\":\"t\"}").andExpect(status().isBadGateway());
    }

    @Test
    void JSON이_아니면_502() throws Exception {
        when(parser.parse(anyString())).thenReturn("화면을 알 수 없습니다");
        parse("{\"text\":\"t\"}").andExpect(status().isBadGateway());
    }

    @Test
    void LLM_호출이_실패하면_502() throws Exception {
        when(parser.parse(anyString())).thenThrow(new ParseFailedException("LLM 호출에 실패했습니다."));
        parse("{\"text\":\"t\"}")
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.error.code").value("PARSE_FAILED"));
    }

    // --- 요청 형태 ---

    @Test
    void text가_비었거나_너무_길면_400이고_LLM을_부르지_않는다() throws Exception {
        parse("{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.message", containsString("text")));
        parse("{\"text\":\"   \"}").andExpect(status().isBadRequest());
        parse("{\"text\":\"" + "가".repeat(5001) + "\"}").andExpect(status().isBadRequest());
        verify(parser, never()).parse(anyString());
    }

    /** 프로젝트는 소유자가 있어야 만들 수 있다(AC16·N15) — 테스트마다 소유자용 멤버를 따로 만든다 */
    private Member owner() {
        return members.save(new Member("owner-" + java.util.UUID.randomUUID() + "@test.dev", "소유자"));
    }
}
