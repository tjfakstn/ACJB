package com.acjb.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.acjb.server.domain.DesignCodeMapping;
import com.acjb.server.domain.IssueSource;
import com.acjb.server.domain.IssueStatus;
import com.acjb.server.domain.Member;
import com.acjb.server.domain.MemberRole;
import com.acjb.server.domain.NotificationEvent;
import com.acjb.server.domain.PlatformScope;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.ProjectMember;
import com.acjb.server.domain.ProjectRun;
import com.acjb.server.domain.QAIssue;
import com.acjb.server.domain.RunKind;
import com.acjb.server.domain.RunStatus;
import com.acjb.server.domain.StatusHistory;
import com.acjb.server.domain.StyleMismatch;
import com.acjb.server.domain.StyleProperty;
import com.acjb.server.domain.Verification;
import com.acjb.server.domain.VerificationResult;
import com.acjb.server.domain.VerificationSource;
import com.acjb.server.qa.FirstQaJob;
import com.acjb.server.qa.QaComparator;
import com.acjb.server.qa.QaFailedException;
import com.acjb.server.qa.ReQaJob;
import com.acjb.server.repository.DesignCodeMappingRepository;
import com.acjb.server.repository.MemberRepository;
import com.acjb.server.repository.NotificationRepository;
import com.acjb.server.repository.ProjectMemberRepository;
import com.acjb.server.repository.ProjectRepository;
import com.acjb.server.repository.ProjectRunRepository;
import com.acjb.server.repository.QAIssueRepository;
import com.acjb.server.repository.StatusHistoryRepository;
import com.acjb.server.repository.StyleMismatchRepository;
import com.acjb.server.repository.VerificationRepository;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;

/**
 * AC23 첫 QA·AC28 자동 Re-QA(골든 케이스 fq-001~005, rq-001~004, ng-019). 비교기는 가짜로 바꿔 끼운다 — LLM을 부르지 않는다.
 * 테스트 트랜잭션 안에서는 커밋 뒤 이벤트가 오지 않으므로 실행(job.run)을 직접 부른다
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class QaRunApiTest {

    private static final String SHA = "b".repeat(40);

    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired ProjectRepository projects;
    @Autowired MemberRepository members;
    @Autowired ProjectMemberRepository projectMembers;
    @Autowired ProjectRunRepository runs;
    @Autowired DesignCodeMappingRepository mappings;
    @Autowired QAIssueRepository issues;
    @Autowired StyleMismatchRepository mismatches;
    @Autowired StatusHistoryRepository histories;
    @Autowired VerificationRepository verifications;
    @Autowired NotificationRepository notifications;
    @Autowired FirstQaJob firstQaJob;
    @Autowired ReQaJob reQaJob;
    @MockitoBean QaComparator comparator;

    Project project;
    Member owner;
    Member front;

    @BeforeEach
    void setUp() {
        owner = members.save(new Member("qa-owner@test.dev", "소유자"));
        front = members.save(new Member("qa-front@test.dev", "프론트"));
        project = projects.save(new Project("QA 프로젝트", owner));
        projectMembers.save(new ProjectMember(project, owner, null));
        projectMembers.save(new ProjectMember(project, front, MemberRole.FRONTEND));
        project.connectGithub("https://github.com/o/r", "o/r", "main", "main", Instant.now());
    }

    /** 성공한 비교 분석 — 홈(F1) 화면의 주문 버튼(12:34) ↔ src/components/Button.tsx */
    private void analyzed() {
        ProjectRun run = runs.save(new ProjectRun(project, RunKind.ANALYSIS, owner, Instant.now()));
        run.succeed(SHA, 2, 0, Instant.now());
        mappings.save(DesignCodeMapping.frame(project, run, "F1", "홈", "src/pages/Home.tsx", Instant.now()));
        mappings.save(DesignCodeMapping.node(project, run, "F1", "홈", "12:34", "주문 버튼", "src/components/Button.tsx", "OrderButton",
            Instant.now()));
        em.flush();
    }

    private ResultActions postAs(String path, Member as, String body) throws Exception {
        var req = post("/api/v1/projects/" + project.getId() + path)
            .with(oauth2Login().attributes(a -> a.put("email", as.getEmail())))
            .cookie(new Cookie("XSRF-TOKEN", "t")).header("X-XSRF-TOKEN", "t");
        if (body != null) {
            req = req.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(req);
    }

    private Long latestRun(RunKind kind) {
        return runs.findFirstByProjectIdAndKindOrderByCreatedAtDescIdDesc(project.getId(), kind).orElseThrow().getId();
    }

    private ProjectRun reload(Long runId) {
        em.flush();
        em.clear();
        return runs.findById(runId).orElseThrow();
    }

    private long runCount() {
        return runs.count();
    }

    // --- AC23 첫 QA ---

    @Test
    void fq001_근거가_둘_다_있는_차이만_이슈로_만들고_값은_근거에서만_채운다() throws Exception {
        analyzed();
        when(comparator.compare(any())).thenReturn(new QaComparator.FirstQaResult(SHA,
            List.of(new QaComparator.Finding("12:34", StyleProperty.COLOR, "#1A73E8", "#2B7DE9", "src/components/Button.tsx", 20)), 0));

        postAs("/first-qa", owner, "{\"scope\":[\"color_typography\"]}")
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.kind").value("first_qa"))
            .andExpect(jsonPath("$.data.scope[0]").value("color_typography"));
        Long runId = latestRun(RunKind.FIRST_QA);
        firstQaJob.run(runId);

        ProjectRun run = reload(runId);
        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.getCreatedCount()).isEqualTo(1);
        assertThat(run.getDroppedCount()).isZero();
        assertThat(run.isDemo()).isFalse();
        QAIssue issue = issues.findAll().stream().filter(i -> i.getProject().getId().equals(project.getId())).findFirst().orElseThrow();
        assertThat(issue.getSource()).isEqualTo(IssueSource.FIRST_QA);
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.NEEDS_CHECK);
        assertThat(issue.getReportedBy().getId()).isEqualTo(owner.getId());
        assertThat(issue.getAssignedTeam()).isNull();
        assertThat(issue.getPlatformScope()).isEqualTo(PlatformScope.COMMON);
        assertThat(issue.getScreenLocation()).contains("홈").contains("주문 버튼");
        assertThat(issue.getReproSteps()).containsExactly("'홈' 화면을 연다", "'주문 버튼'를 확인한다");
        assertThat(issue.getStyleMismatch().getProperty()).isEqualTo(StyleProperty.COLOR);
        assertThat(issue.getStyleMismatch().getExpectedValue()).isEqualTo("#1A73E8");
        assertThat(issue.getStyleMismatch().getActualValue()).isEqualTo("#2B7DE9");
        assertThat(issue.getStyleMismatch().getCodePath()).isEqualTo("src/components/Button.tsx");
        assertThat(issue.getDescription()).doesNotStartWith("[시연용]");
        assertThat(notifications.count()).isZero();

        // 비교기에는 고른 범위의 property와 노드 짝만 넘긴다
        ArgumentCaptor<QaComparator.FirstQaInput> input = ArgumentCaptor.forClass(QaComparator.FirstQaInput.class);
        verify(comparator).compare(input.capture());
        assertThat(input.getValue().properties()).containsExactly(StyleProperty.COLOR, StyleProperty.FONT_WEIGHT, StyleProperty.FONT_SIZE);
        assertThat(input.getValue().targets()).extracting(QaComparator.Target::nodeId).containsExactly("12:34");
    }

    @Test
    void ng017_입력에_없는_노드_범위_밖_property_빈_값은_버리고_센다() throws Exception {
        analyzed();
        when(comparator.compare(any())).thenReturn(new QaComparator.FirstQaResult(SHA, List.of(
            new QaComparator.Finding("99:99", StyleProperty.COLOR, "#111", "#222", "src/components/Button.tsx", 1),
            new QaComparator.Finding("12:34", StyleProperty.RADIUS, "8px", "4px", "src/components/Button.tsx", 2),
            new QaComparator.Finding("12:34", StyleProperty.COLOR, "#111", "", "src/components/Button.tsx", 3)), 1));
        postAs("/first-qa", owner, "{\"scope\":[\"color_typography\"]}").andExpect(status().isAccepted());
        Long runId = latestRun(RunKind.FIRST_QA);
        firstQaJob.run(runId);
        ProjectRun run = reload(runId);
        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.getCreatedCount()).isZero();
        assertThat(run.getDroppedCount()).isEqualTo(4);
        assertThat(issues.count()).isZero();
    }

    @Test
    void ng019_비교기가_실패하면_이슈를_하나도_남기지_않고_failed() throws Exception {
        analyzed();
        when(comparator.compare(any())).thenThrow(new QaFailedException("QA 비교용 LLM API 키(ANTHROPIC_API_KEY)가 설정되지 않았어요."));
        postAs("/first-qa", owner, "{\"scope\":[\"color_typography\"]}").andExpect(status().isAccepted());
        Long runId = latestRun(RunKind.FIRST_QA);
        firstQaJob.run(runId);
        ProjectRun run = reload(runId);
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getErrorMessage()).contains("ANTHROPIC_API_KEY");
        assertThat(issues.count()).isZero();
        assertThat(mismatches.count()).isZero();
    }

    @Test
    void 가짜_비교기면_실행에_demo_이슈에_시연용을_남긴다() throws Exception {
        analyzed();
        when(comparator.demo()).thenReturn(true);
        when(comparator.compare(any())).thenReturn(new QaComparator.FirstQaResult(SHA,
            List.of(new QaComparator.Finding("12:34", StyleProperty.SPACING, "padding 12px 24px", "padding 8px 20px",
                "src/components/Button.tsx", 5)), 0));
        postAs("/first-qa", owner, "{\"scope\":[\"size_spacing\"]}").andExpect(status().isAccepted());
        Long runId = latestRun(RunKind.FIRST_QA);
        firstQaJob.run(runId);
        assertThat(reload(runId).isDemo()).isTrue();
        assertThat(issues.findAll()).singleElement().satisfies(i -> assertThat(i.getDescription()).startsWith("[시연용]"));
    }

    @Test
    void fq002_첫_QA가_이미_성공했으면_409() throws Exception {
        analyzed();
        ProjectRun done = runs.save(new ProjectRun(project, RunKind.FIRST_QA, owner, Instant.now()));
        done.succeed(SHA, 0, 0, Instant.now());
        em.flush();
        postAs("/first-qa", owner, "{\"scope\":[\"color_typography\"]}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("NOT_READY"));
    }

    @Test
    void 비교_분석_전이면_409() throws Exception {
        postAs("/first-qa", owner, "{\"scope\":[\"color_typography\"]}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("NOT_READY"));
    }

    @Test
    void fq003_소유자가_아니면_403이고_실행을_만들지_않는다() throws Exception {
        analyzed();
        long before = runCount();
        postAs("/first-qa", front, "{\"scope\":[\"color_typography\"]}").andExpect(status().isForbidden());
        assertThat(runCount()).isEqualTo(before);
        verify(comparator, never()).compare(any());
    }

    @Test
    void fq005_검증_범위가_비었거나_틀리면_400이고_실행을_만들지_않는다() throws Exception {
        analyzed();
        long before = runCount();
        postAs("/first-qa", owner, "{\"scope\":[]}").andExpect(status().isBadRequest());
        postAs("/first-qa", owner, "{\"scope\":[\"animation\"]}").andExpect(status().isBadRequest());
        postAs("/first-qa", owner, null).andExpect(status().isBadRequest());
        assertThat(runCount()).isEqualTo(before);
    }

    // --- AC28 자동 Re-QA ---

    /** 첫 QA가 만든 이슈를 fixed·프론트엔드 팀으로 만든다 */
    private QAIssue fixedFirstQaIssue(String nodeId, StyleProperty property, String expected, String actual) {
        ProjectRun fq = runs.findFirstByProjectIdAndKindOrderByCreatedAtDescIdDesc(project.getId(), RunKind.FIRST_QA).orElseGet(() -> {
            ProjectRun r = runs.save(new ProjectRun(project, RunKind.FIRST_QA, owner, Instant.now()));
            r.succeed(SHA, 0, 0, Instant.now());
            return r;
        });
        StyleMismatch m = mismatches.save(StyleMismatch.firstQa(project, "F1", "홈", nodeId, property, expected, actual,
            "src/components/Button.tsx", 10));
        QAIssue issue = issues.save(QAIssue.firstQa(project, fq, "차이", "홈", "주문 버튼", m, owner));
        histories.save(new StatusHistory(issue, IssueStatus.NEEDS_CHECK, owner));
        issue.assignTeam(MemberRole.FRONTEND);
        issue.changeStatus(IssueStatus.FIXED);
        histories.save(new StatusHistory(issue, IssueStatus.FIXED, front));
        issues.flush();
        return issue;
    }

    @Test
    void rq001_같아졌으면_pass로_resolved_여전히_다르면_fail로_needs_recheck와_팀_알림() throws Exception {
        analyzed();
        QAIssue a = fixedFirstQaIssue("12:34", StyleProperty.COLOR, "#1A73E8", "#2B7DE9");
        QAIssue b = fixedFirstQaIssue("12:35", StyleProperty.RADIUS, "8px", "6px");
        Map<Long, String> observed = new LinkedHashMap<>();
        observed.put(a.getId(), "#1a73e8");
        observed.put(b.getId(), "4px");
        when(comparator.recheck(any())).thenReturn(new QaComparator.ReQaResult(SHA, observed));

        postAs("/re-qa", owner, null).andExpect(status().isAccepted()).andExpect(jsonPath("$.data.kind").value("re_qa"));
        Long runId = latestRun(RunKind.RE_QA);
        reQaJob.run(runId);

        ProjectRun run = reload(runId);
        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.getCreatedCount()).isEqualTo(1);
        assertThat(run.getFailedCount()).isEqualTo(1);
        assertThat(run.getDroppedCount()).isZero();
        assertThat(issues.findById(a.getId()).orElseThrow().getStatus()).isEqualTo(IssueStatus.RESOLVED);
        assertThat(issues.findById(b.getId()).orElseThrow().getStatus()).isEqualTo(IssueStatus.NEEDS_RECHECK);
        Verification va = verifications.findByIssueIdOrderByVerifiedAtDescIdDesc(a.getId()).getFirst();
        assertThat(va.getResult()).isEqualTo(VerificationResult.PASS);
        assertThat(va.getSource()).isEqualTo(VerificationSource.RE_QA);
        assertThat(va.getVerifiedBy().getId()).isEqualTo(owner.getId());
        assertThat(va.getObservedValue()).isEqualTo("#1a73e8");
        Verification vb = verifications.findByIssueIdOrderByVerifiedAtDescIdDesc(b.getId()).getFirst();
        assertThat(vb.getResult()).isEqualTo(VerificationResult.FAIL);
        assertThat(vb.getObservedValue()).isEqualTo("4px");
        assertThat(notifications.countByIssueIdAndEvent(b.getId(), NotificationEvent.RECHECK_FAILED)).isEqualTo(1);
        assertThat(notifications.countByIssueIdAndEvent(a.getId(), NotificationEvent.RECHECK_FAILED)).isZero();
        assertThat(notifications.findAll()).singleElement()
            .satisfies(n -> assertThat(n.getRecipient().getId()).isEqualTo(front.getId()));
        // 상태 변경은 status_history에 실행한 소유자로 남는다(AC6)
        assertThat(histories.findByIssueIdOrderByChangedAtAscIdAsc(a.getId()).getLast().getChangedBy().getId()).isEqualTo(owner.getId());

        mvc.perform(get("/api/v1/projects/" + project.getId() + "/re-qa").with(oauth2Login().attributes(x -> x.put("email", front.getEmail()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.failed_count").value(1))
            .andExpect(jsonPath("$.data.created_count").value(1));
    }

    @Test
    void rq002_fixed인_첫_QA_이슈가_없으면_409_사람이_등록한_fixed는_대상이_아니다() throws Exception {
        analyzed();
        QAIssue manual = issues.save(new QAIssue(project, "d", "홈", List.of("홈 진입"), PlatformScope.COMMON, null, List.of(), null, null,
            MemberRole.FRONTEND, owner));
        manual.changeStatus(IssueStatus.FIXED);
        issues.flush();
        long before = runCount();
        postAs("/re-qa", owner, null)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("NOT_READY"));
        assertThat(runCount()).isEqualTo(before);
    }

    @Test
    void rq003_소유자가_아니면_403() throws Exception {
        analyzed();
        fixedFirstQaIssue("12:34", StyleProperty.COLOR, "#1A73E8", "#2B7DE9");
        postAs("/re-qa", front, null).andExpect(status().isForbidden());
        verify(comparator, never()).recheck(any());
    }

    @Test
    void rq004_다시_읽지_못한_이슈는_판정하지_않고_fixed로_둔다() throws Exception {
        analyzed();
        QAIssue c = fixedFirstQaIssue("12:34", StyleProperty.COLOR, "#1A73E8", "#2B7DE9");
        when(comparator.recheck(any())).thenReturn(new QaComparator.ReQaResult(SHA, Map.of()));
        postAs("/re-qa", owner, null).andExpect(status().isAccepted());
        Long runId = latestRun(RunKind.RE_QA);
        reQaJob.run(runId);
        ProjectRun run = reload(runId);
        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.getCreatedCount()).isZero();
        assertThat(run.getFailedCount()).isZero();
        assertThat(run.getDroppedCount()).isEqualTo(1);
        assertThat(issues.findById(c.getId()).orElseThrow().getStatus()).isEqualTo(IssueStatus.FIXED);
        assertThat(verifications.findByIssueIdOrderByVerifiedAtDescIdDesc(c.getId())).isEmpty();
        assertThat(notifications.count()).isZero();
    }

    @Test
    void Re_QA가_실패하면_어떤_이슈도_바꾸지_않는다() throws Exception {
        analyzed();
        QAIssue c = fixedFirstQaIssue("12:34", StyleProperty.COLOR, "#1A73E8", "#2B7DE9");
        when(comparator.recheck(any())).thenThrow(new QaFailedException("QA 비교용 LLM API 키(ANTHROPIC_API_KEY)가 설정되지 않았어요."));
        postAs("/re-qa", owner, null).andExpect(status().isAccepted());
        Long runId = latestRun(RunKind.RE_QA);
        reQaJob.run(runId);
        assertThat(reload(runId).getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(issues.findById(c.getId()).orElseThrow().getStatus()).isEqualTo(IssueStatus.FIXED);
        assertThat(verifications.findByIssueIdOrderByVerifiedAtDescIdDesc(c.getId())).isEmpty();
    }

    @Test
    void Re_QA가_진행_중이면_첫_QA도_Re_QA도_409() throws Exception {
        analyzed();
        fixedFirstQaIssue("12:34", StyleProperty.COLOR, "#1A73E8", "#2B7DE9");
        postAs("/re-qa", owner, null).andExpect(status().isAccepted());
        postAs("/re-qa", owner, null).andExpect(status().isConflict());
    }
}
