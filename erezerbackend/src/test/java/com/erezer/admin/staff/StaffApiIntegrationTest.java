package com.erezer.admin.staff;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
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
import java.util.List;
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
        long id = createModerator(email, "[\"ORDER_VIEW\",\"ORDER_CREATE\"]");

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
    void moderatorsWithoutThePermissionCannotManageStaff() throws Exception {
        String moderator = bearer(Role.MODERATOR, Permission.ORDER_VIEW);
        mvc.perform(get("/api/staff").header("Authorization", moderator)).andExpect(status().isForbidden());
    }

    @Test
    void aStaffManagerLooksAfterModeratorsButNeverAdminsOrThemselves() throws Exception {
        String managerEmail = createUser(Role.MODERATOR, List.of(Permission.STAFF_MANAGE, Permission.ORDER_VIEW, Permission.ORDER_CREATE));
        String manager = login(managerEmail, PASSWORD);

        // Can add a moderator with permissions they hold themselves…
        String email = "managed-" + UUID.randomUUID() + "@test.local";
        mvc.perform(post("/api/staff").header("Authorization", manager).contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(email, TEMP_PASSWORD, "[\"ORDER_VIEW\",\"ORDER_CREATE\"]")))
                .andExpect(status().isCreated());
        // …but not give ones they don't have, and not add admins.
        mvc.perform(post("/api/staff").header("Authorization", manager).contentType(MediaType.APPLICATION_JSON)
                        .content(createJson("x-" + email, TEMP_PASSWORD, "[\"EXPENSE_VIEW\"]")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(startsWith("You can only give permissions you have yourself")));
        mvc.perform(post("/api/staff").header("Authorization", manager).contentType(MediaType.APPLICATION_JSON)
                        .content(createJson("y-" + email, TEMP_PASSWORD, "[]").replace("MODERATOR", "ADMIN")))
                .andExpect(status().isForbidden());

        // Admins' accounts and their own are off limits.
        long adminId = users.findByEmail(createUser(Role.ADMIN, List.of())).orElseThrow().getId();
        mvc.perform(post("/api/staff/" + adminId + "/deactivate").header("Authorization", manager)).andExpect(status().isForbidden());
        // Nor can they reset the password of someone with permissions they don't have (then sign in as them).
        long stronger = users.findByEmail(createUser(Role.MODERATOR, List.of(Permission.ORDER_VIEW, Permission.EXPENSE_VIEW))).orElseThrow().getId();
        mvc.perform(post("/api/staff/" + stronger + "/reset-password").header("Authorization", manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"newPassword\":\"Another12345\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(startsWith("Only someone who has all of this person's permissions")));
        long weaker = users.findByEmail(createUser(Role.MODERATOR, List.of(Permission.ORDER_VIEW))).orElseThrow().getId();
        mvc.perform(post("/api/staff/" + weaker + "/reset-password").header("Authorization", manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"newPassword\":\"Another12345\"}"))
                .andExpect(status().isOk());

        long selfId = users.findByEmail(managerEmail).orElseThrow().getId();
        mvc.perform(post("/api/staff/" + selfId + "/reset-password").header("Authorization", manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"newPassword\":\"Another12345\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void changingASectionIncludesSeeingIt() throws Exception {
        String email = createUser(Role.MODERATOR, List.of(Permission.ASSET_MANAGE));
        String token = login(email, PASSWORD);
        mvc.perform(get("/api/assets").header("Authorization", token)).andExpect(status().isOk());   // View comes with it
        mvc.perform(get("/api/auth/me").header("Authorization", token))
                .andExpect(jsonPath("$.permissions").value(hasItem("ASSET_VIEW")));
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
