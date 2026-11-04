package com.acjb.server.invite;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 초대가 커밋된 뒤 별도 스레드에서 메일을 보낸다 — 메일 서버가 느려도 초대 요청 응답은 바로 나간다 */
@Component
public class InvitationMailListener {

    private final InvitationMailer mailer;

    public InvitationMailListener(InvitationMailer mailer) {
        this.mailer = mailer;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCreated(InvitationCreated event) {
        mailer.send(event.mail());
    }
}
