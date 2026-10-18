package com.erezer.admin.staff;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erezer.admin.security.Permission;
import com.erezer.admin.security.Role;
import com.erezer.admin.support.IntegrationTest;
import com.erezer.admin.user.User;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class StaffApiIntegrationTest extends IntegrationTest {

    private static final String TEMP_PASSWORD = "Welcome12345";

    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        admin = adminBearer();
    }

    @Test
    void adminCreatesModeratorWhoCanSignInWithOnlyGrantedPermissions() throws Exception {
        String email = "mod-" + UUID.randomUUID() + "@test.local";
        // STAFF_MANAGE is admin-only and must be dropped.
        long id = createModerator(email, "[\"ORDER_VIEW\",\"ORDER_CREATE\",\"STAFF_MANAGE\"]");

        mvc.perform(get("/api/staff/" + id).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MODERATOR"))
                .andExpect(jsonPath("$.permissions.length()").value(2))
                .andExpect(jsonPath("$.permissions").value(not(hasItem("STAFF_MANAGE"))));

        String token = login(email, TEMP_PASSWORD);
        mvc.perform(get("/api/orders").header("Authorization", token)).andExpect(status().isOk());
        mvc.perform(get("/api/expenses").header("Authorization", token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/staff").header("Authorization", token)).andExpect(status().isForbidden());
    }

    @Test
    void duplicateEmailAndWeakPasswordAreRejected() throws Exception {
        String email = "dup-" + UUID.randomUUID() + "@test.local";
        createModerator(email, "[]");
        mvc.perform(post("/api/staff").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(email.toUpperCase(), TEMP_PASSWORD, "[]")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
        mvc.perform(post("/api/staff").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(createJson("weak-" + UUID.randomUUID() + "@test.local", "short", "[]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WEAK_PASSWORD"));
    }

    @Test
    void permissionChangeAppliesImmediatelyWithoutSigningOut() throws Exception {
        String email = "perm-" + UUID.randomUUID() + "@test.local";
        long id = createModerator(email, "[\"ORDER_VIEW\"]");
        MvcResult login = loginResult(email, TEMP_PASSWORD);
        String oldToken = "Bearer " + JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken");
        Cookie refresh = login.getResponse().getCookie("erezer_rt");

        Thread.sleep(1100); // tokens carry whole-second timestamps
        mvc.perform(put("/api/staff/" + id).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Moderator\",\"role\":\"MODERATOR\",\"permissions\":[\"EXPENSE_VIEW\"],\"version\":0}"))
                .andExpect(status().isOk());

        // The old token (still carrying ORDER_VIEW) is rejected at once...
        mvc.perform(get("/api/orders").header("Authorization", oldToken)).andExpect(status().isUnauthorized());
        // ...but the session survives: a refresh yields a token with the new permissions.
        MvcResult refreshed = mvc.perform(post("/api/auth/refresh").cookie(refresh))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.permissions").value(hasItem("EXPENSE_VIEW")))
                .andExpect(jsonPath("$.user.permissions").value(not(hasItem("ORDER_VIEW"))))
                .andReturn();
        String newToken = "Bearer " + JsonPath.read(refreshed.getResponse().getContentAsString(), "$.accessToken");
        mvc.perform(get("/api/expenses").header("Authorization", newToken)).andExpect(status().isOk());
        mvc.perform(get("/api/orders").header("Authorization", newToken)).andExpect(status().isForbidden());
    }

    @Test
    void deactivationLocksTheAccountOutImmediately() throws Exception {
        String email = "gone-" + UUID.randomUUID() + "@test.local";
        long id = createModerator(email, "[\"ORDER_VIEW\"]");
        MvcResult login = loginResult(email, TEMP_PASSWORD);
        String token = "Bearer " + JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken");
        Cookie refresh = login.getResponse().getCookie("erezer_rt");

        mvc.perform(post("/api/staff/" + id + "/deactivate").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mvc.perform(get("/api/orders").header("Authorization", token)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").cookie(refresh)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + TEMP_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));

        mvc.perform(post("/api/staff/" + id + "/activate").header("Authorization", admin)).andExpect(status().isOk());
        login(email, TEMP_PASSWORD);
    }

    @Test
    void resetPasswordUnlocksAndSignsOutEverywhere() throws Exception {
        String email = "reset-" + UUID.randomUUID() + "@test.local";
        long id = createModerator(email, "[\"ORDER_VIEW\"]");
        String token = login(email, TEMP_PASSWORD);
        for (int i = 0; i < 5; i++) { // lock the account
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + email + "\",\"password\":\"Wrong123456\"}"));
        }
        mvc.perform(get("/api/staff/" + id).header("Authorization", admin)).andExpect(jsonPath("$.locked").value(true));

        Thread.sleep(1100);
        mvc.perform(post("/api/staff/" + id + "/reset-password").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"newPassword\":\"BrandNew2026\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(false));

        mvc.perform(get("/api/orders").header("Authorization", token)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + TEMP_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
        login(email, "BrandNew2026");
    }

    @Test
    void adminCannotLockThemselvesOutOrRemoveTheLastAdmin() throws Exception {
        String soleAdminEmail = createUser(Role.ADMIN, java.util.List.of());
        User soleAdmin = users.findByEmail(soleAdminEmail).orElseThrow();
        String selfToken = login(soleAdminEmail, PASSWORD);

        mvc.perform(post("/api/staff/" + soleAdmin.getId() + "/deactivate").header("Authorization", selfToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANNOT_DEACTIVATE_SELF"));
        mvc.perform(put("/api/staff/" + soleAdmin.getId()).header("Authorization", selfToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"X\",\"role\":\"MODERATOR\",\"permissions\":[],\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANNOT_DEMOTE_SELF"));
    }

    @Test
    void listSearchesByNameOrEmailAndFilters() throws Exception {
        String marker = UUID.randomUUID().toString().substring(0, 8);
        createModerator("find-" + marker + "@test.local", "[]");
        mvc.perform(get("/api/staff").header("Authorization", admin).param("q", marker).param("role", "MODERATOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].email").value("find-" + marker + "@test.local"));
    }

    @Test
    void moderatorsCannotManageStaff() throws Exception {
        String moderator = bearer(Role.MODERATOR, Permission.ORDER_VIEW, Permission.STAFF_MANAGE);
        mvc.perform(get("/api/staff").header("Authorization", moderator)).andExpect(status().isForbidden());
    }

    private long createModerator(String email, String permissionsJson) throws Exception {
        MvcResult result = mvc.perform(post("/api/staff").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(createJson(email, TEMP_PASSWORD, permissionsJson)))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) read(result, "$.id")).longValue();
    }

    private static String createJson(String email, String password, String permissionsJson) {
        return """
                {"fullName":"Moderator","email":"%s","phone":"01700000000","role":"MODERATOR",
                 "password":"%s","permissions":%s}
                """.formatted(email, password, permissionsJson);
    }

    private MvcResult loginResult(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
    }

    private String login(String email, String password) throws Exception {
        return "Bearer " + JsonPath.read(loginResult(email, password).getResponse().getContentAsString(), "$.accessToken");
    }
}
