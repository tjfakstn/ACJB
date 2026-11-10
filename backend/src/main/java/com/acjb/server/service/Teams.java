package com.acjb.server.service;

import java.util.Locale;

import com.acjb.server.common.ApiException;
import com.acjb.server.domain.MemberRole;

/** assigned_team 값 — 프로젝트 역할과 같은 소문자 값(planner·designer·frontend·backend, 2026-11-08) */
final class Teams {

    private Teams() {
    }

    /** 정해진 4개 값이 아니면 400(AC13). null·빈 값도 400 — 담당 팀은 지정·변경만 가능하다 */
    static MemberRole parse(String raw) {
        if (raw != null) {
            for (MemberRole r : MemberRole.values()) {
                if (r.name().toLowerCase(Locale.ROOT).equals(raw)) {
                    return r;
                }
            }
        }
        throw ApiException.badRequest("assigned_team은 planner·designer·frontend·backend 중 하나여야 합니다.");
    }
}
