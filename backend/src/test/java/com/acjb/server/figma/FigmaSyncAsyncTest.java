package com.acjb.server.figma;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.acjb.server.domain.FigmaSync;
import com.acjb.server.domain.JobStatus;
import com.acjb.server.domain.Member;
import com.acjb.server.domain.MemberRole;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.ProjectMember;
import com.acjb.server.repository.FigmaFrameRepository;
import com.acjb.server.repository.FigmaSyncRepository;
import com.acjb.server.repository.MemberRepository;
import com.acjb.server.repository.ProjectMemberRepository;
import com.acjb.server.repository.ProjectRepository;

import jakarta.servlet.http.Cookie;

/**
 * 요청 트랜잭션이 커밋된 뒤 FigmaSyncJob이 다른 스레드에서 실제로 실행되는지. 트랜잭션 없이 돌고, 만든 행은 끝에 지운다
 */
@SpringBootTest
@AutoConfigureMockMvc
class FigmaSyncAsyncTest {

    @Autowired MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired MemberRepository members;
    @Autowired ProjectMemberRepository projectMembers;
    @Autowired FigmaSyncRepository syncs;
    @Autowired FigmaFrameRepository frames;
    @MockitoBean FigmaClient figma;

    Project project;
    Member me;
    ProjectMember membership;

    @AfterEach
    void cleanUp() {
        frames.findByProjectIdOrderByNameAscIdAsc(project.getId()).forEach(frames::delete);
        syncs.findAll().stream().filter(s -> s.getProject().getId().equals(project.getId())).forEach(syncs::delete);
        projectMembers.delete(membership);
        members.delete(me);
        projects.delete(project);
    }

    @Test
    void 커밋_뒤_백그라운드에서_동기화가_끝난다() throws Exception {
        project = projects.save(new Project("figma-async", owner()));
        me = members.save(new Member("figma-async@test.dev", "멤버"));
        membership = projectMembers.save(new ProjectMember(project, me, MemberRole.DESIGNER));
        when(figma.isConfigured()).thenReturn(true);
        when(figma.topLevelNodes("ASYNCKEY")).thenReturn(List.of(new FigmaNode("1:2", "홈", "FRAME")));

        mvc.perform(post("/api/v1/projects/" + project.getId() + "/figma/sync")
                .with(oauth2Login().attributes(a -> a.put("email", me.getEmail())))
                .cookie(new Cookie("XSRF-TOKEN", "t")).header("X-XSRF-TOKEN", "t")
                .contentType(MediaType.APPLICATION_JSON).content("{\"figma_file_key\":\"ASYNCKEY\"}"))
            .andExpect(status().isAccepted());

        JobStatus last = null;
        for (int i = 0; i < 50 && last != JobStatus.DONE && last != JobStatus.FAILED; i++) {
            Thread.sleep(100);
            last = syncs.findAll().stream().filter(s -> s.getProject().getId().equals(project.getId()))
                .map(FigmaSync::getStatus).findFirst().orElse(null);
        }
        assertThat(last).isEqualTo(JobStatus.DONE);
        assertThat(frames.findByProjectIdOrderByNameAscIdAsc(project.getId())).singleElement()
            .satisfies(f -> assertThat(f.getName()).isEqualTo("홈"));
    }

    /** 프로젝트는 소유자가 있어야 만들 수 있다(AC16·N15) — 테스트마다 소유자용 멤버를 따로 만든다 */
    private Member owner() {
        return members.save(new Member("owner-" + java.util.UUID.randomUUID() + "@test.dev", "소유자"));
    }
}
