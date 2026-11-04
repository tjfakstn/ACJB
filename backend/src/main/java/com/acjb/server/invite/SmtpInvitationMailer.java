package com.acjb.server.invite;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * 팀 공용 Gmail 계정(SMTP)으로 초대 메일을 보낸다(decision_log 2026-11-04). 계정·앱 비밀번호는 backend/.env의
 * MAIL_USERNAME·MAIL_PASSWORD. 설정이 없으면 보내지 않고 경고만 남긴다. 받는 주소와 수락 링크(토큰)는 로그에 남기지 않는다.
 */
@Component
public class SmtpInvitationMailer implements InvitationMailer {

    private static final Logger log = LoggerFactory.getLogger(SmtpInvitationMailer.class);
    private static final DateTimeFormatter EXPIRES = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    private final ObjectProvider<JavaMailSender> sender;
    private final String from;

    public SmtpInvitationMailer(ObjectProvider<JavaMailSender> sender, @Value("${app.invite.mail-from:}") String from) {
        this.sender = sender;
        this.from = from;
    }

    @Override
    public boolean send(InvitationMail mail) {
        JavaMailSender mailSender = sender.getIfAvailable();
        if (mailSender == null || from == null || from.isBlank()) {
            log.warn("초대 메일 발송 설정(MAIL_USERNAME·MAIL_PASSWORD)이 없어 메일을 보내지 않았습니다. 초대는 앱 안 초대함으로 수락할 수 있습니다.");
            return false;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(mail.to());
        message.setSubject("[QAting] " + mail.inviterName() + " 님이 '" + mail.projectName() + "' 프로젝트에 초대했습니다");
        message.setText("""
            %s 님이 QAting의 '%s' 프로젝트에 초대했습니다.

            아래 링크를 열고, 이 메일을 받은 구글 계정(%s)으로 로그인한 뒤 수락하세요.
            %s

            이 초대는 %s(한국 시간)까지 유효합니다.
            다른 구글 계정으로 로그인하면 수락할 수 없습니다.
            """.formatted(mail.inviterName(), mail.projectName(), mail.to(), mail.acceptUrl(), EXPIRES.format(mail.expiresAt())));
        try {
            mailSender.send(message);
            return true;
        } catch (MailException e) {
            log.warn("초대 메일 발송 실패: {}", e.getClass().getSimpleName());
            return false;
        }
    }
}
