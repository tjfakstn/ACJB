package com.acjb.server.auth;

import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

import com.acjb.server.common.ApiException;
import com.acjb.server.common.ErrorCodes;
import com.acjb.server.domain.Member;
import com.acjb.server.repository.MemberRepository;

/**
 * 로그인한 구글 계정을 Member로 바꾼다. 처음 보는 이메일이면 이때 Member를 만든다(AC16 — 시드 데이터 폐기, 2026-11-04).
 * reported_by·verified_by·changed_by·owner·invited_by는 항상 이 값이다(N8 — 요청 body에서 받지 않는다).
 * 이메일은 소문자로 맞춘다 — 초대 이메일과 로그인 이메일 비교(AC18)도 같은 규칙이다.
 */
@Component
public class CurrentMember {

    private static final int NAME_MAX = 50;

    private final MemberRepository members;

    public CurrentMember(MemberRepository members) {
        this.members = members;
    }

    public static String emailOf(OAuth2User principal) {
        Object email = principal == null ? null : principal.getAttribute("email");
        return email instanceof String s && !s.isBlank() ? s.trim().toLowerCase(Locale.ROOT) : null;
    }

    public Member require(OAuth2User principal) {
        String email = emailOf(principal);
        if (email == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHORIZED, "로그인이 필요합니다.");
        }
        return members.findByEmail(email).orElseGet(() -> create(email, principal));
    }

    private Member create(String email, OAuth2User principal) {
        Object rawName = principal.getAttribute("name");
        String name = rawName instanceof String s && !s.isBlank() ? s.trim() : email.substring(0, email.indexOf('@') < 0 ? email.length() : email.indexOf('@'));
        if (name.length() > NAME_MAX) {
            name = name.substring(0, NAME_MAX);
        }
        try {
            return members.saveAndFlush(new Member(email, name));
        } catch (DataIntegrityViolationException e) {
            // 같은 사람의 첫 요청 두 개가 동시에 와서 다른 쪽이 먼저 만들었다 — 그 행을 쓴다
            return members.findByEmail(email).orElseThrow(() -> e);
        }
    }
}
