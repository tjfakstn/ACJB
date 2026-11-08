package com.acjb.server.invite;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * 팀 공용 Gmail 계정(SMTP)으로 초대 메일을 보낸다(decision_log 2026-11-04). 계정·앱 비밀번호는 backend/.env의
 * MAIL_USERNAME·MAIL_PASSWORD. 설정이 없으면 보내지 않고 경고만 남긴다. 받는 주소와 수락 링크(토큰)는 로그에 남기지 않는다.
 */
@Component
public class SmtpInvitationMailer implements InvitationMailer {

    private static final Logger log = LoggerFactory.getLogger(SmtpInvitationMailer.class);

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
        try {
            // 카드형 HTML + HTML을 못 보는 메일 앱용 텍스트를 함께 보낸다(multipart/alternative)
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(mail.to());
            helper.setSubject(InvitationMailTemplate.subject(mail));
            helper.setText(InvitationMailTemplate.text(mail), InvitationMailTemplate.html(mail));
            mailSender.send(message);
            return true;
        } catch (MessagingException e) {
            log.warn("초대 메일 작성 실패: {}", e.getClass().getSimpleName());
            return false;
        } catch (MailException e) {
            log.warn("초대 메일 발송 실패: {}", e.getClass().getSimpleName());
            return false;
        }
    }
}
