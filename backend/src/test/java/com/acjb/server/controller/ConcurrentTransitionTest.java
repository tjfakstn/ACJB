package com.acjb.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.acjb.server.domain.IssueStatus;
import com.acjb.server.domain.Member;
import com.acjb.server.domain.MemberRole;
import com.acjb.server.domain.NotificationEvent;
import com.acjb.server.domain.PlatformScope;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.ProjectMember;
import com.acjb.server.domain.QAIssue;
import com.acjb.server.domain.StatusHistory;
import com.acjb.server.repository.MemberRepository;
import com.acjb.server.repository.NotificationRepository;
import com.acjb.server.repository.ProjectMemberRepository;
import com.acjb.server.repository.ProjectRepository;
import com.acjb.server.repository.QAIssueRepository;
import com.acjb.server.repository.StatusHistoryRepository;
import com.acjb.server.repository.VerificationRepository;

import jakarta.servlet.http.Cookie;

/**
 * 같은 이슈에 상태 변경 요청이 동시에 두 번 와도(버튼 두 번) 하나만 반영되고, 늦은 쪽은 500이 아니라 명세대로 409를 받는다.
 * 실제 커밋·잠금이 일어나야 하므로 트랜잭션 없이 돌고, 만든 행은 끝에 지운다. 경쟁이 매번 같지 않아 여러 번 반복한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConcurrentTransitionTest {

    private static final int ROUNDS = 10;

    @Autowired MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired MemberRepository members;
    @Autowired ProjectMemberRepository projectMembers;
    @Autowired QAIssueRepository issues;
    @Autowired StatusHistoryRepository histories;
    @Autowired NotificationRepository notifications;
    @Autowired VerificationRepository verifications;

    Project project;
    Member reporter;
    Member assignee;
    final List<QAIssue> created = new ArrayList<>();

    @BeforeEach
    void setUp() {
        project = projects.save(new Project("concurrent-" + System.nanoTime(), owner()));
        reporter = members.save(new Member("cc-rep-" + System.nanoTime() + "@test.dev", "등록자"));
        assignee = members.save(new Member("cc-asg-" + System.nanoTime() + "@test.dev", "담당자"));
        projectMembers.save(new ProjectMember(project, reporter, MemberRole.DESIGNER));
        projectMembers.save(new ProjectMember(project, assignee, MemberRole.DEVELOPER));
    }

    @AfterEach
    void cleanUp() {
        for (QAIssue issue : created) {
            notifications.findAll().stream().filter(n -> n.getIssue().getId().equals(issue.getId())).forEach(notifications::delete);
            verifications.deleteAll(verifications.findByIssueIdOrderByVerifiedAtDescIdDesc(issue.getId()));
            histories.deleteAll(histories.findByIssueIdOrderByChangedAtAscIdAsc(issue.getId()));
            issues.deleteById(issue.getId());
        }
        projectMembers.findAll().stream().filter(pm -> pm.getProject().getId().equals(project.getId())).forEach(projectMembers::delete);
        members.delete(reporter);
        members.delete(assignee);
        projects.delete(project);
    }

    private QAIssue newIssue() {
        QAIssue issue = issues.save(new QAIssue(project, "d", "홈", List.of("홈 진입"), PlatformScope.COMMON,
            null, List.of(), null, null, assignee, reporter));
        histories.save(new StatusHistory(issue, IssueStatus.NEEDS_CHECK, reporter));
        created.add(issue);
        return issue;
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder r, Member m, String body) {
        return r.with(oauth2Login().attributes(a -> a.put("email", m.getEmail())))
            .cookie(new Cookie("XSRF-TOKEN", "t")).header("X-XSRF-TOKEN", "t")
            .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** 두 요청을 같은 순간에 보내고 응답 둘을 돌려준다 */
    private List<MockHttpServletResponse> twice(MockHttpServletRequestBuilder a, MockHttpServletRequestBuilder b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
            for (MockHttpServletRequestBuilder r : List.of(a, b)) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return mvc.perform(r).andReturn().getResponse();
                }));
            }
            go.countDown();
            return List.of(futures.get(0).get(), futures.get(1).get());
        } finally {
            pool.shutdown();
        }
    }

    private static List<Integer> statuses(List<MockHttpServletResponse> responses) {
        return responses.stream().map(MockHttpServletResponse::getStatus).sorted().toList();
    }

    @Test
    void 같은_이슈에_fixed_PATCH가_동시에_두_번_오면_하나는_200_하나는_409이고_알림과_이력은_1건() throws Exception {
        for (int i = 0; i < ROUNDS; i++) {
            QAIssue issue = newIssue();
            String path = "/api/v1/projects/" + project.getId() + "/qa-issues/" + issue.getId();
            List<MockHttpServletResponse> res = twice(
                as(patch(path), assignee, "{\"status\":\"fixed\"}"), as(patch(path), assignee, "{\"status\":\"fixed\"}"));

            assertThat(statuses(res)).as("round %d", i).containsExactly(200, 409);
            assertThat(res).filteredOn(r -> r.getStatus() == 409).singleElement()
                .satisfies(r -> assertThat(r.getContentAsString()).contains("\"success\":false").contains("INVALID_TRANSITION"));
            assertThat(notifications.countByIssueIdAndEvent(issue.getId(), NotificationEvent.FIXED)).isEqualTo(1);
            assertThat(histories.findByIssueIdOrderByChangedAtAscIdAsc(issue.getId())).hasSize(2);
        }
    }

    @Test
    void 같은_이슈에_재검증이_동시에_두_번_오면_하나는_201_하나는_409이고_기록과_알림은_1건() throws Exception {
        for (int i = 0; i < ROUNDS; i++) {
            QAIssue issue = newIssue();
            issue.changeStatus(IssueStatus.FIXED);
            issues.save(issue);
            String path = "/api/v1/projects/" + project.getId() + "/qa-issues/" + issue.getId() + "/verifications";
            List<MockHttpServletResponse> res = twice(
                as(post(path), reporter, "{\"result\":\"fail\"}"), as(post(path), reporter, "{\"result\":\"fail\"}"));

            assertThat(statuses(res)).as("round %d", i).containsExactly(201, 409);
            assertThat(verifications.findByIssueIdOrderByVerifiedAtDescIdDesc(issue.getId())).hasSize(1);
            assertThat(notifications.countByIssueIdAndEvent(issue.getId(), NotificationEvent.RECHECK_FAILED)).isEqualTo(1);
        }
    }

    /** 프로젝트는 소유자가 있어야 만들 수 있다(AC16·N15) — 테스트마다 소유자용 멤버를 따로 만든다 */
    private Member owner() {
        return members.save(new Member("owner-" + java.util.UUID.randomUUID() + "@test.dev", "소유자"));
    }
}
