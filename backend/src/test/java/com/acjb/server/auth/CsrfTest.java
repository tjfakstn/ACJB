package com.acjb.server.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OAuth2LoginRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.acjb.server.domain.Member;
import com.acjb.server.domain.MemberRole;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.ProjectMember;
import com.acjb.server.repository.MemberRepository;
import com.acjb.server.repository.ProjectMemberRepository;
import com.acjb.server.repository.ProjectRepository;

import jakarta.servlet.http.Cookie;

/**
 * SPEC 5절: POST·PATCH는 XSRF-TOKEN 쿠키 값을 X-XSRF-TOKEN 헤더에 실어야 하고, 없거나 틀리면 403. GET은 필요 없다.
 * 이슈 등록·PATCH 컨트롤러는 아직 없어서, 토큰이 맞을 때는 "403이 아님"(CSRF 통과)으로만 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CsrfTest {

    @Autowired MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired MemberRepository members;
    @Autowired ProjectMemberRepository projectMembers;

    Project project;

    @BeforeEach
    void setUp() {
        project = projects.save(new Project("csrf-test"));
        Member me = members.save(new Member("csrf@test.dev", "멤버", MemberRole.DEVELOPER));
        projectMembers.save(new ProjectMember(project, me));
    }

    private static OAuth2LoginRequestPostProcessor member() {
        return oauth2Login().attributes(a -> a.put("email", "csrf@test.dev"));
    }

    private String issues() {
        return "/api/v1/projects/" + project.getId() + "/qa-issues";
    }

    @Test
    void 토큰_없이_POST하면_멤버라도_403_ErrorResponse() throws Exception {
        mvc.perform(post(issues()).with(member()).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
            .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void 쿠키와_다른_토큰을_헤더에_넣으면_403() throws Exception {
        mvc.perform(patch(issues() + "/1").with(member())
                .cookie(new Cookie("XSRF-TOKEN", "cookie-value"))
                .header("X-XSRF-TOKEN", "different-value")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void 서버가_내려준_XSRF_TOKEN_쿠키를_헤더에_실으면_CSRF를_통과한다() throws Exception {
        // 실제 프론트 흐름: 아무 GET 응답에서 XSRF-TOKEN 쿠키를 받고, 같은 값을 헤더로 보낸다
        MvcResult first = mvc.perform(get("/api/v1/auth/me")).andReturn();
        Cookie token = first.getResponse().getCookie("XSRF-TOKEN");
        assertThat(token).as("GET 응답이 XSRF-TOKEN 쿠키를 내려줘야 프론트가 읽을 수 있다").isNotNull();
        assertThat(token.isHttpOnly()).as("JS가 읽어야 하므로 HttpOnly가 아니어야 한다").isFalse();

        int status = mvc.perform(post(issues()).with(member())
                .cookie(token).header("X-XSRF-TOKEN", token.getValue())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andReturn().getResponse().getStatus();
        assertThat(status).isNotIn(401, 403);
    }

    @Test
    void GET은_토큰이_없어도_된다() throws Exception {
        int status = mvc.perform(get(issues()).with(member())).andReturn().getResponse().getStatus();
        assertThat(status).isNotIn(401, 403);
    }
}
