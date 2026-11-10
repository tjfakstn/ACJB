package com.acjb.server.invite;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/** 카드형 초대 메일 본문 — 사용자가 입력한 값은 HTML로 해석되지 않고, 다른 자리표시자로 다시 바뀌지도 않는다 */
class InvitationMailTemplateTest {

    private static final InvitationMail MAIL = new InvitationMail("guest@example.com", "<b>쇼핑몰</b> & 앱",
        "설명 {URL} <script>alert(1)</script>", "김하늘", "https://qating.test/invitations/abc?x=1&y=2",
        Instant.parse("2026-11-15T03:00:00Z"));

    @Test
    void HTML_본문은_사용자_값을_이스케이프하고_자리표시자를_한_번만_채운다() {
        String html = InvitationMailTemplate.html(MAIL);

        assertThat(html).contains("&lt;b&gt;쇼핑몰&lt;/b&gt; &amp; 앱");
        assertThat(html).doesNotContain("<script>").contains("&lt;script&gt;");
        // 설명 안의 "{URL}"은 글자 그대로 남는다
        assertThat(html).contains("설명 {URL}");
        assertThat(html).contains("href=\"https://qating.test/invitations/abc?x=1&amp;y=2\"");
        assertThat(html).contains("guest@example.com").contains("김하늘 님이 프로젝트에 초대했어요")
            .contains("2026년 11월 15일 12:00까지");
        assertThat(html).doesNotContainPattern("\\{[A-Z_]+}(?<!\\{URL})");
    }

    @Test
    void 텍스트_본문에는_수락_링크와_만료_시각이_그대로_들어간다() {
        String text = InvitationMailTemplate.text(MAIL);

        assertThat(text).contains("https://qating.test/invitations/abc?x=1&y=2").contains("2026년 11월 15일 12:00")
            .contains("guest@example.com");
        assertThat(InvitationMailTemplate.subject(MAIL)).isEqualTo("[QAting] 김하늘 님이 '<b>쇼핑몰</b> & 앱' 프로젝트에 초대했습니다");
    }
}
