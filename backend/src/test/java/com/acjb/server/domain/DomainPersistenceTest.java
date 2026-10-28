package com.acjb.server.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.acjb.server.repository.MemberRepository;
import com.acjb.server.repository.NotificationRepository;
import com.acjb.server.repository.ProjectMemberRepository;
import com.acjb.server.repository.ProjectRepository;
import com.acjb.server.repository.QAIssueRepository;
import com.acjb.server.repository.StatusHistoryRepository;
import com.acjb.server.repository.VerificationRepository;

/** 엔티티가 MySQL(qating_test)에 저장되고 같은 값으로 읽히는지만 확인한다. 규칙(AC1~AC13)은 서비스·골든 케이스에서 검증한다 */
@SpringBootTest
@Transactional
class DomainPersistenceTest {

    @Autowired ProjectRepository projects;
    @Autowired MemberRepository members;
    @Autowired ProjectMemberRepository projectMembers;
    @Autowired QAIssueRepository issues;
    @Autowired StatusHistoryRepository histories;
    @Autowired VerificationRepository verifications;
    @Autowired NotificationRepository notifications;

    @Test
    void 이슈와_연관_데이터를_저장하고_같은_값으로_읽는다() {
        Project project = projects.saveAndFlush(new Project("persist-test"));
        Member reporter = members.save(new Member("reporter@test.dev", "등록자", MemberRole.DESIGNER));
        Member assignee = members.save(new Member("assignee@test.dev", "담당자", MemberRole.DEVELOPER));
        projectMembers.save(new ProjectMember(project, reporter));

        QAIssue issue = issues.saveAndFlush(new QAIssue(
            project, "아이콘이 너무 커요", "홈 화면 상단 알림 아이콘", List.of("홈 진입", "아이콘 확인", "크기 비교"),
            PlatformScope.AOS, new Environment(EnvPlatform.AOS, "Pixel 8", null, DeployStage.STAGING),
            List.of("https://example.com/a.png"), null,
            new StyleMismatch(StyleProperty.RADIUS, "8px", "6px"), assignee, reporter));
        histories.save(new StatusHistory(issue, IssueStatus.NEEDS_CHECK, reporter));
        verifications.save(new Verification(issue, VerificationResult.FAIL, "해당 화면만", reporter));
        notifications.save(new Notification(issue, NotificationEvent.ISSUE_CREATED, assignee, "in_app"));

        QAIssue loaded = issues.findById(issue.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(IssueStatus.NEEDS_CHECK);
        assertThat(loaded.getReproSteps()).containsExactly("홈 진입", "아이콘 확인", "크기 비교");
        assertThat(loaded.getEnvironment().getDevice()).isEqualTo("Pixel 8");
        assertThat(loaded.getStyleMismatch().getExpectedValue()).isEqualTo("8px");
        assertThat(loaded.getReportedBy().getEmail()).isEqualTo("reporter@test.dev");
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(projectMembers.existsByProjectIdAndMemberEmail(project.getId(), "reporter@test.dev")).isTrue();
        assertThat(projectMembers.existsByProjectIdAndMemberEmail(project.getId(), "assignee@test.dev")).isFalse();
        assertThat(histories.findByIssueIdOrderByChangedAtAscIdAsc(issue.getId())).hasSize(1);
        assertThat(verifications.findByIssueIdOrderByVerifiedAtDescIdDesc(issue.getId())).hasSize(1);
        assertThat(notifications.countByIssueIdAndEvent(issue.getId(), NotificationEvent.ISSUE_CREATED)).isEqualTo(1);
    }

    @Test
    void 스타일_차이가_없는_이슈는_style_mismatch가_null이다() {
        Project project = projects.save(new Project("persist-test-2"));
        Member reporter = members.save(new Member("r2@test.dev", "등록자2", MemberRole.PLANNER));

        QAIssue issue = issues.saveAndFlush(new QAIssue(
            project, "문구 변경", "설정 화면", List.of("설정 진입"), PlatformScope.COMMON, null, null, null, null, null, reporter));

        QAIssue loaded = issues.findById(issue.getId()).orElseThrow();
        assertThat(loaded.getStyleMismatch()).isNull();
        assertThat(loaded.getAssignedTo()).isNull();
    }
}
