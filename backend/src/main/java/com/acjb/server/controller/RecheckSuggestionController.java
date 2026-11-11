package com.acjb.server.controller;

import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.acjb.server.auth.CurrentMember;
import com.acjb.server.common.ApiResponses;
import com.acjb.server.service.RecheckSuggestionService;

/** 재검증 범위 제안 (SPEC AC20). 프로젝트 멤버 여부는 Security 단계에서 확인했다(AC12). 본문은 받지 않는다 */
@RestController
public class RecheckSuggestionController {

    private final RecheckSuggestionService service;
    private final CurrentMember currentMember;

    public RecheckSuggestionController(RecheckSuggestionService service, CurrentMember currentMember) {
        this.service = service;
        this.currentMember = currentMember;
    }

    /** 사용량 한도(2026-11-11) */
    @org.springframework.beans.factory.annotation.Autowired
    private com.acjb.server.service.UsageLimits limits;

    @PostMapping("/api/v1/projects/{projectId}/qa-issues/{issueId}/recheck-suggestions")
    public Map<String, Object> suggest(@PathVariable Long projectId, @PathVariable String issueId,
                                       @AuthenticationPrincipal OAuth2User principal) {
        limits.hit(com.acjb.server.service.UsageLimits.Counter.SUGGESTION, projectId);
        return ApiResponses.ok(service.suggest(projectId, issueId, currentMember.require(principal)));
    }
}
