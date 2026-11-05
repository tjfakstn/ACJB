package com.acjb.server.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.acjb.server.domain.Member;
import com.acjb.server.repository.MemberRepository;

/** GET /auth/me — 로그인했으면 내 멤버 id를 준다(2026-11-05), 미인증은 200 {authenticated:false}(AC10) */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthMeTest {

    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;

    @Test
    void 이미_있는_멤버는_그_id와_DB의_이름_이메일을_준다() throws Exception {
        Member me = members.save(new Member("auth-me@test.dev", "DB이름"));

        mvc.perform(get("/api/v1/auth/me")
                .with(oauth2Login().attributes(a -> {
                    a.put("email", "Auth-Me@test.dev");
                    a.put("name", "구글이름");
                    a.put("picture", "https://example.com/p.png");
                })))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authenticated").value(true))
            .andExpect(jsonPath("$.id").value(String.valueOf(me.getId())))
            .andExpect(jsonPath("$.name").value("DB이름"))
            .andExpect(jsonPath("$.email").value("auth-me@test.dev"))
            .andExpect(jsonPath("$.picture").value("https://example.com/p.png"));
    }

    @Test
    void 처음_로그인한_계정은_이때_멤버가_만들어진다() throws Exception {
        assertThat(members.findByEmail("first-login@test.dev")).isEmpty();

        mvc.perform(get("/api/v1/auth/me")
                .with(oauth2Login().attributes(a -> {
                    a.put("email", "first-login@test.dev");
                    a.put("name", "처음");
                })))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authenticated").value(true))
            .andExpect(jsonPath("$.name").value("처음"))
            .andExpect(jsonPath("$.picture").doesNotExist());

        Member created = members.findByEmail("first-login@test.dev").orElseThrow();
        mvc.perform(get("/api/v1/auth/me")
                .with(oauth2Login().attributes(a -> a.put("email", "first-login@test.dev"))))
            .andExpect(jsonPath("$.id").value(String.valueOf(created.getId())));
        assertThat(members.findAll().stream().filter(m -> m.getEmail().equals("first-login@test.dev"))).hasSize(1);
    }

    @Test
    void 미인증은_200_authenticated_false() throws Exception {
        mvc.perform(get("/api/v1/auth/me"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authenticated").value(false))
            .andExpect(jsonPath("$.id").doesNotExist());
    }

    @Test
    void 이메일이_없는_계정은_로그인하지_않은_것으로_본다() throws Exception {
        mvc.perform(get("/api/v1/auth/me")
                .with(oauth2Login().attributes(a -> a.put("name", "이메일없음"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authenticated").value(false));
    }
}
