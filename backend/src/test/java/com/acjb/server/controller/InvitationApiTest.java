package com.acjb.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.acjb.server.domain.Invitation;
import com.acjb.server.domain.InvitationStatus;
import com.acjb.server.domain.Member;
import com.acjb.server.domain.MemberRole;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.ProjectMember;
import com.acjb.server.invite.InvitationCreated;
import com.acjb.server.invite.InvitationTokens;
import com.acjb.server.repository.InvitationRepository;
import com.acjb.server.repository.MemberRepository;
import com.acjb.server.repository.ProjectMemberRepository;
import com.acjb.server.repository.ProjectRepository;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;

/**
 * AC17(초대)·AC18(수락)·N14. 원문 토큰은 메일에만 있으므로, 테스트는 초대 때 발행된 이벤트(메일 내용)에서 꺼낸다.
 * 메일이 실제로 커밋 뒤 보내지는지는 InvitationMailAsyncTest가 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@RecordApplicationEvents
class InvitationApiTest {

    @Autowired MockMvc mvc;
    @Autowired ApplicationEvents events;
    @Autowired EntityManager em;
    @Autowired ProjectRepository projects;
    @Autowired MemberRepository members;
    @Autowired ProjectMemberRepository projectMembers;
    @Autowired InvitationRepository invitations;

    Project project;
    Member owner;
    Member dev;

    @BeforeEach
    void setUp() {
        owner = members.save(new Member("inv-owner@test.dev", "소유자"));
        dev = members.save(new Member("inv-dev@test.dev", "개발자"));
        project = projects.save(new Project("inv-project", owner));
        projectMembers.save(new ProjectMember(project, owner, MemberRole.PLANNER));
        projectMembers.save(new ProjectMember(project, dev, MemberRole.BACKEND));
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder r, String email) {
        return r.with(oauth2Login().attributes(a -> { a.put("email", email); a.put("name", "초대받은 사람"); }))
            .cookie(new Cookie("XSRF-TOKEN", "t")).header("X-XSRF-TOKEN", "t").contentType(MediaType.APPLICATION_JSON);
    }

    private ResultActions invite(Member by, String email) throws Exception {
        return mvc.perform(as(post("/api/v1/projects/" + project.getId() + "/invitations"), by.getEmail())
            .content("{\"email\":\"" + email + "\"}"));
    }

    /** 가장 최근 초대 메일의 수락 링크에서 토큰을 꺼낸다 */
    private String lastToken() {
        String url = events.stream(InvitationCreated.class).reduce((a, b) -> b).orElseThrow().mail().acceptUrl();
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private ResultActions accept(String token, String email, String role) throws Exception {
        return mvc.perform(as(post("/api/v1/invitations/" + token + "/accept"), email).content("{\"role\":\"" + role + "\"}"));
    }

    // --- AC17 ---

    @Test
    void ac17_멤버가_초대하면_7일짜리_대기_초대_1건과_초대_메일_1통이_생기고_응답에_토큰이_없다() throws Exception {
        String body = invite(dev, "  New.Person@Gmail.com ")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.email").value("new.person@gmail.com"))
            .andExpect(jsonPath("$.data.status").value("pending"))
            .andExpect(jsonPath("$.data.invited_by").value(String.valueOf(dev.getId())))
            .andExpect(jsonPath("$.data.project_name").value("inv-project"))
            .andReturn().getResponse().getContentAsString();

        assertThat(events.stream(InvitationCreated.class)).singleElement().satisfies(e -> {
            assertThat(e.mail().to()).isEqualTo("new.person@gmail.com");
            assertThat(e.mail().inviterName()).isEqualTo("개발자");
            assertThat(e.mail().acceptUrl()).startsWith("http://localhost:5173/invitations/");
        });
        String token = lastToken();
        assertThat(body).doesNotContain(token);
        Invitation saved = invitations.findAll().getFirst();
        assertThat(saved.getTokenHash()).isEqualTo(InvitationTokens.hash(token)).isNotEqualTo(token);   // DB에는 해시만
        assertThat(Duration.between(saved.getCreatedAt(), saved.getExpiresAt())).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void ac17_이미_멤버인_이메일은_409이고_초대를_만들지_않는다() throws Exception {
        invite(owner, "INV-DEV@test.dev")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("ALREADY_MEMBER"));
        assertThat(invitations.count()).isZero();
        assertThat(events.stream(InvitationCreated.class)).isEmpty();
    }

    /** iv-003(2026-11-07 교체, 설만수 승인) — 재초대는 새 초대 대신 같은 초대를 연장한다 */
    @Test
    void ac17_같은_이메일을_다시_초대하면_같은_초대가_7일_연장되고_이전_링크는_무효다() throws Exception {
        invite(dev, "again@test.dev").andExpect(status().isCreated());
        String first = lastToken();
        Invitation old = invitations.findAll().getFirst();
        // 3일 전에 만든 초대로 맞춘다
        em.createNativeQuery("update invitations set created_at = :c, expires_at = :e where id = :id")
            .setParameter("c", Instant.now().minus(Duration.ofDays(3)))
            .setParameter("e", Instant.now().plus(Duration.ofDays(4)))
            .setParameter("id", old.getId()).executeUpdate();
        em.clear();

        Instant before = Instant.now();
        invite(owner, "again@test.dev")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(String.valueOf(old.getId())))
            .andExpect(jsonPath("$.data.status").value("pending"));
        String second = lastToken();

        em.flush();
        em.clear();
        assertThat(invitations.count()).isEqualTo(1);
        Invitation renewed = invitations.findById(old.getId()).orElseThrow();
        assertThat(renewed.getStatus()).isEqualTo(InvitationStatus.PENDING);
        assertThat(renewed.getExpiresAt()).isBetween(before.plus(Duration.ofDays(7)).minusSeconds(5), Instant.now().plus(Duration.ofDays(7)));
        assertThat(events.stream(InvitationCreated.class)).hasSize(2);
        mvc.perform(as(get("/api/v1/projects/" + project.getId() + "/invitations"), dev.getEmail()))
            .andExpect(jsonPath("$.data.items.length()").value(1));
        accept(first, "again@test.dev", "designer").andExpect(status().isNotFound());
        accept(second, "again@test.dev", "designer").andExpect(status().isOk());
    }

    /** iv-008 — 재전송은 초대하지 않은 멤버도 할 수 있고, 만료를 7일 늘리고 메일 1통. 응답에 토큰이 없다(N14) */
    @Test
    void ac17_재전송하면_만료가_7일_연장되고_새_링크로_메일_1통() throws Exception {
        Member designer = members.save(new Member("inv-des2@test.dev", "디자이너"));
        projectMembers.save(new ProjectMember(project, designer, MemberRole.DESIGNER));
        invite(dev, "resend@test.dev").andExpect(status().isCreated());
        String first = lastToken();
        Long id = invitations.findAll().getFirst().getId();

        Instant before = Instant.now();
        String body = mvc.perform(as(post("/api/v1/projects/" + project.getId() + "/invitations/" + id + "/resend"), designer.getEmail()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("pending"))
            .andReturn().getResponse().getContentAsString();
        String second = lastToken();

        assertThat(second).isNotEqualTo(first);
        assertThat(body).doesNotContain(second).doesNotContain("token");
        assertThat(events.stream(InvitationCreated.class)).hasSize(2);
        em.flush();
        em.clear();
        assertThat(invitations.findById(id).orElseThrow().getExpiresAt()).isAfterOrEqualTo(before.plus(Duration.ofDays(7)).minusSeconds(5));
        accept(first, "resend@test.dev", "designer").andExpect(status().isNotFound());
    }

    /** iv-009 — 대기 중이 아닌 초대의 재전송은 409이고 메일을 보내지 않는다 */
    @Test
    void ac17_수락된_초대는_재전송할_수_없다() throws Exception {
        invite(dev, "done@test.dev").andExpect(status().isCreated());
        Long id = invitations.findAll().getFirst().getId();
        accept(lastToken(), "done@test.dev", "designer").andExpect(status().isOk());
        long mails = events.stream(InvitationCreated.class).count();

        mvc.perform(as(post("/api/v1/projects/" + project.getId() + "/invitations/" + id + "/resend"), dev.getEmail()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("INVITATION_CLOSED"));
        assertThat(events.stream(InvitationCreated.class).count()).isEqualTo(mails);
    }

    @Test
    void ac17_이메일이_비었거나_형식이_틀리면_400() throws Exception {
        invite(dev, "").andExpect(status().isBadRequest());
        invite(dev, "not-an-email").andExpect(status().isBadRequest());
        assertThat(invitations.count()).isZero();
    }

    @Test
    void 멤버가_아니면_초대할_수_없다_AC12() throws Exception {
        Member outsider = members.save(new Member("inv-out@test.dev", "외부"));
        invite(outsider, "x@test.dev").andExpect(status().isForbidden());
    }

    // --- AC18 ---

    @Test
    void 초대_미리보기는_프로젝트_id를_함께_준다() throws Exception {
        invite(dev, "preview-id@test.dev").andExpect(status().isCreated());

        mvc.perform(as(get("/api/v1/invitations/" + lastToken()), "preview-id@test.dev"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.project_id").value(String.valueOf(project.getId())))
            .andExpect(jsonPath("$.data.token").doesNotExist());
    }

    @Test
    void ac18_초대받은_계정으로_수락하면_고른_역할의_멤버가_되고_초대는_accepted() throws Exception {
        invite(dev, "joiner@test.dev").andExpect(status().isCreated());
        String token = lastToken();

        mvc.perform(as(get("/api/v1/invitations/" + token), "joiner@test.dev"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.project_name").value("inv-project"))
            .andExpect(jsonPath("$.data.invited_by_name").value("개발자"))
            .andExpect(jsonPath("$.data.status").value("pending"));

        accept(token, "Joiner@test.dev", "designer")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(String.valueOf(project.getId())));

        Member joiner = members.findByEmail("joiner@test.dev").orElseThrow();   // 첫 요청 때 생성됨
        assertThat(projectMembers.findByProjectIdAndMemberId(project.getId(), joiner.getId()))
            .hasValueSatisfying(pm -> assertThat(pm.getRole()).isEqualTo(MemberRole.DESIGNER));
        Invitation invitation = invitations.findByTokenHash(InvitationTokens.hash(token)).orElseThrow();
        assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.ACCEPTED);
        assertThat(invitation.getAcceptedMember().getId()).isEqualTo(joiner.getId());
        // 이제 프로젝트에 접근할 수 있다
        mvc.perform(as(get("/api/v1/projects/" + project.getId() + "/qa-issues"), "joiner@test.dev")).andExpect(status().isOk());
    }

    @Test
    void ac18_다른_구글_계정으로는_수락할_수_없다_403이고_멤버를_만들지_않는다() throws Exception {
        invite(dev, "real@test.dev").andExpect(status().isCreated());
        accept(lastToken(), "forwarded@test.dev", "backend")
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.success").value(false));
        assertThat(members.findByEmail("forwarded@test.dev")).hasValueSatisfying(m ->
            assertThat(projectMembers.existsByProjectIdAndMemberId(project.getId(), m.getId())).isFalse());
        assertThat(invitations.findAll().getFirst().getStatus()).isEqualTo(InvitationStatus.PENDING);
    }

    @Test
    void ac18_만료된_초대는_409이고_미리보기에서도_expired다() throws Exception {
        String token = InvitationTokens.newToken();
        invitations.save(new Invitation(project, "late@test.dev", InvitationTokens.hash(token), dev,
            Instant.now().minus(Duration.ofDays(8))));

        mvc.perform(as(get("/api/v1/invitations/" + token), "late@test.dev")).andExpect(jsonPath("$.data.status").value("expired"));
        accept(token, "late@test.dev", "backend")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("INVITATION_CLOSED"));
        assertThat(members.findByEmail("late@test.dev")).hasValueSatisfying(m ->
            assertThat(projectMembers.existsByProjectIdAndMemberId(project.getId(), m.getId())).isFalse());
    }

    @Test
    void ac18_이미_수락한_초대를_다시_수락하면_409() throws Exception {
        invite(dev, "twice@test.dev").andExpect(status().isCreated());
        String token = lastToken();
        accept(token, "twice@test.dev", "backend").andExpect(status().isOk());
        accept(token, "twice@test.dev", "backend").andExpect(status().isConflict());
    }

    @Test
    void ac18_역할이_정해진_값이_아니면_400이고_초대는_그대로다() throws Exception {
        invite(dev, "role@test.dev").andExpect(status().isCreated());
        String token = lastToken();
        accept(token, "role@test.dev", "boss").andExpect(status().isBadRequest());
        mvc.perform(as(post("/api/v1/invitations/" + token + "/accept"), "role@test.dev").content("{}")).andExpect(status().isBadRequest());
        assertThat(invitations.findAll().getFirst().getStatus()).isEqualTo(InvitationStatus.PENDING);
    }

    @Test
    void 없는_토큰은_404() throws Exception {
        mvc.perform(as(get("/api/v1/invitations/no-such-token"), "a@test.dev")).andExpect(status().isNotFound());
        accept("no-such-token", "a@test.dev", "backend").andExpect(status().isNotFound());
    }

    // --- 앱 안 초대함 ---

    @Test
    void 메일을_못_받아도_로그인하면_내_초대를_보고_수락할_수_있다() throws Exception {
        invite(dev, "inbox@test.dev").andExpect(status().isCreated());
        String mine = mvc.perform(as(get("/api/v1/invitations/mine"), "INBOX@test.dev"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].project_name").value("inv-project"))
            .andReturn().getResponse().getContentAsString();
        String id = mine.replaceAll("(?s).*\"id\":\"(\\d+)\".*", "$1");

        mvc.perform(as(post("/api/v1/invitations/mine/" + id + "/accept"), "dev-other@test.dev").content("{\"role\":\"planner\"}"))
            .andExpect(status().isForbidden());   // 남의 초대는 수락 불가
        mvc.perform(as(post("/api/v1/invitations/mine/" + id + "/accept"), "inbox@test.dev").content("{\"role\":\"planner\"}"))
            .andExpect(status().isOk());
        mvc.perform(as(get("/api/v1/invitations/mine"), "inbox@test.dev")).andExpect(jsonPath("$.data.items.length()").value(0));
    }

    // --- 취소 ---

    @Test
    void 초대는_초대한_사람이나_소유자만_취소한다() throws Exception {
        Member designer = members.save(new Member("inv-des@test.dev", "디자이너"));
        projectMembers.save(new ProjectMember(project, designer, MemberRole.DESIGNER));
        invite(dev, "cancel-me@test.dev").andExpect(status().isCreated());
        Long id = invitations.findAll().getFirst().getId();
        String path = "/api/v1/projects/" + project.getId() + "/invitations/" + id;

        mvc.perform(as(delete(path), designer.getEmail())).andExpect(status().isForbidden());
        mvc.perform(as(delete(path), owner.getEmail())).andExpect(status().isNoContent());
        mvc.perform(as(delete(path), dev.getEmail())).andExpect(status().isConflict());   // 이미 취소됨
        em.flush();
        em.clear();
        assertThat(invitations.findById(id).orElseThrow().getStatus()).isEqualTo(InvitationStatus.CANCELED);
    }

    @Test
    void 초대_목록에는_토큰이나_토큰_해시가_없다_N14() throws Exception {
        invite(dev, "list@test.dev").andExpect(status().isCreated());
        String token = lastToken();
        String body = mvc.perform(as(get("/api/v1/projects/" + project.getId() + "/invitations"), dev.getEmail()))
            .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(token).doesNotContain(InvitationTokens.hash(token)).doesNotContain("token");
    }
}
