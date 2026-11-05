package com.acjb.server.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

/** GET /auth/login/google — 구글로 보낼 때 계정 선택창을 띄우게 한다(로그아웃 뒤 다른 계정으로 바꿔 로그인할 수 있게) */
@SpringBootTest
@AutoConfigureMockMvc
class LoginRedirectTest {

    @Autowired MockMvc mvc;

    @Test
    void 구글_로그인_요청에_계정_선택_prompt가_붙는다() throws Exception {
        String location = mvc.perform(get("/api/v1/auth/login/google"))
            .andExpect(status().isFound())
            .andReturn().getResponse().getHeader("Location");

        UriComponents uri = UriComponentsBuilder.fromUriString(location).build();
        assertThat(uri.getHost()).isEqualTo("accounts.google.com");
        assertThat(uri.getQueryParams().getFirst("prompt")).isEqualTo("select_account");
    }
}
