package com.acjb.server.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OAuth2LoginRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.acjb.server.domain.Member;
import com.acjb.server.domain.MemberRole;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.ProjectMember;
import com.acjb.server.repository.MemberRepository;
import com.acjb.server.repository.ProjectMemberRepository;
import com.acjb.server.repository.ProjectRepository;

/**
 * AC12: /projects/{projectId}/** 는 로그인한 그 프로젝트 멤버만. 골든 케이스 au-001(401)·au-002(403, data 없음)에 대응한다.
 * 로그인은 spring-security-test의 oauth2Login()으로 흉내 낸다 — 테스트 쪽 도구라 운영 코드에 인증 우회가 생기지 않는다(N10).
 * GET /qa-issues 컨트롤러는 아직 없어서, 멤버가 통과했는지는 "401·403이 아님"으로만 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProjectAccessTest {

    @Autowired MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired MemberRepository members;
    @Autowired ProjectMemberRepository projectMembers;

    Project mine;
    Project other;

    @BeforeEach
    void setUp() {
        mine = projects.save(new Project("ac12-mine", owner()));
        other = projects.save(new Project("ac12-other", owner()));
        Member me = members.save(new Member("me@test.dev", "멤버"));
        Member outsider = members.save(new Member("outsider@test.dev", "다른 프로젝트 멤버"));
        projectMembers.save(new ProjectMember(mine, me, MemberRole.DEVELOPER));
        projectMembers.save(new ProjectMember(other, outsider, MemberRole.DESIGNER));
    }

    private static OAuth2LoginRequestPostProcessor loginAs(String email) {
        return oauth2Login().attributes(a -> a.put("email", email));
    }

    private String issuesOf(Project project) {
        return "/api/v1/projects/" + project.getId() + "/qa-issues";
    }

    @Test
    void au001_미인증이면_401과_ErrorResponse를_반환한다() throws Exception {
        mvc.perform(get(issuesOf(mine)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void au002_다른_프로젝트_멤버면_403이고_data를_담지_않는다() throws Exception {
        mvc.perform(get(issuesOf(mine)).with(loginAs("outsider@test.dev")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
            .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void 구글_로그인은_했지만_Member로_등록되지_않은_이메일이면_403() throws Exception {
        mvc.perform(get(issuesOf(mine)).with(loginAs("stranger@test.dev")))
            .andExpect(status().isForbidden());
    }

    @Test
    void 존재하지_않는_프로젝트와_숫자가_아닌_projectId도_403이다() throws Exception {
        mvc.perform(get("/api/v1/projects/999999999/qa-issues").with(loginAs("me@test.dev")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/projects/abc/qa-issues").with(loginAs("me@test.dev")))
            .andExpect(status().isForbidden());
    }

    @Test
    void 그_프로젝트_멤버는_통과한다() throws Exception {
        int status = mvc.perform(get(issuesOf(mine)).with(loginAs("me@test.dev")))
            .andReturn().getResponse().getStatus();
        assertThat(status).isNotIn(401, 403);
    }

    @Test
    void 멤버여도_자기_프로젝트가_아니면_403이다() throws Exception {
        mvc.perform(get(issuesOf(other)).with(loginAs("me@test.dev")))
            .andExpect(status().isForbidden());
    }

    @Test
    void 프로젝트_밖의_공개_경로는_그대로다_AC9_AC10() throws Exception {
        mvc.perform(get("/api/v1/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"));
        mvc.perform(get("/api/v1/auth/me"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authenticated").value(false));
    }

    /** 프로젝트는 소유자가 있어야 만들 수 있다(AC16·N15) — 테스트마다 소유자용 멤버를 따로 만든다 */
    private Member owner() {
        return members.save(new Member("owner-" + java.util.UUID.randomUUID() + "@test.dev", "소유자"));
    }
}
