package com.gatekeeper.qaplatform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gatekeeper.qaplatform.IntegrationTestBase;
import com.gatekeeper.qaplatform.auth.AppUser;
import com.gatekeeper.qaplatform.auth.AppUserRepository;
import com.gatekeeper.qaplatform.auth.JwtTokenService;
import com.gatekeeper.qaplatform.auth.OidcLoginFailureHandler;
import com.gatekeeper.qaplatform.auth.Role;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;

class AuditIntegrationTest extends IntegrationTestBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private AppUserRepository users;
    @Autowired private JwtTokenService tokens;
    @Autowired private AuditEventRepository events;
    @Autowired private OidcLoginFailureHandler loginFailureHandler;

    @Test
    void annotatedActionRecordsActorTargetAndDetails() throws Exception {
        AppUser admin = saveUser(Role.ADMIN);
        AppUser target = saveUser(Role.VIEWER);

        mockMvc.perform(update(target.getId(), admin, "QA_LEAD")).andExpect(status().isOk());

        AuditEvent event = latestFor("USER_UPDATE", target.getId().toString());
        assertThat(event.getActor()).isEqualTo(admin.getEmail());
        assertThat(event.getDetailsJson()).contains("SUCCESS", "QA_LEAD");
        assertThat(event.getAt()).isNotNull();
    }

    @Test
    void failedActionIsAlsoRecorded() throws Exception {
        AppUser admin = saveUser(Role.ADMIN);

        mockMvc.perform(update(admin.getId(), admin, "VIEWER")).andExpect(status().isConflict());

        AuditEvent event = latestFor("USER_UPDATE", admin.getId().toString());
        assertThat(event.getActor()).isEqualTo(admin.getEmail());
        assertThat(event.getDetailsJson()).contains("FAILURE", "ConflictException");
    }

    @Test
    void logoutIsRecorded() throws Exception {
        AppUser user = saveUser(Role.VIEWER);

        mockMvc.perform(post("/api/auth/logout")
                        .cookie(tokenCookie(user), new Cookie("XSRF-TOKEN", "t"))
                        .header("X-XSRF-TOKEN", "t"))
                .andExpect(status().isNoContent());

        assertThat(latestFor("LOGOUT", user.getId().toString()).getActor()).isEqualTo(user.getEmail());
    }

    @Test
    void failedLoginIsRecorded() throws Exception {
        long before = events.count();

        loginFailureHandler.onAuthenticationFailure(
                new MockHttpServletRequest(), new MockHttpServletResponse(), new BadCredentialsException("secret text"));

        assertThat(events.count()).isEqualTo(before + 1);
        assertThat(events.findAll())
                .filteredOn(event -> "anonymous".equals(event.getActor()))
                .allSatisfy(event -> assertThat(event.getDetailsJson()).doesNotContain("secret text"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder update(
            UUID id, AppUser actor, String role) {
        return put("/api/users/" + id)
                .cookie(tokenCookie(actor), new Cookie("XSRF-TOKEN", "t"))
                .header("X-XSRF-TOKEN", "t")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"" + role + "\",\"enabled\":true}");
    }

    private AuditEvent latestFor(String action, String target) {
        return events.findAll().stream()
                .filter(event -> action.equals(event.getAction()) && target.equals(event.getTarget()))
                .reduce((first, second) -> second)
                .orElseThrow();
    }

    private Cookie tokenCookie(AppUser user) {
        return new Cookie("GK_TOKEN", tokens.issue(user.getId()));
    }

    private AppUser saveUser(Role role) {
        String id = UUID.randomUUID().toString();
        AppUser user = new AppUser("https://idp.example.test", id, id + "@example.test");
        user.setRole(role);
        return users.save(user);
    }
}
