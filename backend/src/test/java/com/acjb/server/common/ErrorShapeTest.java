package com.acjb.server.common;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.acjb.server.domain.Member;
import com.acjb.server.domain.MemberRole;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.ProjectMember;
import com.acjb.server.repository.MemberRepository;
import com.acjb.server.repository.ProjectMemberRepository;
import com.acjb.server.repository.ProjectRepository;

import jakarta.servlet.http.Cookie;

/** N12: 컨트롤러 단계의 모든 오류도 {success:false, error:{code, message}} 형태이고 data를 담지 않는다 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(ErrorShapeTest.BoomConfig.class)
class ErrorShapeTest {

    /** 테스트 classpath에만 있는 엔드포인트 — 예상하지 못한 예외를 일부러 던진다 */
    @TestConfiguration
    static class BoomConfig {
        @RestController
        static class BoomController {
            @GetMapping("/api/v1/projects/{projectId}/__test-boom")
            String boom() {
                throw new IllegalStateException("내부 상세 메시지");
            }
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired MemberRepository members;
    @Autowired ProjectMemberRepository projectMembers;

    Project project;
    Member me;

    @BeforeEach
    void setUp() {
        project = projects.save(new Project("error-shape"));
        me = members.save(new Member("error-shape@test.dev", "멤버", MemberRole.DEVELOPER));
        projectMembers.save(new ProjectMember(project, me));
    }

    @Test
    void 예상하지_못한_오류는_500_INTERNAL_ERROR이고_내부_메시지를_싣지_않는다() throws Exception {
        mvc.perform(get("/api/v1/projects/" + project.getId() + "/__test-boom")
                .with(oauth2Login().attributes(a -> a.put("email", me.getEmail()))))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.error.message").value("서버 오류가 발생했습니다."))
            .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void 없는_경로는_404_NOT_FOUND_envelope() throws Exception {
        mvc.perform(get("/api/v1/projects/" + project.getId() + "/no-such-thing")
                .with(oauth2Login().attributes(a -> a.put("email", me.getEmail()))))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void 허용하지_않는_메서드는_405_envelope() throws Exception {
        mvc.perform(delete("/api/v1/projects/" + project.getId() + "/qa-issues")
                .with(oauth2Login().attributes(a -> a.put("email", me.getEmail())))
                .cookie(new Cookie("XSRF-TOKEN", "t")).header("X-XSRF-TOKEN", "t"))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
    }
}
