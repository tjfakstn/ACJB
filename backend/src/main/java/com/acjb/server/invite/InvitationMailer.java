package com.acjb.server.invite;

/** 초대 메일 발송. 실패해도 초대는 남는다 — 초대받은 사람은 앱 안 초대함에서 수락할 수 있다(AC17) */
public interface InvitationMailer {

    /** @return 실제로 보냈으면 true, 설정이 없거나 실패하면 false(예외를 던지지 않는다) */
    boolean send(InvitationMail mail);
}
