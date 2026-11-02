package com.gatekeeper.qaplatform.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gatekeeper.qaplatform.IntegrationTestBase;
import com.gatekeeper.qaplatform.auth.AppUser;
import com.gatekeeper.qaplatform.auth.AppUserRepository;
import com.gatekeeper.qaplatform.auth.JwtTokenService;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class RateLimitIntegrationTest extends IntegrationTestBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private RateLimitProperties limits;
    @Autowired private AppUserRepository users;
    @Autowired private JwtTokenService tokens;

    @Test
    void loginIsLimitedPerAddress() throws Exception {
        String address = "198.51.100.1";
        for (int i = 0; i < limits.loginPerMinute(); i++) {
            mockMvc.perform(loginFrom(address)).andExpect(status().is3xxRedirection());
        }

        mockMvc.perform(loginFrom(address))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.status").value(429));

        mockMvc.perform(loginFrom("198.51.100.2")).andExpect(status().is3xxRedirection());
    }

    @Test
    void runStartIsLimitedPerUserNotPerAddress() throws Exception {
        Cookie firstUser = tokenCookie(saveUser());
        for (int i = 0; i < limits.runStartPerMinute(); i++) {
            int status = mockMvc.perform(runStart(firstUser)).andReturn().getResponse().getStatus();
            assertThat(status).isNotEqualTo(429);
        }

        mockMvc.perform(runStart(firstUser)).andExpect(status().isTooManyRequests());

        int otherUserStatus = mockMvc.perform(runStart(tokenCookie(saveUser()))).andReturn().getResponse().getStatus();
        assertThat(otherUserStatus).isNotEqualTo(429);
    }

    @Test
    void otherEndpointsAreNotLimited() throws Exception {
        for (int i = 0; i < limits.loginPerMinute() + 1; i++) {
            mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        }
    }

    private static MockHttpServletRequestBuilder loginFrom(String address) {
        return get("/oauth2/authorization/oidc").with(request -> {
            request.setRemoteAddr(address);
            return request;
        });
    }

    private static MockHttpServletRequestBuilder runStart(Cookie auth) {
        return post("/api/projects/" + UUID.randomUUID() + "/runs")
                .cookie(auth, new Cookie("XSRF-TOKEN", "t"))
                .header("X-XSRF-TOKEN", "t");
    }

    private Cookie tokenCookie(AppUser user) {
        return new Cookie("GK_TOKEN", tokens.issue(user.getId()));
    }

    private AppUser saveUser() {
        String id = UUID.randomUUID().toString();
        return users.save(new AppUser("https://idp.example.test", id, id + "@example.test"));
    }
}
