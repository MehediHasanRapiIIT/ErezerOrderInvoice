package com.erezer.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erezer.admin.TestcontainersConfiguration;
import com.erezer.admin.security.Permission;
import com.erezer.admin.security.Role;
import com.erezer.admin.user.User;
import com.erezer.admin.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AuthFlowIntegrationTest {

    private static final String PASSWORD = "Secret12345";
    private static final String COOKIE = "erezer_rt";

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Test
    void bootstrapAdminCanLogInAndHoldsEveryPermission() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("ADMIN@test.local", "Admin@12345678")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.role").value("ADMIN"))
                .andExpect(jsonPath("$.user.permissions.length()").value(Permission.values().length))
                .andExpect(cookie().httpOnly(COOKIE, true))
                .andExpect(cookie().path(COOKIE, "/api/auth"));
    }

    @Test
    void wrongPasswordIsRejectedWithProblemDetail() throws Exception {
        String email = moderator(List.of());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(email, "WrongPassword1")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void accountLocksAfterRepeatedFailures() throws Exception {
        String email = moderator(List.of());
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content(credentials(email, "WrongPassword1")));
        }
        // Even the correct password is refused while locked.
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(email, PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
    }

    @Test
    void protectedEndpointRequiresToken() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void meReturnsOnlyGrantedPermissionsForModerator() throws Exception {
        // STAFF_MANAGE is admin-only and must be dropped even if requested.
        String email = moderator(List.of(Permission.ORDER_VIEW, Permission.STAFF_MANAGE));
        String token = accessToken(login(email));

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.permissions").value(hasItem("ORDER_VIEW")))
                .andExpect(jsonPath("$.permissions").value(not(hasItem("STAFF_MANAGE"))));
    }

    @Test
    void moderatorWithoutPermissionGetsForbidden() throws Exception {
        String token = accessToken(login(moderator(List.of(Permission.ORDER_VIEW))));
        mvc.perform(get("/api/permissions").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void refreshRotatesTokenAndLogoutRevokesIt() throws Exception {
        MvcResult login = login(moderator(List.of()));
        Cookie first = login.getResponse().getCookie(COOKIE);

        MvcResult refreshed = mvc.perform(post("/api/auth/refresh").cookie(first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();
        Cookie second = refreshed.getResponse().getCookie(COOKIE);
        assertThat(second.getValue()).isNotEqualTo(first.getValue());

        mvc.perform(post("/api/auth/logout").cookie(second))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge(COOKIE, 0));

        mvc.perform(post("/api/auth/refresh").cookie(second))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void deactivatedUserCannotRefresh() throws Exception {
        String email = moderator(List.of());
        Cookie refresh = login(email).getResponse().getCookie(COOKIE);

        User user = users.findByEmail(email).orElseThrow();
        user.setActive(false);
        users.save(user);

        mvc.perform(post("/api/auth/refresh").cookie(refresh))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changePasswordEndsOtherSessionsImmediately() throws Exception {
        String email = moderator(List.of());
        Cookie otherDevice = login(email).getResponse().getCookie(COOKIE);
        String token = accessToken(login(email));

        mvc.perform(put("/api/auth/password").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"NewSecret12345\"}"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists(COOKIE));

        // No reuse grace for tokens revoked by a password change.
        mvc.perform(post("/api/auth/refresh").cookie(otherDevice))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void parallelRefreshWithinGraceWindowSucceeds() throws Exception {
        Cookie original = login(moderator(List.of())).getResponse().getCookie(COOKIE);
        mvc.perform(post("/api/auth/refresh").cookie(original)).andExpect(status().isOk());
        // A second tab sending the just-rotated cookie must not be logged out.
        mvc.perform(post("/api/auth/refresh").cookie(original)).andExpect(status().isOk());
    }

    @Test
    void changePasswordRejectsWeakPassword() throws Exception {
        String token = accessToken(login(moderator(List.of())));
        mvc.perform(put("/api/auth/password").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"onlyletters\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WEAK_PASSWORD"));
    }

    private String moderator(List<Permission> permissions) {
        String email = "mod-" + UUID.randomUUID() + "@test.local";
        User user = new User("Test Moderator", email, null, passwordEncoder.encode(PASSWORD), Role.MODERATOR);
        user.replacePermissions(permissions);
        users.save(user);
        return email;
    }

    private MvcResult login(String email) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
    }

    private static String accessToken(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private static String credentials(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }
}
