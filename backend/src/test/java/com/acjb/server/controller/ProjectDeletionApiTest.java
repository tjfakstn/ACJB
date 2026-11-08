package com.acjb.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.acjb.server.domain.Invitation;
import com.acjb.server.domain.Member;
import com.acjb.server.domain.MemberRole;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.ProjectMember;
import com.acjb.server.domain.ProjectRun;
import com.acjb.server.domain.RunKind;
import com.acjb.server.repository.MemberRepository;
import com.acjb.server.repository.ProjectMemberRepository;
import com.acjb.server.repository.ProjectRepository;
import com.acjb.server.repository.ProjectRunRepository;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import tools.jackson.databind.json.JsonMapper;

/** AC26 — 프로젝트 삭제(골든 케이스 pj-009~pj-011) */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProjectDeletionApiTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired EntityManager em;
    @Autowired ProjectRepository projects;
    @Autowired MemberRepository members;
    @Autowired ProjectMemberRepository projectMembers;
    @Autowired ProjectRunRepository runs;

    Member owner;
    Member dev;
    Project x;
    Project y;

    @BeforeEach
    void setUp() {
        owner = members.save(new Member("del-owner@test.dev", "소유자"));
        dev = members.save(new Member("del-dev@test.dev", "개발자"));
        x = projects.save(new Project("지울 프로젝트", owner));
        y = projects.save(new Project("남을 프로젝트", owner));
        for (Project p : new Project[] {x, y}) {
            projectMembers.save(new ProjectMember(p, owner, null));
            projectMembers.save(new ProjectMember(p, dev, MemberRole.BACKEND));
        }
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder r, Member m) {
        return r.with(oauth2Login().attributes(a -> a.put("email", m.getEmail())))
            .cookie(new Cookie("XSRF-TOKEN", "t")).header("X-XSRF-TOKEN", "t").contentType(MediaType.APPLICATION_JSON);
    }

    /** 등록(담당자 지정 → 알림·이력) → fixed → 재검증 fail(재검증·알림)까지 API로 만든다. 스타일 차이도 함께 */
    private String issueWithEverything(Project p) throws Exception {
        String body = """
            {"description":"버튼 색이 달라요","screen_location":"홈 주문 버튼","repro_steps":["홈 진입","주문 버튼 확인"],
             "platform_scope":"common","assigned_team":"backend",
             "style_mismatch":{"property":"color","expected_value":"#1A73E8","actual_value":"#2B7DE9"}}
            """;
        String res = mvc.perform(as(post("/api/v1/projects/" + p.getId() + "/qa-issues"), owner).content(body))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(res).path("data").path("id").asString();
        mvc.perform(as(patch("/api/v1/projects/" + p.getId() + "/qa-issues/" + id), dev).content("{\"status\":\"fixed\"}"))
            .andExpect(status().isOk());
        mvc.perform(as(post("/api/v1/projects/" + p.getId() + "/qa-issues/" + id + "/verifications"), owner).content("{\"result\":\"fail\"}"))
            .andExpect(status().isCreated());
        return id;
    }

    private long rows(String sql) {
        return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
    }

    private long rowsForProject(Long pid) {
        String issueRows = "(SELECT id FROM qa_issues WHERE project_id = " + pid + ")";
        return rows("SELECT COUNT(*) FROM qa_issues WHERE project_id = " + pid)
            + rows("SELECT COUNT(*) FROM status_histories WHERE qa_issue_id IN " + issueRows)
            + rows("SELECT COUNT(*) FROM verifications WHERE qa_issue_id IN " + issueRows)
            + rows("SELECT COUNT(*) FROM notifications WHERE qa_issue_id IN " + issueRows)
            + rows("SELECT COUNT(*) FROM style_mismatches WHERE project_id = " + pid)
            + rows("SELECT COUNT(*) FROM invitations WHERE project_id = " + pid)
            + rows("SELECT COUNT(*) FROM project_members WHERE project_id = " + pid)
            + rows("SELECT COUNT(*) FROM projects WHERE id = " + pid);
    }

    @Test
    void pj009_소유자가_삭제하면_그_프로젝트의_데이터는_모두_지워지고_다른_프로젝트와_멤버는_남는다() throws Exception {
        issueWithEverything(x);
        issueWithEverything(y);
        em.persist(new Invitation(x, "pending@test.dev", "h-del-pending", owner, Instant.now()));
        em.flush();
        assertThat(rowsForProject(x.getId())).isGreaterThan(5);
        long yRowsBefore = rowsForProject(y.getId());
        long membersBefore = members.count();

        mvc.perform(as(delete("/api/v1/projects/" + x.getId()), owner)).andExpect(status().isNoContent());

        em.clear();
        assertThat(rowsForProject(x.getId())).isZero();
        assertThat(rowsForProject(y.getId())).isEqualTo(yRowsBefore);
        assertThat(members.count()).isEqualTo(membersBefore);
        mvc.perform(as(get("/api/v1/projects/" + x.getId()), owner)).andExpect(status().isForbidden());
        mvc.perform(as(get("/api/v1/projects"), owner))
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].name").value("남을 프로젝트"));
    }

    @Test
    void pj010_소유자가_아니면_403이고_아무것도_지우지_않는다() throws Exception {
        issueWithEverything(x);
        long before = rowsForProject(x.getId());
        mvc.perform(as(delete("/api/v1/projects/" + x.getId()), dev)).andExpect(status().isForbidden());
        em.clear();
        assertThat(rowsForProject(x.getId())).isEqualTo(before);
    }

    @Test
    void pj011_비교_분석이_실행_중이면_409이고_지우지_않는다() throws Exception {
        runs.saveAndFlush(new ProjectRun(x, RunKind.ANALYSIS, owner, Instant.now()));   // queued
        mvc.perform(as(delete("/api/v1/projects/" + x.getId()), owner))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("NOT_READY"));
        assertThat(projects.existsById(x.getId())).isTrue();
    }
}
