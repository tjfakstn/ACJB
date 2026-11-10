package com.acjb.server.invite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.acjb.server.domain.Member;
import com.acjb.server.domain.MemberRole;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.ProjectMember;
import com.acjb.server.repository.InvitationRepository;
import com.acjb.server.repository.MemberRepository;
import com.acjb.server.repository.ProjectMemberRepository;
import com.acjb.server.repository.ProjectRepository;

import jakarta.servlet.http.Cookie;

/** 초대가 커밋된 뒤 별도 스레드에서 메일이 1통 나가는지(AC17). 트랜잭션 없이 돌고 만든 행은 끝에 지운다 */
@SpringBootTest
@AutoConfigureMockMvc
class InvitationMailAsyncTest {

    @Autowired MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired MemberRepository members;
    @Autowired ProjectMemberRepository projectMembers;
    @Autowired InvitationRepository invitations;
    @MockitoBean InvitationMailer mailer;

    Project project;
    Member owner;
    ProjectMember membership;

    @AfterEach
    void cleanUp() {
        invitations.findAll().stream().filter(i -> i.getProject().getId().equals(project.getId())).forEach(invitations::delete);
        projectMembers.delete(membership);
        projects.delete(project);
        members.delete(owner);
    }

    @Test
    void 초대가_커밋되면_초대받은_이메일로_수락_링크가_든_메일이_1통_나간다() throws Exception {
        owner = members.save(new Member("mail-owner-" + System.nanoTime() + "@test.dev", "소유자"));
        project = projects.save(new Project("mail-project", owner));
        membership = projectMembers.save(new ProjectMember(project, owner, MemberRole.PLANNER));

        mvc.perform(post("/api/v1/projects/" + project.getId() + "/invitations")
                .with(oauth2Login().attributes(a -> a.put("email", owner.getEmail())))
                .cookie(new Cookie("XSRF-TOKEN", "t")).header("X-XSRF-TOKEN", "t")
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"mail-target@test.dev\"}"))
            .andExpect(status().isCreated());

        ArgumentCaptor<InvitationMail> sent = ArgumentCaptor.forClass(InvitationMail.class);
        verify(mailer, timeout(5000).times(1)).send(sent.capture());
        assertThat(sent.getValue().to()).isEqualTo("mail-target@test.dev");
        assertThat(sent.getValue().projectName()).isEqualTo("mail-project");
        assertThat(sent.getValue().acceptUrl()).matches("http://localhost:5173/invitations/[A-Za-z0-9_-]{43}");
        verify(mailer, timeout(500).times(1)).send(any());
    }
}
