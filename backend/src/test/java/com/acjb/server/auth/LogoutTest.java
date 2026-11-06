package com.acjb.server.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/** GET /auth/logout — 세션을 끝내고 프론트의 로그인 화면으로 보낸다(백엔드 '/'로 가면 404였다) */
@SpringBootTest
@AutoConfigureMockMvc
class LogoutTest {

    @Autowired MockMvc mvc;

    @Test
    void 로그아웃하면_세션이_끝나고_프론트_주소로_간다() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mvc.perform(get("/api/v1/auth/logout").session(session)
                .with(oauth2Login().attributes(a -> a.put("email", "logout@test.dev"))))
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("http://localhost:5173/"));

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void 로그인하지_않아도_같은_곳으로_보낸다() throws Exception {
        mvc.perform(get("/api/v1/auth/logout"))
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("http://localhost:5173/"));
    }
}
