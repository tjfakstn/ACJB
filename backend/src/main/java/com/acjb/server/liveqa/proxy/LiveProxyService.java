package com.acjb.server.liveqa.proxy;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.acjb.server.liveqa.DeployTargetService;
import com.acjb.server.scan.TargetUrlPolicy;

/**
 * 연결 화면(프록시) 입장권 발급(2026-11-20, AC29) — 프로젝트 멤버가 실시간 QA 화면을 열 때 받는다.
 * 프록시 주소가 설정되지 않았거나(로컬·배포 설정), 배포 주소가 없거나 주소 제한에 걸리면 enabled false — 화면은 원래 화면(스니펫·캡처)으로 보여 준다
 */
@Service
public class LiveProxyService {

    /** enter_url 뒤에 &path=/경로를 붙여 iframe으로 연다 */
    @tools.jackson.databind.annotation.JsonNaming(tools.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record View(boolean enabled, String origin, String enterUrl) {
    }

    private final String proxyOrigin;
    private final DeployTargetService deployTargets;
    private final ProxyTokens tokens;
    private final TargetUrlPolicy policy;

    public LiveProxyService(@Value("${app.proxy.origin:}") String proxyOrigin, DeployTargetService deployTargets, ProxyTokens tokens,
                            TargetUrlPolicy policy) {
        this.proxyOrigin = proxyOrigin == null ? "" : proxyOrigin.trim().replaceAll("/+$", "");
        this.deployTargets = deployTargets;
        this.tokens = tokens;
        this.policy = policy;
    }

    public View issue(Long projectId) {
        if (proxyOrigin.isEmpty()) {
            return new View(false, null, null);
        }
        String url = deployTargets.resolve(projectId).url();
        if (url == null || !policy.allows(url)) {
            return new View(false, proxyOrigin, null);
        }
        URI u = URI.create(url);
        String targetOrigin = u.getScheme().toLowerCase() + "://" + u.getRawAuthority();
        String token = tokens.issue(projectId, targetOrigin);
        return new View(true, proxyOrigin, proxyOrigin + "/__qating/enter?t=" + token);
    }
}
