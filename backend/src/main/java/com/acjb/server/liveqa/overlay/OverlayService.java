package com.acjb.server.liveqa.overlay;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.acjb.server.common.ApiException;
import com.acjb.server.common.ErrorCodes;
import com.acjb.server.domain.Member;
import com.acjb.server.dto.QaIssueResponse;
import com.acjb.server.liveqa.DeployTargetService;
import com.acjb.server.liveqa.DeployTargetView;
import com.acjb.server.liveqa.ScreenLinkService;
import com.acjb.server.repository.MemberRepository;
import com.acjb.server.repository.ProjectRepository;
import com.acjb.server.service.QaIssueService;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 사이트 위에 얹는 QAting 툴바(오버레이)의 입장권 발급과 조회(2026-11-24, docs/design-live-overlay.md).
 * 등록·캡처는 기존 ManualQaService·CaptureService를 그대로 쓴다 — 필수 항목(AC1)·알림(AC3)·한도 규칙이 같게.
 */
@Service
public class OverlayService {

    private final OverlayTokens tokens;
    private final DeployTargetService deployTargets;
    private final ScreenLinkService screenLinks;
    private final QaIssueService issues;
    private final ProjectRepository projects;
    private final MemberRepository members;

    public OverlayService(OverlayTokens tokens, DeployTargetService deployTargets, ScreenLinkService screenLinks, QaIssueService issues,
                          ProjectRepository projects, MemberRepository members) {
        this.tokens = tokens;
        this.deployTargets = deployTargets;
        this.screenLinks = screenLinks;
        this.issues = issues;
        this.projects = projects;
        this.members = members;
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SessionView(String token, Instant expiresAt, String targetOrigin, String openUrl) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ContextView(String projectId, String projectName, String memberName, String targetOrigin,
                              ScreenLinkService.LinkList screenLinks) {
    }

    /** 요소에 붙일 핀 — 요소 안의 점 비율(rx·ry)로 넘겨 툴바가 지금 화면에서 다시 계산한다 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PinView(String issueId, int pinNumber, String title, String status, String selector, double rx, double ry) {
    }

    public record PinList(List<PinView> items) {
    }

    /** 로그인한 프로젝트 멤버(AC12 — SecurityConfig가 앞에서 검사)에게 배포 사이트용 입장권을 준다 */
    public SessionView openSession(Long projectId, String pagePath, Member member) {
        DeployTargetView target = deployTargets.get(projectId);
        if (target == null || target.url() == null) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.NOT_READY, "배포 주소를 먼저 넣어 주세요.");
        }
        URI base = URI.create(target.url());
        String origin = base.getScheme() + "://" + base.getRawAuthority();
        OverlayTokens.Ticket t = tokens.issue(projectId, member.getId(), origin);
        String path = pagePath == null || pagePath.isBlank() || !pagePath.startsWith("/") ? null : pagePath;
        String openUrl = path == null ? target.url() : origin + path;
        return new SessionView(tokens.encode(t), Instant.ofEpochSecond(t.expiresAt()), origin, openUrl);
    }

    @Transactional(readOnly = true)
    public ContextView context(OverlayTokens.Ticket t) {
        String projectName = projects.findById(t.projectId()).map(p -> p.getName()).orElse("");
        String memberName = members.findById(t.memberId()).map(Member::getName).orElse("");
        return new ContextView(String.valueOf(t.projectId()), projectName, memberName, t.targetOrigin(), screenLinks.list(t.projectId()));
    }

    /** 지금 경로의 요소 핀 — 정렬은 핀 번호 오름차순으로 고정(AGENTS.md 5장) */
    @Transactional(readOnly = true)
    public PinList pins(OverlayTokens.Ticket t, String path) {
        List<PinView> items = issues.list(t.projectId(), null, null, null).items().stream()
            .filter(i -> {
                QaIssueResponse.ManualQaView m = i.manualQa();
                return m != null && m.element() != null && m.anchor() != null && Objects.equals(m.pagePath(), path);
            })
            .map(i -> {
                QaIssueResponse.ManualQaView m = i.manualQa();
                QaIssueResponse.BoxView box = m.element().box();
                double rx = box.width() > 0 ? clamp((double) (m.anchor().xPx() - box.x()) / box.width()) : 0.5;
                double ry = box.height() > 0 ? clamp((double) (m.anchor().yPx() - box.y()) / box.height()) : 0.5;
                return new PinView(i.id(), m.pinNumber(), m.title(), i.status(), m.element().selector(), rx, ry);
            })
            .sorted((a, b) -> Integer.compare(a.pinNumber(), b.pinNumber()))
            .toList();
        return new PinList(items);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
