package com.acjb.server.golden;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.acjb.server.domain.DeployStage;
import com.acjb.server.domain.EnvPlatform;
import com.acjb.server.domain.Environment;
import com.acjb.server.domain.IssueSource;
import com.acjb.server.domain.IssueStatus;
import com.acjb.server.domain.Member;
import com.acjb.server.domain.MemberRole;
import com.acjb.server.domain.Notification;
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

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import tools.jackson.databind.json.JsonMapper;

/**
 * 골든 테스트 하니스 — tests/harness/golden_cases.yaml에서 {@code harness: api}인 케이스를 읽어 실제 API를 호출하고 판정한다.
 * 대상: QA 이슈 등록·담당 팀 알림·상태 전이·재검증·권한(AGENTS.md 절대 규칙 1~7, AC1~AC7·AC11~AC13)과 공개 엔드포인트(AC9·AC10).
 *
 * <ul>
 *   <li>요청(엔드포인트·본문)과 기대값은 모두 케이스 파일에서 읽는다 — 케이스를 바꾸면 이 테스트의 판정도 바뀐다.</li>
 *   <li>사전 조건은 케이스의 {@code given}(기계가 읽는 형태)으로 만든다. 기본 세계: member-1 소유자(planner), member-2 frontend,
 *       member-3 designer, member-9 다른 프로젝트의 멤버. {@code given.members}로 역할을 바꾸거나 더하고, {@code given.issue}로 이슈를,
 *       {@code given.removed}로 이슈를 만든 뒤 제거된 멤버를, {@code given.as}로 요청자를 정한다.</li>
 *   <li>이 하니스가 처리하지 못하는 판정 키가 있으면 그 케이스는 통과가 아니라 실패다 — 판정을 건너뛰지 않는다.</li>
 *   <li>케이스마다 트랜잭션을 열고 끝나면 되돌린다(다른 케이스와 데이터가 섞이지 않게).</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class GoldenApiHarnessTest {

    static final String HARNESS = "api";
    private static final String CSRF = "golden-csrf-token";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 응답·사후 조회로 판정하는 키. 여기 없는 키가 케이스에 있으면 실패로 본다 */
    private static final Set<String> TOP_KEYS = Set.of("status", "body", "body_has", "body_not_has", "error_code",
        "error_message_contains", "status_history_last_entry_has", "repro_steps_match_original", "followup");
    private static final Set<String> FOLLOWUP_KEYS = Set.of("description", "expected_notification_count_per_team_member",
        "expected_notification_event", "expected_notification_count_for_reporter", "expected_notification_count_total",
        "expected_notification_count_by_event", "expected_notifications", "expected_notification_count_total_delta",
        "expected_notification_count_delta", "expected_issue_status", "status_history_length_unchanged",
        "expected_status_history_count_delta", "expected_verification_count_delta");

    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired EntityManager em;
    @Autowired ProjectRepository projects;
    @Autowired MemberRepository members;
    @Autowired ProjectMemberRepository projectMembers;
    @Autowired QAIssueRepository issues;
    @Autowired StatusHistoryRepository histories;
    @Autowired VerificationRepository verifications;
    @Autowired NotificationRepository notifications;

    @TestFactory
    Stream<DynamicTest> 골든_케이스() {
        List<Map<String, Object>> cases = GoldenCases.load().stream()
            .filter(c -> HARNESS.equals(c.get("harness")))
            .toList();
        assertThat(cases).as("harness: api인 케이스가 하나도 없다").isNotEmpty();
        return cases.stream().map(c -> DynamicTest.dynamicTest(
            c.get("id") + " [" + c.get("ac") + "] " + c.get("description"), () -> runRolledBack(c)));
    }

    private void runRolledBack(Map<String, Object> c) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            status.setRollbackOnly();
            try {
                run(c);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    // ---------------------------------------------------------------- 실행

    private void run(Map<String, Object> c) throws Exception {
        String id = (String) c.get("id");
        Map<String, Object> input = map(c.get("input"));
        Map<String, Object> expected = map(c.get("expected"));
        Map<String, Object> given = map(c.get("given"));
        for (String key : expected.keySet()) {
            if (!TOP_KEYS.contains(key)) {
                fail(id + ": 하니스가 처리할 수 없는 판정 키 '" + key + "' — 판정을 건너뛰지 않고 실패로 본다");
            }
        }

        World w = world(id, given);
        Snapshot before = snapshot(w.issue);

        String[] ep = ((String) input.get("endpoint")).split(" ", 2);
        String path = ep[1].replace("{projectId}", String.valueOf(w.project.getId()));
        if (w.issue != null) {
            path = path.replace("{issueId}", String.valueOf(w.issue.getId()));
        }
        assertThat(path).as(id + ": 채우지 못한 경로 변수").doesNotContain("{");
        if (!path.startsWith("/api/")) {
            path = "/api/v1" + path;
        }
        MockHttpServletRequestBuilder rb = request(HttpMethod.valueOf(ep[0]), path)
            .cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF);
        String as = (String) given.getOrDefault("as", "member-1");
        if (!"anonymous".equals(as)) {
            Member actor = w.member(as);
            rb.with(oauth2Login().attributes(a -> a.put("email", actor.getEmail())));
        }
        Object body = input.get("body");
        if (body != null) {
            rb.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
        }

        MvcResult result = mvc.perform(rb).andReturn();
        int status = result.getResponse().getStatus();
        String raw = result.getResponse().getContentAsString();
        Object res = raw.isBlank() ? null : JSON.readValue(raw, Object.class);
        String ctx = id + " (" + ep[0] + " " + path + " → " + status + " " + abbreviate(raw) + ")";

        if (expected.containsKey("status")) {
            assertThat(status).as(ctx + ": status").isEqualTo(((Number) expected.get("status")).intValue());
        }
        if (expected.containsKey("body")) {
            assertSubset(ctx, "", expected.get("body"), res);
        }
        for (Object p : list(expected.get("body_has"))) {
            assertThat(has(res, (String) p) || has(res, "data." + p)).as(ctx + ": 응답에 " + p + "가 있어야 한다").isTrue();
        }
        for (Object p : list(expected.get("body_not_has"))) {
            assertThat(has(res, (String) p)).as(ctx + ": 응답에 " + p + "가 없어야 한다").isFalse();
        }
        if (expected.containsKey("error_code")) {
            assertThat(String.valueOf(at(res, "error.code"))).as(ctx + ": error.code").isEqualTo(expected.get("error_code"));
        }
        if (expected.containsKey("error_message_contains")) {
            assertThat(String.valueOf(at(res, "error.message"))).as(ctx + ": error.message")
                .contains((String) expected.get("error_message_contains"));
        }
        if (expected.containsKey("status_history_last_entry_has")) {
            List<Object> history = list(at(res, "data.status_history"));
            assertThat(history).as(ctx + ": status_history").isNotEmpty();
            Map<String, Object> last = map(history.get(history.size() - 1));
            for (Object k : list(expected.get("status_history_last_entry_has"))) {
                assertThat(last.get(k)).as(ctx + ": status_history 마지막 항목의 " + k).isNotNull();
            }
        }
        if (Boolean.TRUE.equals(expected.get("repro_steps_match_original"))) {
            assertThat(list(at(res, "data.repro_steps"))).as(ctx + ": 등록 때의 repro_steps 그대로").isEqualTo(w.originalReproSteps);
        }

        Map<String, Object> followup = map(expected.get("followup"));
        if (!followup.isEmpty()) {
            em.flush();
            em.clear();
            QAIssue target = w.issue;
            if (target == null && at(res, "data.id") != null) {
                target = issues.findById(Long.valueOf(String.valueOf(at(res, "data.id")))).orElse(null);
            }
            followup(ctx, followup, w, target, map(body), before);
        }
    }

    private void followup(String ctx, Map<String, Object> f, World w, QAIssue issue, Map<String, Object> body, Snapshot before) {
        for (String key : f.keySet()) {
            if (!FOLLOWUP_KEYS.contains(key)) {
                fail(ctx + ": 하니스가 처리할 수 없는 followup 키 '" + key + "' — 판정을 건너뛰지 않고 실패로 본다");
            }
        }
        Snapshot after = snapshot(issue);
        String event = (String) f.get("expected_notification_event");

        if (f.containsKey("expected_notification_count_per_team_member")) {
            int n = ((Number) f.get("expected_notification_count_per_team_member")).intValue();
            assertThat(issue).as(ctx + ": 알림을 셀 이슈").isNotNull();
            String teamName = body.get("assigned_team") != null ? (String) body.get("assigned_team")
                : issue.getAssignedTeam() == null ? null : issue.getAssignedTeam().name();
            assertThat(teamName).as(ctx + ": 담당 팀").isNotNull();
            List<ProjectMember> team = projectMembers.findTeam(w.project.getId(), MemberRole.valueOf(teamName.toUpperCase(Locale.ROOT)));
            assertThat(team).as(ctx + ": 담당 팀 역할 멤버").isNotEmpty();
            for (ProjectMember pm : team) {
                assertThat(count(issue, pm.getMember(), event)).as(ctx + ": " + pm.getMember().getName() + "에게 온 " + event).isEqualTo(n);
            }
        }
        if (f.containsKey("expected_notification_count_for_reporter")) {
            int n = ((Number) f.get("expected_notification_count_for_reporter")).intValue();
            assertThat(count(issue, issue.getReportedBy(), event)).as(ctx + ": 등록자에게 온 " + event).isEqualTo(n);
        }
        if (f.containsKey("expected_notification_count_total")) {
            assertThat(after.issueNotifications).as(ctx + ": 이 이슈의 알림 수")
                .isEqualTo(((Number) f.get("expected_notification_count_total")).longValue());
        }
        for (Map.Entry<String, Object> e : map(f.get("expected_notification_count_by_event")).entrySet()) {
            long now = count(issue, null, e.getKey());
            long was = before.byEvent.getOrDefault(e.getKey(), 0L);
            assertThat(now - was).as(ctx + ": 이 요청으로 생긴 " + e.getKey() + " 알림").isEqualTo(((Number) e.getValue()).longValue());
        }
        for (Object o : list(f.get("expected_notifications"))) {
            Map<String, Object> exp = map(o);
            String to = (String) exp.get("to");
            Member recipient = "reported_by".equals(to) ? issue.getReportedBy() : w.member(to);
            assertThat(count(issue, recipient, (String) exp.get("event"))).as(ctx + ": " + to + "에게 온 " + exp.get("event"))
                .isEqualTo(((Number) exp.get("count")).longValue());
        }
        for (String k : List.of("expected_notification_count_total_delta", "expected_notification_count_delta")) {
            if (f.containsKey(k)) {
                assertThat(after.allNotifications - before.allNotifications).as(ctx + ": 이 요청으로 생긴 알림 수")
                    .isEqualTo(((Number) f.get(k)).longValue());
            }
        }
        if (f.containsKey("expected_issue_status")) {
            assertThat(issue.getStatus().name().toLowerCase(Locale.ROOT)).as(ctx + ": 이슈 상태").isEqualTo(f.get("expected_issue_status"));
        }
        if (Boolean.TRUE.equals(f.get("status_history_length_unchanged"))) {
            assertThat(after.history).as(ctx + ": status_history 길이").isEqualTo(before.history);
        }
        if (f.containsKey("expected_status_history_count_delta")) {
            assertThat(after.history - before.history).as(ctx + ": status_history 증가")
                .isEqualTo(((Number) f.get("expected_status_history_count_delta")).longValue());
        }
        if (f.containsKey("expected_verification_count_delta")) {
            assertThat(after.verifications - before.verifications).as(ctx + ": Verification 증가")
                .isEqualTo(((Number) f.get("expected_verification_count_delta")).longValue());
        }
    }

    // ---------------------------------------------------------------- 사전 조건(given)

    private static final class World {
        Project project;
        final Map<String, Member> members = new LinkedHashMap<>();
        QAIssue issue;
        List<String> originalReproSteps;

        Member member(String key) {
            Member m = members.get(key);
            if (m == null) {
                throw new IllegalStateException("given에 없는 멤버: " + key);
            }
            return m;
        }
    }

    private World world(String caseId, Map<String, Object> given) {
        World w = new World();
        Map<String, String> roles = new LinkedHashMap<>();
        roles.put("member-1", "planner");
        roles.put("member-2", "frontend");
        roles.put("member-3", "designer");
        map(given.get("members")).forEach((k, v) -> roles.put(k, (String) v));

        for (String key : roles.keySet()) {
            w.members.put(key, members.save(new Member(key + "." + caseId + "@golden.test", key)));
        }
        w.project = projects.save(new Project("golden-" + caseId, w.member("member-1")));
        roles.forEach((key, role) -> projectMembers.save(
            new ProjectMember(w.project, w.member(key), MemberRole.valueOf(role.toUpperCase(Locale.ROOT)))));

        Member outsider = members.save(new Member("member-9." + caseId + "@golden.test", "member-9"));
        Project elsewhere = projects.save(new Project("golden-other-" + caseId, outsider));
        projectMembers.save(new ProjectMember(elsewhere, outsider, MemberRole.PLANNER));
        w.members.put("member-9", outsider);

        Map<String, Object> issue = map(given.get("issue"));
        if (!issue.isEmpty()) {
            w.issue = issue(w, issue);
        }
        for (Object removed : list(given.get("removed"))) {
            projectMembers.findByProjectIdAndMemberId(w.project.getId(), w.member((String) removed).getId())
                .ifPresent(projectMembers::delete);
        }
        projectMembers.flush();
        return w;
    }

    /** 등록 API를 거친 것과 같은 이슈(needs_check + 이력 1건)를 만들고, 상태가 다르면 그 상태로 맞춘다 */
    private QAIssue issue(World w, Map<String, Object> spec) {
        Member reporter = w.member((String) spec.getOrDefault("reported_by", "member-1"));
        String team = (String) spec.get("assigned_team");
        List<String> repro = List.of("홈 진입", "상단 알림 아이콘 확인");
        QAIssue issue = issues.save(new QAIssue(w.project, "아이콘이 너무 커요", "홈 화면 상단 알림 아이콘", repro, PlatformScope.AOS,
            new Environment(EnvPlatform.AOS, "Pixel 8", null, DeployStage.STAGING), List.of(), null, null,
            team == null ? null : MemberRole.valueOf(team.toUpperCase(Locale.ROOT)), reporter));
        if ("first_qa".equals(spec.get("source"))) {
            ReflectionTestUtils.setField(issue, "source", IssueSource.FIRST_QA);
        }
        histories.save(new StatusHistory(issue, IssueStatus.NEEDS_CHECK, reporter));
        IssueStatus status = IssueStatus.valueOf(((String) spec.getOrDefault("status", "needs_check")).toUpperCase(Locale.ROOT));
        if (status != IssueStatus.NEEDS_CHECK) {
            issue.changeStatus(status);
            histories.save(new StatusHistory(issue, status, reporter));
        }
        issues.flush();
        w.originalReproSteps = repro;
        return issue;
    }

    // ---------------------------------------------------------------- 사후 조회

    private record Snapshot(long allNotifications, long issueNotifications, Map<String, Long> byEvent, long history, long verifications) {
    }

    private Snapshot snapshot(QAIssue issue) {
        List<Notification> all = notifications.findAll();
        if (issue == null) {
            return new Snapshot(all.size(), 0, Map.of(), 0, 0);
        }
        Map<String, Long> byEvent = new LinkedHashMap<>();
        long mine = 0;
        for (Notification n : all) {
            if (n.getIssue() != null && n.getIssue().getId().equals(issue.getId())) {
                mine++;
                byEvent.merge(n.getEvent().name().toLowerCase(Locale.ROOT), 1L, Long::sum);
            }
        }
        return new Snapshot(all.size(), mine,
            byEvent,
            histories.findByIssueIdOrderByChangedAtAscIdAsc(issue.getId()).size(),
            verifications.findByIssueIdOrderByVerifiedAtDescIdDesc(issue.getId()).size());
    }

    /** 이 이슈의 알림 중 받는 사람(null이면 모두)과 event(null이면 모두)가 맞는 것의 수 */
    private long count(QAIssue issue, Member recipient, String event) {
        NotificationEvent ev = event == null ? null : NotificationEvent.valueOf(event.toUpperCase(Locale.ROOT));
        return notifications.findAll().stream()
            .filter(n -> n.getIssue() != null && n.getIssue().getId().equals(issue.getId()))
            .filter(n -> recipient == null || n.getRecipient().getId().equals(recipient.getId()))
            .filter(n -> ev == null || n.getEvent() == ev)
            .count();
    }

    // ---------------------------------------------------------------- JSON 판정 도구

    /** expected의 키·값이 모두 actual에 있어야 한다(적히지 않은 키는 보지 않는다 — 케이스 파일 머리말의 판정 규칙) */
    private static void assertSubset(String ctx, String path, Object expected, Object actual) {
        if (expected instanceof Map<?, ?> em) {
            assertThat(actual).as(ctx + ": " + path + "는 객체여야 한다").isInstanceOf(Map.class);
            Map<?, ?> am = (Map<?, ?>) actual;
            for (Map.Entry<?, ?> e : em.entrySet()) {
                String p = path.isEmpty() ? String.valueOf(e.getKey()) : path + "." + e.getKey();
                if (e.getValue() != null) {
                    assertThat(am.containsKey(e.getKey())).as(ctx + ": 응답에 " + p + "가 있어야 한다").isTrue();
                }
                assertSubset(ctx, p, e.getValue(), am.get(e.getKey()));
            }
        } else if (expected instanceof List<?> el) {
            assertThat(actual).as(ctx + ": " + path + "는 배열이어야 한다").isInstanceOf(List.class);
            List<?> al = (List<?>) actual;
            assertThat(al).as(ctx + ": " + path + " 길이").hasSize(el.size());
            for (int i = 0; i < el.size(); i++) {
                assertSubset(ctx, path + "[" + i + "]", el.get(i), al.get(i));
            }
        } else if (expected instanceof Number en && actual instanceof Number an) {
            assertThat(an.doubleValue()).as(ctx + ": " + path).isEqualTo(en.doubleValue());
        } else {
            assertThat(actual == null ? null : String.valueOf(actual)).as(ctx + ": " + path)
                .isEqualTo(expected == null ? null : String.valueOf(expected));
        }
    }

    /** a.b.c 경로의 값. 배열을 만나면 모든 원소에서 찾고, 하나라도 있으면 있다 */
    private static boolean has(Object root, String path) {
        return !values(root, path.split("\\."), 0).isEmpty();
    }

    private static Object at(Object root, String path) {
        List<Object> found = values(root, path.split("\\."), 0);
        return found.isEmpty() ? null : found.get(0);
    }

    private static List<Object> values(Object node, String[] keys, int i) {
        List<Object> out = new ArrayList<>();
        if (i == keys.length) {
            if (node != null) {
                out.add(node);
            }
            return out;
        }
        if (node instanceof List<?> l) {
            for (Object o : l) {
                out.addAll(values(o, keys, i));
            }
            return out;
        }
        if (node instanceof Map<?, ?> m && m.containsKey(keys[i]) && m.get(keys[i]) != null) {
            out.addAll(values(m.get(keys[i]), keys, i + 1));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return o instanceof List<?> l ? (List<Object>) l : List.of();
    }

    private static String abbreviate(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
