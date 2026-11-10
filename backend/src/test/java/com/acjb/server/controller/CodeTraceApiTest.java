package com.acjb.server.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

import com.acjb.server.domain.DesignCodeMapping;
import com.acjb.server.domain.Member;
import com.acjb.server.domain.MemberRole;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.ProjectMember;
import com.acjb.server.domain.ProjectRun;
import com.acjb.server.domain.QAIssue;
import com.acjb.server.domain.RunKind;
import com.acjb.server.domain.StyleMismatch;
import com.acjb.server.domain.StyleProperty;
import com.acjb.server.domain.Verification;
import com.acjb.server.domain.VerificationResult;
import com.acjb.server.github.GithubApiException;
import com.acjb.server.github.GithubClient;
import com.acjb.server.github.GithubClient.CommitInfo;
import com.acjb.server.github.GithubClient.PullInfo;
import com.acjb.server.repository.DesignCodeMappingRepository;
import com.acjb.server.repository.ManualQaPinRepository;
import com.acjb.server.repository.MemberRepository;
import com.acjb.server.repository.ProjectMemberRepository;
import com.acjb.server.repository.ProjectRepository;
import com.acjb.server.repository.ProjectRunRepository;
import com.acjb.server.repository.QAIssueRepository;
import com.acjb.server.repository.StyleMismatchRepository;
import com.acjb.server.repository.VerificationRepository;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;

/**
 * 이슈 상세 코드 추적 AC34 — 코드 위치, 같은 요소의 다른 이슈, 문제 코드가 들어온 커밋·PR, 고친 커밋·PR(이슈 키·붙인 링크·Re-QA), 수정 링크 권한.
 * GitHub는 가짜로 바꿔 끼운다 — 외부 네트워크를 쓰지 않는다
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CodeTraceApiTest {

    private static final String SHA = "6ad7beba0dc397710cfb8b44305590e6c7a3c30e";

    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired ProjectRepository projects;
    @Autowired MemberRepository members;
    @Autowired ProjectMemberRepository projectMembers;
    @Autowired ProjectRunRepository runs;
    @Autowired DesignCodeMappingRepository mappings;
    @Autowired StyleMismatchRepository mismatches;
    @Autowired QAIssueRepository issues;
    @Autowired VerificationRepository verifications;
    @Autowired ManualQaPinRepository pins;
    @MockitoBean GithubClient github;

    Project project;
    Member owner;
    Member front;
    Member designer;
    QAIssue issue;
    QAIssue sibling;

    @BeforeEach
    void setUp() {
        owner = members.save(new Member("ct-owner@test.dev", "소유자"));
        front = members.save(new Member("ct-front@test.dev", "프론트"));
        designer = members.save(new Member("ct-design@test.dev", "디자인"));
        project = projects.save(new Project("코드 추적", owner));
        projectMembers.save(new ProjectMember(project, owner, null));
        projectMembers.save(new ProjectMember(project, front, MemberRole.FRONTEND));
        projectMembers.save(new ProjectMember(project, designer, MemberRole.DESIGNER));
        project.connectGithub("https://github.com/o/r", "o/r", "main", "main", Instant.now());

        ProjectRun analysis = runs.save(new ProjectRun(project, RunKind.ANALYSIS, owner, Instant.now()));
        analysis.succeed(SHA, 1, 0, Instant.now());
        mappings.save(DesignCodeMapping.node(project, analysis, "F1", "QA Queue", "9:1", "Issue List", "src/QaParts.tsx", "Verdict", Instant.now()));
        ProjectRun firstQa = runs.save(new ProjectRun(project, RunKind.FIRST_QA, owner, Instant.now()));
        firstQa.succeed(SHA, 2, 0, Instant.now());
        issue = firstQa(firstQa, StyleProperty.COLOR, "글자 #000000", "글자 #fc1b1f");
        sibling = firstQa(firstQa, StyleProperty.FONT_WEIGHT, "500", "700");
        issue.assignTeam(MemberRole.FRONTEND);
        em.flush();
        when(github.hasToken()).thenReturn(true);
        when(github.commitsSince(anyString(), anyString(), anyString(), any())).thenReturn(List.of());
        when(github.recentPulls(anyString(), anyString())).thenReturn(List.of());
    }

    private QAIssue firstQa(ProjectRun run, StyleProperty p, String expected, String actual) {
        StyleMismatch m = mismatches.save(StyleMismatch.firstQa(project, "F1", "QA Queue", "I9:1;2:3", p, expected, actual, "src/QaParts.tsx", 141));
        return issues.save(QAIssue.firstQa(project, run, "설명", "QA Queue", "Issue List > Label", m, owner));
    }

    private <B extends AbstractMockHttpServletRequestBuilder<B>> B as(B req, Member who) {
        return req.with(oauth2Login().attributes(a -> a.put("email", who.getEmail())))
            .cookie(new Cookie("XSRF-TOKEN", "t")).header("X-XSRF-TOKEN", "t");
    }

    private String base() {
        return "/api/v1/projects/" + project.getId() + "/qa-issues/" + issue.getId();
    }

    private static CommitInfo commit(String sha, String message, Instant at) {
        return new CommitInfo(sha, message, "dev", at, "https://github.com/o/r/commit/" + sha);
    }

    private static PullInfo pr(int n, String title, String state, Instant at) {
        return new PullInfo(n, title, "", "feature/x", state, "dev", at, "https://github.com/o/r/pull/" + n);
    }

    @Test
    void 코드_위치와_같은_요소의_다른_이슈와_문제_코드가_들어온_커밋_PR을_준다() throws Exception {
        CommitInfo origin = commit("7c71f18aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "feat(client): QA 화면", Instant.parse("2026-11-08T10:10:30Z"));
        when(github.blameLine("o", "r", SHA, "src/QaParts.tsx", 141))
            .thenReturn(Optional.of(new GithubClient.Origin(origin, List.of(pr(12, "QA 화면", "merged", Instant.parse("2026-11-08T09:00:00Z"))))));
        mvc.perform(as(get(base() + "/code-trace"), designer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.issue_key").value("QA-" + issue.getId()))
            .andExpect(jsonPath("$.data.code.basis").value("element"))
            .andExpect(jsonPath("$.data.code.path").value("src/QaParts.tsx"))
            .andExpect(jsonPath("$.data.code.line").value(141))
            .andExpect(jsonPath("$.data.code.symbol").value("Verdict"))
            .andExpect(jsonPath("$.data.code.commit_sha").value(SHA))
            .andExpect(jsonPath("$.data.code.url").value("https://github.com/o/r/blob/" + SHA + "/src/QaParts.tsx#L141"))
            .andExpect(jsonPath("$.data.related.length()").value(1))
            .andExpect(jsonPath("$.data.related[0].issue_id").value(String.valueOf(sibling.getId())))
            .andExpect(jsonPath("$.data.related[0].property").value("font_weight"))
            .andExpect(jsonPath("$.data.origin.basis").value("line"))
            .andExpect(jsonPath("$.data.origin.commit.message").value("feat(client): QA 화면"))
            .andExpect(jsonPath("$.data.origin.pull_requests[0].number").value(12))
            .andExpect(jsonPath("$.data.origin.pull_requests[0].state").value("merged"))
            .andExpect(jsonPath("$.data.fixes.length()").value(0))
            .andExpect(jsonPath("$.data.github").value("ok"));
    }

    @Test
    void 토큰이_없으면_그_파일을_마지막으로_바꾼_커밋으로_대신한다() throws Exception {
        when(github.hasToken()).thenReturn(false);
        CommitInfo last = commit("abc1234aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "style: 칩", Instant.parse("2026-11-09T00:00:00Z"));
        when(github.lastCommitForPath("o", "r", SHA, "src/QaParts.tsx")).thenReturn(Optional.of(last));
        when(github.pullsForCommit("o", "r", last.sha())).thenReturn(List.of());
        mvc.perform(as(get(base() + "/code-trace"), front))
            .andExpect(jsonPath("$.data.origin.basis").value("file"))
            .andExpect(jsonPath("$.data.origin.commit.sha").value(last.sha()))
            .andExpect(jsonPath("$.data.origin.pull_requests.length()").value(0));
        verify(github, never()).blameLine(anyString(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void 고친_커밋_PR은_이슈_키가_정확히_들어간_것_붙인_링크_Re_QA_커밋이다() throws Exception {
        String key = "QA-" + issue.getId();
        Instant later = Instant.now().plusSeconds(3600);
        when(github.commitsSince(eq("o"), eq("r"), eq("main"), any())).thenReturn(List.of(
            commit("1111111aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "fix: FAIL 칩 색 시안대로 (" + key + ")", later),
            commit("2222222aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "fix: 다른 이슈 (" + key + "0)", later),     // QA-{id}0은 다른 이슈
            commit("3333333aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "chore: 정리", later)));
        when(github.recentPulls("o", "r")).thenReturn(List.of(
            pr(42, "[" + key + "] QA Queue 칩 수정", "merged", later),
            pr(43, "QA Queue 리팩터링", "open", later)));
        when(github.pull("o", "r", 50)).thenReturn(Optional.of(pr(50, "칩 굵기", "open", later.plusSeconds(10))));
        // 담당 팀(프론트)이 링크를 붙인다
        mvc.perform(as(put(base() + "/fix-link"), front).contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://github.com/o/r/pull/50\"}"))
            .andExpect(status().isOk());
        // Re-QA가 1111111 커밋에서 PASS
        ProjectRun reQa = runs.save(new ProjectRun(project, RunKind.RE_QA, owner, Instant.now()));
        reQa.succeedReQa("1111111aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 1, 0, 0, Instant.now());
        verifications.save(Verification.reQa(issue, VerificationResult.PASS, "글자 #000000", owner, reQa));
        em.flush();
        mvc.perform(as(get(base() + "/code-trace"), designer))
            .andExpect(jsonPath("$.data.fixes.length()").value(3))
            .andExpect(jsonPath("$.data.fixes[?(@.source == 'key' && @.kind == 'commit')].commit.sha").value("1111111aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
            .andExpect(jsonPath("$.data.fixes[?(@.source == 'key' && @.kind == 'commit')].verdict").value("pass"))
            .andExpect(jsonPath("$.data.fixes[?(@.source == 'key' && @.kind == 'pull_request')].pull_request.number").value(42))
            .andExpect(jsonPath("$.data.fixes[?(@.source == 'manual')].pull_request.number").value(50));
    }

    @Test
    void 수정_링크는_담당_팀만_연결된_저장소의_PR_커밋_주소만_붙인다() throws Exception {
        mvc.perform(as(put(base() + "/fix-link"), designer).contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"https://github.com/o/r/pull/1\"}")).andExpect(status().isForbidden());
        mvc.perform(as(put(base() + "/fix-link"), owner).contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"https://github.com/o/r/pull/1\"}")).andExpect(status().isForbidden());
        for (String bad : List.of("https://github.com/other/r/pull/1", "https://gitlab.com/o/r/pull/1", "https://github.com/o/r/issues/1",
            "https://github.com/o/r/commit/xyz", "")) {
            mvc.perform(as(put(base() + "/fix-link"), front).contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"" + bad + "\"}")).andExpect(status().isBadRequest());
        }
        mvc.perform(as(put(base() + "/fix-link"), front).contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"https://github.com/O/R/commit/1111111\"}")).andExpect(status().isOk());
        em.flush();
        em.clear();
        org.assertj.core.api.Assertions.assertThat(issues.findById(issue.getId()).orElseThrow().getFixLinkUrl())
            .isEqualTo("https://github.com/O/R/commit/1111111");
        mvc.perform(as(delete(base() + "/fix-link"), front)).andExpect(status().isNoContent());
        mvc.perform(as(delete(base() + "/fix-link"), front)).andExpect(status().isNoContent());
        em.flush();
        em.clear();
        org.assertj.core.api.Assertions.assertThat(issues.findById(issue.getId()).orElseThrow().getFixLinkUrl()).isNull();
    }

    @Test
    void GitHub를_읽지_못해도_저장된_값으로_200이고_unavailable() throws Exception {
        when(github.blameLine(anyString(), anyString(), anyString(), anyString(), anyInt()))
            .thenThrow(new GithubApiException("한도", 403, null));
        when(github.recentPulls(anyString(), anyString())).thenThrow(new GithubApiException("한도", 403, null));
        issue.attachFixLink("https://github.com/o/r/pull/7", front, Instant.now());
        em.flush();
        mvc.perform(as(get(base() + "/code-trace"), front))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.github").value("unavailable"))
            .andExpect(jsonPath("$.data.code.path").value("src/QaParts.tsx"))
            .andExpect(jsonPath("$.data.origin").doesNotExist())
            .andExpect(jsonPath("$.data.fixes[0].pull_request.number").value(7))
            .andExpect(jsonPath("$.data.fixes[0].url").value("https://github.com/o/r/pull/7"));
    }

    @Test
    void 저장소가_없으면_not_connected이고_다른_프로젝트_이슈는_404() throws Exception {
        Project other = projects.save(new Project("다른", owner));
        projectMembers.save(new ProjectMember(other, owner, null));
        em.flush();
        mvc.perform(as(get("/api/v1/projects/" + other.getId() + "/qa-issues/" + issue.getId() + "/code-trace"), owner))
            .andExpect(status().isNotFound());
        Member outsider = members.save(new Member("ct-out@test.dev", "외부"));
        mvc.perform(as(get(base() + "/code-trace"), outsider)).andExpect(status().isForbidden());
        project.connectGithub(null, null, null, null, null);
        em.flush();
        mvc.perform(as(get(base() + "/code-trace"), front))
            .andExpect(jsonPath("$.data.github").value("not_connected"))
            .andExpect(jsonPath("$.data.issue_key").value("QA-" + issue.getId()));
    }

    @Test
    void 수동_QA는_기준_프레임의_화면_파일로_코드를_잇고_문제_코드는_파일_기준이다() throws Exception {
        ProjectRun analysis = runs.findFirstByProjectIdAndKindOrderByCreatedAtDescIdDesc(project.getId(), RunKind.ANALYSIS).orElseThrow();
        mappings.save(DesignCodeMapping.frame(project, analysis, "F2", "QUE-01", "src/pages/QaQueuePage.tsx", Instant.now()));
        QAIssue manual = issues.save(new QAIssue(project, "카드가 작다", "/queue", List.of("큐를 연다"), com.acjb.server.domain.PlatformScope.COMMON,
            null, List.of(), null, null, null, front));
        pins.save(new com.acjb.server.domain.ManualQaPin(project, manual, 1, "r1", "카드 크기", "/queue", null, null, null, null, null,
            com.acjb.server.domain.LocationBasis.NONE, com.acjb.server.domain.CaptureStatus.NONE, null, null, null, null, "F2", "QUE-01", Instant.now()));
        em.flush();
        CommitInfo last = commit("abc1234aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "feat: 큐", Instant.parse("2026-11-09T00:00:00Z"));
        when(github.lastCommitForPath("o", "r", SHA, "src/pages/QaQueuePage.tsx")).thenReturn(Optional.of(last));
        when(github.pullsForCommit("o", "r", last.sha())).thenReturn(List.of());
        mvc.perform(as(get("/api/v1/projects/" + project.getId() + "/qa-issues/" + manual.getId() + "/code-trace"), front))
            .andExpect(jsonPath("$.data.code.basis").value("screen"))
            .andExpect(jsonPath("$.data.code.path").value("src/pages/QaQueuePage.tsx"))
            .andExpect(jsonPath("$.data.code.line").doesNotExist())
            .andExpect(jsonPath("$.data.code.url").value("https://github.com/o/r/blob/" + SHA + "/src/pages/QaQueuePage.tsx"))
            .andExpect(jsonPath("$.data.origin.basis").value("file"))
            .andExpect(jsonPath("$.data.origin.commit.sha").value(last.sha()));
        verify(github, never()).blameLine(anyString(), anyString(), anyString(), anyString(), anyInt());
    }
}
