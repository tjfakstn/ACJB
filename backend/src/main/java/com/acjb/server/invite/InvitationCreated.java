package com.acjb.server.invite;

/** 초대가 커밋된 뒤 메일을 보내라는 신호. 원문 토큰을 담지만 메모리 안에서만 쓰고 저장·로그하지 않는다 */
public record InvitationCreated(InvitationMail mail) {
}
