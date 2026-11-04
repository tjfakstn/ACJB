package com.acjb.server.dto;

import java.util.Locale;

import com.acjb.server.domain.Member;

/** openapi Member — id는 assigned_to·reported_by·verified_by에 쓰는 값과 같다 */
public record MemberResponse(String id, String name, String email, String role) {

    public static MemberResponse of(Member m) {
        return new MemberResponse(String.valueOf(m.getId()), m.getName(), m.getEmail(),
            m.getRole().name().toLowerCase(Locale.ROOT));
    }
}
