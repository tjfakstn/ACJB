package com.acjb.server.invite;

import java.time.ZoneId;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.format.DateTimeFormatter;

import org.springframework.web.util.HtmlUtils;

/**
 * 초대 메일 본문 — 카드형 HTML과, HTML을 못 보는 메일 앱용 텍스트를 함께 만든다.
 * 메일 앱은 외부 CSS·flex를 거의 지원하지 않아 표(table)와 인라인 스타일로만 그린다. 색은 앱 토큰(qa-*)과 같다.
 * 프로젝트 이름·설명·초대한 사람 이름은 사용자가 입력한 값이라 HTML에 넣을 때 반드시 이스케이프한다.
 */
public final class InvitationMailTemplate {

    private static final DateTimeFormatter EXPIRES = DateTimeFormatter.ofPattern("yyyy년 M월 d일 HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    private static final String FONT = "'Pretendard','Apple SD Gothic Neo','Malgun Gothic',sans-serif";
    private static final String TEXT = "#000000";
    private static final String TEXT_SUB = "#5f5f5f";
    private static final String SUB = "#9b9eaf";
    private static final String BORDER = "#e2e8f0";
    private static final String SURFACE = "#f9fafb";
    private static final String PRIMARY = "#0072dc";
    private static final String LOGO = "#965fff";

    private InvitationMailTemplate() {
    }

    public static String subject(InvitationMail mail) {
        return "[QAting] " + mail.inviterName() + " 님이 '" + mail.projectName() + "' 프로젝트에 초대했습니다";
    }

    public static String text(InvitationMail mail) {
        return """
            %s 님이 QAting의 '%s' 프로젝트에 초대했습니다.
            %s
            아래 링크를 열고, 이 메일을 받은 구글 계정(%s)으로 로그인한 뒤 수락하세요.
            %s

            이 초대는 %s(한국 시간)까지 유효합니다.
            프로젝트 역할은 수락할 때 직접 고릅니다.
            다른 구글 계정으로 로그인하면 수락할 수 없습니다.
            """.formatted(mail.inviterName(), mail.projectName(),
                mail.projectDescription() == null ? "" : "프로젝트 설명: " + mail.projectDescription() + "\n",
                mail.to(), mail.acceptUrl(), EXPIRES.format(mail.expiresAt()));
    }

    public static String html(InvitationMail mail) {
        String inviter = esc(mail.inviterName());
        String project = esc(mail.projectName());
        String to = esc(mail.to());
        String url = esc(mail.acceptUrl());
        String expires = esc(EXPIRES.format(mail.expiresAt()));
        String description = mail.projectDescription() == null || mail.projectDescription().isBlank() ? ""
            : "<p style=\"margin:8px 0 0;font-size:14px;line-height:20px;color:" + TEXT_SUB + ";\">" + esc(mail.projectDescription()) + "</p>";

        return fill("""
            <!doctype html>
            <html lang="ko">
            <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>QAting 프로젝트 초대</title></head>
            <body style="margin:0;padding:0;background:{SURFACE};">
            <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="background:{SURFACE};font-family:{FONT};">
              <tr><td align="center" style="padding:32px 16px;">
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="max-width:560px;background:#ffffff;border:1px solid {BORDER};border-radius:16px;">
                  <tr><td style="padding:28px 32px 20px;border-bottom:1px solid {BORDER};">
                    <span style="font-size:22px;font-weight:700;color:{LOGO};letter-spacing:-0.2px;">QAting</span>
                    <span style="font-size:12px;color:{SUB};padding-left:8px;">디자인 QA 협업</span>
                  </td></tr>
                  <tr><td style="height:4px;line-height:4px;font-size:0;background:{PRIMARY};">&nbsp;</td></tr>
                  <tr><td style="padding:32px 32px 8px;">
                    <p style="margin:0;font-size:12px;font-weight:600;color:{PRIMARY};">프로젝트 초대</p>
                    <h1 style="margin:8px 0 0;font-size:22px;line-height:30px;font-weight:700;color:{TEXT};">{INVITER} 님이 프로젝트에 초대했어요</h1>
                    <p style="margin:12px 0 0;font-size:14px;line-height:22px;color:{TEXT_SUB};">QAting에서 함께 디자인 QA를 진행해요. 아래 버튼을 눌러 이 메일을 받은 구글 계정으로 로그인한 뒤 초대를 수락하세요.</p>
                  </td></tr>
                  <tr><td style="padding:20px 32px 0;">
                    <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="background:{SURFACE};border:1px solid {BORDER};border-radius:12px;">
                      <tr><td style="padding:20px;">
                        <p style="margin:0;font-size:18px;line-height:26px;font-weight:700;color:{TEXT};">{PROJECT}</p>
                        {DESCRIPTION}
                      </td></tr>
                    </table>
                  </td></tr>
                  <tr><td style="padding:20px 32px 0;">
                    <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="font-size:14px;line-height:20px;">
                      <tr><td width="104" style="padding:6px 0;color:{TEXT_SUB};">초대받은 이메일</td><td style="padding:6px 0;color:{TEXT};font-weight:500;">{TO}</td></tr>
                      <tr><td width="104" style="padding:6px 0;color:{TEXT_SUB};">초대한 사람</td><td style="padding:6px 0;color:{TEXT};font-weight:500;">{INVITER}</td></tr>
                      <tr><td width="104" style="padding:6px 0;color:{TEXT_SUB};">유효기간</td><td style="padding:6px 0;color:{TEXT};font-weight:500;">{EXPIRES}까지 (한국 시간)</td></tr>
                    </table>
                  </td></tr>
                  <tr><td style="padding:28px 32px 0;">
                    <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0">
                      <tr><td align="center" style="border-radius:8px;background:{PRIMARY};">
                        <a href="{URL}" target="_blank" style="display:block;padding:14px 20px;font-size:16px;font-weight:600;color:#ffffff;text-decoration:none;border-radius:8px;">초대 확인하고 참여하기</a>
                      </td></tr>
                    </table>
                  </td></tr>
                  <tr><td style="padding:24px 32px 0;">
                    <p style="margin:0;font-size:12px;line-height:18px;color:{TEXT_SUB};">· 프로젝트 역할(기획·디자인·프론트엔드·백엔드)은 수락할 때 직접 고릅니다.</p>
                    <p style="margin:4px 0 0;font-size:12px;line-height:18px;color:{TEXT_SUB};">· 다른 구글 계정으로 로그인하면 수락할 수 없어요.</p>
                  </td></tr>
                  <tr><td style="padding:24px 32px 28px;">
                    <p style="margin:0;font-size:12px;line-height:18px;color:{SUB};">버튼이 열리지 않으면 아래 주소를 브라우저에 붙여 넣으세요.</p>
                    <p style="margin:4px 0 0;font-size:12px;line-height:18px;word-break:break-all;"><a href="{URL}" target="_blank" style="color:{PRIMARY};text-decoration:underline;">{URL}</a></p>
                  </td></tr>
                </table>
                <p style="margin:16px 0 0;font-size:11px;line-height:16px;color:{SUB};">이 메일은 QAting 프로젝트 초대를 위해 발송되었습니다. 초대를 원하지 않으면 무시하셔도 됩니다.</p>
              </td></tr>
            </table>
            </body>
            </html>
            """, Map.ofEntries(
            Map.entry("SURFACE", SURFACE), Map.entry("FONT", FONT), Map.entry("BORDER", BORDER), Map.entry("LOGO", LOGO),
            Map.entry("SUB", SUB), Map.entry("PRIMARY", PRIMARY), Map.entry("TEXT_SUB", TEXT_SUB), Map.entry("TEXT", TEXT),
            Map.entry("DESCRIPTION", description), Map.entry("EXPIRES", expires), Map.entry("TO", to),
            Map.entry("PROJECT", project), Map.entry("INVITER", inviter), Map.entry("URL", url)));
    }

    private static final Pattern SLOT = Pattern.compile("\\{([A-Z_]+)}");

    /** 자리표시자를 한 번에 채운다 — 사용자 값 안에 "{URL}" 같은 글자가 있어도 다시 치환되지 않는다 */
    private static String fill(String template, Map<String, String> values) {
        Matcher m = SLOT.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String v = values.get(m.group(1));
            if (v == null) {
                throw new IllegalStateException("초대 메일 템플릿 자리표시자 값 없음: " + m.group(1));
            }
            m.appendReplacement(out, Matcher.quoteReplacement(v));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String esc(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value, "UTF-8");
    }
}
