package com.acjb.server.dto;

import java.util.Locale;

import com.acjb.server.domain.ProjectMember;

/** openapi Member — id는 assigned_to·reported_by·verified_by에 쓰는 값과 같다. role은 이 프로젝트에서의 역할(null=미지정) */
public record MemberResponse(String id, String name, String email, String role) {

    public static MemberResponse of(ProjectMember pm) {
        return new MemberResponse(String.valueOf(pm.getMember().getId()), pm.getMember().getName(), pm.getMember().getEmail(),
            pm.getRole() == null ? null : pm.getRole().name().toLowerCase(Locale.ROOT));
    }
}
