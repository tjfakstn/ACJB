package com.acjb.server.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

import com.acjb.server.common.ApiException;
import com.acjb.server.common.ErrorCodes;
import com.acjb.server.domain.Member;
import com.acjb.server.repository.MemberRepository;

/**
 * 로그인한 구글 계정을 Member로 바꾼다. reported_by·verified_by·changed_by는 항상 이 값이다(N8 — 요청 body에서 받지 않는다).
 * /projects/** 는 AC12에서 이미 멤버만 통과시키므로, 여기서 못 찾는 경우는 그 사이 멤버가 지워진 때뿐이다.
 */
@Component
public class CurrentMember {

    private final MemberRepository members;

    public CurrentMember(MemberRepository members) {
        this.members = members;
    }

    public Member require(OAuth2User principal) {
        String email = principal == null ? null : principal.getAttribute("email");
        if (email == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHORIZED, "로그인이 필요합니다.");
        }
        return members.findByEmail(email)
            .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.FORBIDDEN, "등록된 멤버가 아닙니다."));
    }
}
