package com.erezer.admin.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erezer.admin.security.Permission;
import com.erezer.admin.security.Role;
import com.erezer.admin.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

class PasswordResetRequestIntegrationTest extends IntegrationTest {

    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        admin = adminBearer();
    }

    @Test
    void aRequestWaitsForAnAdminWhoSetsANewPassword() throws Exception {
        String email = createUser(Role.MODERATOR, List.of(Permission.ORDER_VIEW));
        forgot(email.toUpperCase()).andExpect(status().isAccepted());
        forgot(email).andExpect(status().isAccepted()); // asking again doesn't add a second request

        List<String> emails = read(requests(), "$[*].email");
        assertThat(emails).containsOnlyOnce(email);
        Number userId = ((List<Number>) read(requests(), "$[?(@.email == '" + email + "')].userId")).getFirst();

        mvc.perform(post("/api/staff/" + userId + "/reset-password").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"newPassword\":\"FreshStart123\"}"))
                .andExpect(status().isOk());
        assertThat((List<String>) read(requests(), "$[*].email")).doesNotContain(email);
        login(email, "FreshStart123").andExpect(status().isOk());
    }

    @Test
    void unknownEmailsGetTheSameAnswerAndLeaveNoRequest() throws Exception {
        String nobody = "nobody-" + System.nanoTime() + "@test.local";
        forgot(nobody).andExpect(status().isAccepted());
        assertThat((List<String>) read(requests(), "$[*].email")).doesNotContain(nobody);
    }

    @Test
    void onlyAdminsSeeAndDismissRequests() throws Exception {
        String email = createUser(Role.MODERATOR, List.of());
        forgot(email).andExpect(status().isAccepted());
        String moderator = bearer(Role.MODERATOR, Permission.ORDER_VIEW);
        mvc.perform(get("/api/staff/password-requests").header("Authorization", moderator)).andExpect(status().isForbidden());

        Number requestId = ((List<Number>) read(requests(), "$[?(@.email == '" + email + "')].id")).getFirst();
        mvc.perform(post("/api/staff/password-requests/" + requestId + "/dismiss").header("Authorization", admin))
                .andExpect(status().isNoContent());
        assertThat((List<String>) read(requests(), "$[*].email")).doesNotContain(email);
        mvc.perform(post("/api/staff/password-requests/" + requestId + "/dismiss").header("Authorization", admin))
                .andExpect(status().isNotFound());
    }

    private MvcResult requests() throws Exception {
        return mvc.perform(get("/api/staff/password-requests").header("Authorization", admin))
                .andExpect(status().isOk()).andReturn();
    }

    private ResultActions forgot(String email) throws Exception {
        return mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}"));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }
}
