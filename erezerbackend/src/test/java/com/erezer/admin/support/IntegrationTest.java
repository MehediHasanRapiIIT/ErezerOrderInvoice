package com.erezer.admin.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erezer.admin.TestcontainersConfiguration;
import com.erezer.admin.security.Permission;
import com.erezer.admin.security.Role;
import com.erezer.admin.user.User;
import com.erezer.admin.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Base for API tests: full application context against a Testcontainers Postgres.
 * Tests share one database, so each test should create its own uniquely named data.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

    protected static final String PASSWORD = "Secret12345";

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected UserRepository users;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    protected String createUser(Role role, List<Permission> permissions) {
        String email = role.name().toLowerCase() + "-" + UUID.randomUUID() + "@test.local";
        User user = new User("Test " + role.name(), email, null, passwordEncoder.encode(PASSWORD), role);
        user.replacePermissions(permissions);
        users.save(user);
        return email;
    }

    /** Bearer header value for a fresh user with the given role and permissions. */
    protected String bearer(Role role, Permission... permissions) throws Exception {
        String email = createUser(role, List.of(permissions));
        MvcResult result = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return "Bearer " + JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    protected String adminBearer() throws Exception {
        return bearer(Role.ADMIN);
    }

    protected static <T> T read(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), path);
    }
}
