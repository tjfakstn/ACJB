package com.acjb.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.acjb.server.domain.Embedding;
import com.acjb.server.domain.EmbeddingSourceType;
import com.acjb.server.domain.IssueStatus;
import com.acjb.server.domain.Member;
import com.acjb.server.domain.MemberRole;
import com.acjb.server.domain.PlatformScope;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.ProjectMember;
import com.acjb.server.domain.QAIssue;
import com.acjb.server.domain.StatusHistory;
import com.acjb.server.domain.StyleMismatch;
import com.acjb.server.domain.StyleProperty;
import com.acjb.server.domain.Verification;
import com.acjb.server.domain.VerificationResult;
import com.acjb.server.recheck.EmbeddingClient;
import com.acjb.server.recheck.RecheckFailedException;
import com.acjb.server.recheck.RecheckSuggester;
import com.acjb.server.repository.EmbeddingRepository;
import com.acjb.server.repository.MemberRepository;
import com.acjb.server.repository.NotificationRepository;
import com.acjb.server.repository.ProjectMemberRepository;
import com.acjb.server.repository.ProjectRepository;
import com.acjb.server.repository.QAIssueRepository;
import com.acjb.server.repository.StatusHistoryRepository;
import com.acjb.server.repository.StyleMismatchRepository;
import com.acjb.server.repository.VerificationRepository;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;

/**
 * POST /projects/{projectId}/qa-issues/{issueId}/recheck-suggestions (AC20·N16). 메서드 이름 앞의 id는 golden_cases.yaml 케이스와 대응한다.
 * 임베딩(Voyage)·LLM(Claude)은 대역이다 — 서버가 규칙을 지키는지만 본다. 제안 품질은 evals/에서 잰다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RecheckSuggestionApiTest {

    private static final String CSRF = "test-csrf-token";

    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired ProjectRepository projects;
    @Autowired MemberRepository members;
    @Autowired ProjectMemberRepository projectMembers;
    @Autowired QAIssueRepository issues;
    @Autowired StatusHistoryRepository histories;
    @Autowired VerificationRepository verifications;
    @Autowired NotificationRepository notifications;
    @Autowired StyleMismatchRepository mismatches;
    @Autowired EmbeddingRepository embeddings;

    @MockitoBean EmbeddingClient embeddingClient;
    @MockitoBean RecheckSuggester suggester;

    Project project;
    Member reporter;
    Member assignee;

    @BeforeEach
    void setUp() {
        Member owner = members.save(new Member("rs-owner-" + java.util.UUID.randomUUID() + "@test.dev", "소유자"));
        project = projects.save(new Project("recheck-api", owner));
        reporter = members.save(new Member("rs-reporter@test.dev", "등록자"));
        assignee = members.save(new Member("rs-assignee@test.dev", "담당자"));
        projectMembers.save(new ProjectMember(project, owner, MemberRole.PLANNER));
        projectMembers.save(new ProjectMember(project, reporter, MemberRole.DESIGNER));
        projectMembers.save(new ProjectMember(project, assignee, MemberRole.DEVELOPER));

        when(embeddingClient.isConfigured()).thenReturn(true);
        when(embeddingClient.model()).thenReturn("test-model");
        // 문장마다 다른 0이 아닌 벡터 — 검색 순위는 이 테스트들의 판정에 쓰지 않는다(문서가 8개 이하라 모두 들어간다)
        when(embeddingClient.embed(anyList(), any())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            List<float[]> out = new ArrayList<>();
            for (String t : texts) {
                out.add(new float[] {1f, (t.length() % 7) + 1f, (Math.abs(t.hashCode()) % 5) + 1f});
            }
            return out;
        });
    }

    private QAIssue issue(Project p, String screen, String description, IssueStatus status) {
        QAIssue issue = issues.save(new QAIssue(p, description, screen, List.of(screen + " 진입", "확인"),
            PlatformScope.COMMON, null, List.of(), null, null, assignee, reporter));
        histories.save(new StatusHistory(issue, IssueStatus.NEEDS_CHECK, reporter));
        if (status != IssueStatus.NEEDS_CHECK) {
            issue.changeStatus(status);
            histories.save(new StatusHistory(issue, status, assignee));
        }
        issues.flush();
        return issue;
    }

    private QAIssue fixedTarget() {
        return issue(project, "결제 화면", "결제 버튼 색이 시안과 달라요", IssueStatus.FIXED);
    }

    private ResultActions suggestAs(Member actor, QAIssue issue) throws Exception {
        return mvc.perform(post("/api/v1/projects/" + issue.getProject().getId() + "/qa-issues/" + issue.getId() + "/recheck-suggestions")
            .with(oauth2Login().attributes(a -> a.put("email", actor.getEmail())))
            .cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF));
    }

    private String llmPrompt() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(suggester).suggest(captor.capture());
        return captor.getValue();
    }

    // --- AC20 ---

    @Test
    void rs001_등록자가_요청하면_검색된_문서를_근거로_단_제안을_돌려준다() throws Exception {
        QAIssue target = fixedTarget();
        QAIssue related = issue(project, "결제 완료 화면", "결제 완료 버튼 색이 달라요", IssueStatus.RESOLVED);
        when(suggester.suggest(anyString())).thenReturn("""
            {"suggestions":[{"screen":"결제 완료 화면","reason":"같은 결제 버튼을 쓴다","evidence_ids":["qa_issue:%d"]}]}
            """.formatted(related.getId()));

        suggestAs(reporter, target)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.issue_id").value(String.valueOf(target.getId())))
            .andExpect(jsonPath("$.data.suggestions.length()").value(1))
            .andExpect(jsonPath("$.data.suggestions[0].screen").value("결제 완료 화면"))
            .andExpect(jsonPath("$.data.suggestions[0].reason").value("같은 결제 버튼을 쓴다"))
            .andExpect(jsonPath("$.data.suggestions[0].evidence[0].type").value("qa_issue"))
            .andExpect(jsonPath("$.data.suggestions[0].evidence[0].id").value(String.valueOf(related.getId())))
            .andExpect(jsonPath("$.data.searched_count").value(1))
            .andExpect(jsonPath("$.data.dropped_count").value(0));
        verify(suggester, times(1)).suggest(anyString());
    }

    @Test
    void rs002_fixed가_아니면_409이고_임베딩_LLM을_부르지_않는다() throws Exception {
        QAIssue target = issue(project, "결제 화면", "결제 버튼 색이 달라요", IssueStatus.NEEDS_CHECK);
        issue(project, "결제 완료 화면", "다른 이슈", IssueStatus.NEEDS_CHECK);

        suggestAs(reporter, target)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("INVALID_TRANSITION"));
        verify(embeddingClient, never()).embed(anyList(), any());
        verify(suggester, never()).suggest(anyString());
    }

    @Test
    void rs003_재검증_권한이_없는_담당자가_요청하면_403이고_외부_호출이_없다() throws Exception {
        QAIssue target = fixedTarget();
        issue(project, "결제 완료 화면", "다른 이슈", IssueStatus.NEEDS_CHECK);

        suggestAs(assignee, target)
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.success").value(false));
        verify(embeddingClient, never()).embed(anyList(), any());
        verify(suggester, never()).suggest(anyString());
    }

    @Test
    void rs004_검색할_다른_문서가_없으면_LLM을_부르지_않고_빈_제안() throws Exception {
        QAIssue target = fixedTarget();

        suggestAs(reporter, target)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.suggestions.length()").value(0))
            .andExpect(jsonPath("$.data.searched_count").value(0))
            .andExpect(jsonPath("$.data.dropped_count").value(0));
        verify(suggester, never()).suggest(anyString());
    }

    // --- N16 ---

    @Test
    void ng014_검색에_없던_id를_근거로_단_제안은_통째로_버리고_센다() throws Exception {
        QAIssue target = fixedTarget();
        QAIssue doc7 = issue(project, "결제 완료 화면", "결제 완료 버튼 색", IssueStatus.RESOLVED);
        QAIssue doc9 = issue(project, "장바구니 화면", "장바구니 버튼 색", IssueStatus.RESOLVED);
        long fakeId = doc9.getId() + 1000;
        when(suggester.suggest(anyString())).thenReturn("""
            {"suggestions":[
              {"screen":"결제 완료 화면","reason":"근거 있음","evidence_ids":["qa_issue:%d"]},
              {"screen":"장바구니 화면","reason":"근거 일부 지어냄","evidence_ids":["qa_issue:%d","qa_issue:%d"]}
            ]}
            """.formatted(doc7.getId(), doc9.getId(), fakeId));

        suggestAs(reporter, target)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.suggestions.length()").value(1))
            .andExpect(jsonPath("$.data.suggestions[0].screen").value("결제 완료 화면"))
            .andExpect(jsonPath("$.data.dropped_count").value(1))
            .andExpect(content().string(not(containsString(String.valueOf(fakeId)))))
            .andExpect(content().string(not(containsString("장바구니 화면"))));
    }

    @Test
    void ng015_다른_프로젝트_문서는_검색_근거에_들어가지_않는다() throws Exception {
        QAIssue target = fixedTarget();
        QAIssue mine = issue(project, "회원가입 화면", "약관 체크박스가 안 눌려요", IssueStatus.NEEDS_CHECK);
        Project other = projects.save(new Project("recheck-other", reporter));
        projectMembers.save(new ProjectMember(other, reporter, MemberRole.DESIGNER));
        QAIssue foreign = issue(other, "결제 화면", "결제 버튼 색이 시안과 달라요", IssueStatus.FIXED);
        // 다른 프로젝트 문서에도 임베딩이 이미 있는 상태 — 그래도 검색에 들어오면 안 된다
        embeddings.save(new Embedding(other, EmbeddingSourceType.QA_ISSUE, foreign.getId(), "test-model", new float[] {1f, 1f, 1f}));
        when(suggester.suggest(anyString())).thenReturn("{\"suggestions\":[]}");

        suggestAs(reporter, target)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.searched_count").value(1));
        String prompt = llmPrompt();
        assertThat(prompt).contains("qa_issue:" + mine.getId());
        assertThat(prompt).doesNotContain("qa_issue:" + foreign.getId() + "\"");
        // 다른 프로젝트 원본에 대한 임베딩도 이 요청으로 새로 만들지 않는다
        assertThat(embeddings.findByProjectIdAndModel(project.getId(), "test-model"))
            .allSatisfy(e -> assertThat(e.getProject().getId()).isEqualTo(project.getId()));
        assertThat(embeddings.findByProjectIdAndModel(other.getId(), "test-model")).hasSize(1);
    }

    @Test
    void ng016_제안을_요청해도_이슈_스타일차이_재검증_알림을_만들거나_바꾸지_않는다() throws Exception {
        QAIssue target = fixedTarget();
        QAIssue related = issue(project, "결제 완료 화면", "결제 완료 버튼 색이 달라요", IssueStatus.RESOLVED);
        when(suggester.suggest(anyString())).thenReturn("""
            {"suggestions":[{"screen":"결제 완료 화면","reason":"같은 버튼","evidence_ids":["qa_issue:%d"]}]}
            """.formatted(related.getId()));
        long issuesBefore = issues.count();
        long mismatchesBefore = mismatches.count();
        long verificationsBefore = verifications.count();
        long notificationsBefore = notifications.count();
        int historyBefore = histories.findByIssueIdOrderByChangedAtAscIdAsc(target.getId()).size();

        suggestAs(reporter, target).andExpect(status().isOk());

        em.flush();
        em.clear();
        assertThat(issues.count()).isEqualTo(issuesBefore);
        assertThat(mismatches.count()).isEqualTo(mismatchesBefore);
        assertThat(verifications.count()).isEqualTo(verificationsBefore);
        assertThat(notifications.count()).isEqualTo(notificationsBefore);
        assertThat(issues.findById(target.getId()).orElseThrow().getStatus()).isEqualTo(IssueStatus.FIXED);
        assertThat(histories.findByIssueIdOrderByChangedAtAscIdAsc(target.getId())).hasSize(historyBefore);
    }

    // --- 그 밖의 규칙 (설계 3·7절) ---

    @Test
    void 등록자가_제거됐으면_소유자가_요청할_수_있다() throws Exception {
        QAIssue target = fixedTarget();
        projectMembers.delete(projectMembers.findByProjectIdAndMemberId(project.getId(), reporter.getId()).orElseThrow());
        projectMembers.flush();

        suggestAs(project.getOwner(), target).andExpect(status().isOk());
    }

    @Test
    void 근거가_비었거나_대상_이슈와_같은_화면이거나_화면이_겹치면_버린다() throws Exception {
        QAIssue target = fixedTarget();
        QAIssue related = issue(project, "결제 완료 화면", "결제 완료 버튼 색", IssueStatus.RESOLVED);
        when(suggester.suggest(anyString())).thenReturn("""
            {"suggestions":[
              {"screen":"결제 완료 화면","reason":"근거 있음","evidence_ids":["qa_issue:%1$d"]},
              {"screen":"결제 완료 화면","reason":"같은 화면 두 번","evidence_ids":["qa_issue:%1$d"]},
              {"screen":"결제 화면","reason":"대상 이슈 화면","evidence_ids":["qa_issue:%1$d"]},
              {"screen":"주문 내역 화면","reason":"근거 없음","evidence_ids":[]}
            ]}
            """.formatted(related.getId()));

        suggestAs(reporter, target)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.suggestions.length()").value(1))
            .andExpect(jsonPath("$.data.dropped_count").value(3));
    }

    @Test
    void 스타일_차이와_fail_재검증은_검색_문서가_되고_pass_재검증은_아니다() throws Exception {
        QAIssue target = fixedTarget();
        StyleMismatch mismatch = mismatches.save(StyleMismatch.manual(project, null, "결제 완료 화면",
            StyleProperty.COLOR, "#1A73E8", "#1A74E8"));
        QAIssue old = issue(project, "주문 화면", "주문 버튼 색", IssueStatus.FIXED);
        Verification failed = verifications.save(new Verification(old, VerificationResult.FAIL, "주문 화면만", reporter));
        Verification passed = verifications.save(new Verification(old, VerificationResult.PASS, null, reporter));
        when(suggester.suggest(anyString())).thenReturn("{\"suggestions\":[]}");

        suggestAs(reporter, target).andExpect(status().isOk());

        String prompt = llmPrompt();
        assertThat(prompt).contains("style_mismatch:" + mismatch.getId(), "verification:" + failed.getId());
        assertThat(prompt).doesNotContain("verification:" + passed.getId() + "\"");
        // 대상 이슈 자신은 검색 문서로 넘기지 않는다
        assertThat(prompt).doesNotContain("qa_issue:" + target.getId() + "\"");
    }

    @Test
    void 이미_만든_임베딩은_다시_만들지_않는다() throws Exception {
        QAIssue target = fixedTarget();
        issue(project, "결제 완료 화면", "결제 완료 버튼 색", IssueStatus.RESOLVED);
        when(suggester.suggest(anyString())).thenReturn("{\"suggestions\":[]}");

        suggestAs(reporter, target).andExpect(status().isOk());
        int afterFirst = embeddings.findByProjectIdAndModel(project.getId(), "test-model").size();
        suggestAs(reporter, target).andExpect(status().isOk());

        assertThat(afterFirst).isEqualTo(2);
        assertThat(embeddings.findByProjectIdAndModel(project.getId(), "test-model")).hasSize(2);
    }

    @Test
    void LLM_출력이_스키마를_벗어나면_502이고_일부도_돌려주지_않는다() throws Exception {
        QAIssue target = fixedTarget();
        QAIssue related = issue(project, "결제 완료 화면", "결제 완료 버튼 색", IssueStatus.RESOLVED);
        when(suggester.suggest(anyString())).thenReturn("""
            {"suggestions":[{"screen":"결제 완료 화면","reason":"x","evidence_ids":["qa_issue:%d"],"confidence":0.9}]}
            """.formatted(related.getId()));

        suggestAs(reporter, target)
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("SUGGESTION_FAILED"));
    }

    @Test
    void 제안이_5개를_넘으면_502() throws Exception {
        QAIssue target = fixedTarget();
        QAIssue related = issue(project, "결제 완료 화면", "결제 완료 버튼 색", IssueStatus.RESOLVED);
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            items.append(i == 0 ? "" : ",").append("{\"screen\":\"화면").append(i)
                .append("\",\"reason\":\"r\",\"evidence_ids\":[\"qa_issue:").append(related.getId()).append("\"]}");
        }
        when(suggester.suggest(anyString())).thenReturn("{\"suggestions\":[" + items + "]}");

        suggestAs(reporter, target)
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.error.code").value("SUGGESTION_FAILED"));
    }

    @Test
    void 임베딩_호출이_실패하면_502이고_LLM을_부르지_않는다() throws Exception {
        QAIssue target = fixedTarget();
        issue(project, "결제 완료 화면", "결제 완료 버튼 색", IssueStatus.RESOLVED);
        when(embeddingClient.embed(anyList(), any())).thenThrow(new RecheckFailedException("임베딩 API 호출에 실패했습니다(HTTP 500)."));

        suggestAs(reporter, target)
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.error.code").value("SUGGESTION_FAILED"));
        verify(suggester, never()).suggest(anyString());
    }

    @Test
    void 다른_프로젝트_이슈는_404() throws Exception {
        Project other = projects.save(new Project("recheck-404", reporter));
        projectMembers.save(new ProjectMember(other, reporter, MemberRole.DESIGNER));
        QAIssue foreign = issue(other, "결제 화면", "결제 버튼", IssueStatus.FIXED);

        mvc.perform(post("/api/v1/projects/" + project.getId() + "/qa-issues/" + foreign.getId() + "/recheck-suggestions")
                .with(oauth2Login().attributes(a -> a.put("email", reporter.getEmail())))
                .cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF))
            .andExpect(status().isNotFound());
        verify(embeddingClient, never()).embed(anyList(), any());
    }
}
