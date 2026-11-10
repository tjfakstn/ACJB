package com.acjb.server.invite;

import java.time.Instant;

/** 초대 메일 한 통에 필요한 값. acceptUrl에 원문 토큰이 들어 있으므로 로그에 남기지 않는다(N14) */
public record InvitationMail(String to, String projectName, String projectDescription, String inviterName, String acceptUrl,
                             Instant expiresAt) {
}
