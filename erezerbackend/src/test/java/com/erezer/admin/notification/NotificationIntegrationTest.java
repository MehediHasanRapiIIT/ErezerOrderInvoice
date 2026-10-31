package com.erezer.admin.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erezer.admin.security.Permission;
import com.erezer.admin.security.Role;
import com.erezer.admin.support.IntegrationTest;
import java.security.KeyPairGenerator;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class NotificationIntegrationTest extends IntegrationTest {

    /** Endpoints that change nothing worth telling anyone, or that nobody signed in calls. Everything else must notify. */
    private static final Set<String> NOT_ACTIONS = Set.of(
            "/api/auth/login", "/api/auth/refresh", "/api/auth/logout", "/api/auth/forgot-password",
            "/api/files/images", "/api/files/receipts", "/api/files/employee-images", "/api/files/selfies",
            "/api/files/daily-expense-receipts",
            "/api/webhooks/steadfast", "/api/courier/settings/test", "/api/courier/preview",
            "/api/notifications/{id}/read", "/api/notifications/read-all", "/api/notifications/devices",
            "/api/notifications/devices/remove", "/api/notifications/settings/test");

    private record Person(long id, String bearer) {
    }

    @Autowired
    JdbcClient jdbc;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @MockitoBean
    FcmClient fcm;

    private Person admin;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.sql("delete from notification_rules").update();
        jdbc.sql("delete from notification_settings").update();
        admin = person(Role.ADMIN);
    }

    @Test
    void noEndpointThatChangesDataIsLeftOut() {
        List<String> missing = new ArrayList<>();
        mappings.getHandlerMethods().forEach((RequestMappingInfo info, HandlerMethod method) -> {
            boolean changes = info.getMethodsCondition().getMethods().stream().anyMatch(m -> m != RequestMethod.GET);
            boolean ours = info.getPatternValues().stream().anyMatch(p -> p.startsWith("/api/"));
            boolean excused = info.getPatternValues().stream().anyMatch(NOT_ACTIONS::contains);
            if (changes && ours && !excused && !method.hasMethodAnnotation(Notifies.class)) {
                missing.add(info.getPatternValues() + " " + method.getMethod().getName());
            }
        });
        assertThat(missing).as("add @Notifies to these").isEmpty();
    }

    @Test
    void everyDescriptionQueryWorks() {
        for (NotificationTarget target : NotificationTarget.values()) {
            if (target.sql() != null) {
                assertThat(jdbc.sql(target.sql()).param("id", 0L).query(String.class).optional()).as(target.name()).isEmpty();
            }
        }
    }

    @Test
    void aModeratorsActionReachesAdminsWithItsDetailsAndALink() throws Exception {
        Person moderator = person(Role.MODERATOR, Permission.ORDER_CREATE, Permission.ORDER_EDIT);
        long order = createOrder(moderator, "Karim Sheikh");

        feed(admin).andExpect(jsonPath("$.content[0].action").value("ORDER_CREATED"))
                .andExpect(jsonPath("$.content[0].section").value("Orders"))
                .andExpect(jsonPath("$.content[0].title").value(containsString("Order created")))
                .andExpect(jsonPath("$.content[0].body").value(containsString("Karim Sheikh")))
                .andExpect(jsonPath("$.content[0].link").value("/orders/" + order))
                .andExpect(jsonPath("$.content[0].actorRole").value("MODERATOR"))
                .andExpect(jsonPath("$.content[0].read").value(false));
    }

    @Test
    void adminsActionsAreQuietUntilTheRuleSaysOtherwise() throws Exception {
        Person other = person(Role.ADMIN);
        long before = unread(other);
        createOrder(admin, "Admin Order");
        assertThat(unread(other)).isEqualTo(before);

        rule("ORDER_CREATED", true, true, true, true, true).andExpect(status().isNoContent());
        createOrder(admin, "Admin Order");
        assertThat(unread(other)).isEqualTo(before + 1);
    }

    @Test
    void aRuleCanSwitchAnActionOffOrLeaveOutARole() throws Exception {
        Person moderator = person(Role.MODERATOR, Permission.ORDER_CREATE);
        Person employee = person(Role.EMPLOYEE, Permission.ORDER_CREATE);
        long start = unread(admin);

        rule("ORDER_CREATED", true, true, true, false, false).andExpect(status().isNoContent());
        createOrder(employee, "From employee");
        assertThat(unread(admin)).isEqualTo(start);
        createOrder(moderator, "From moderator");
        assertThat(unread(admin)).isEqualTo(start + 1);

        rule("ORDER_CREATED", false, true, true, true, false).andExpect(status().isNoContent());
        createOrder(moderator, "Switched off");
        assertThat(unread(admin)).isEqualTo(start + 1);
    }

    @Test
    void eachPersonGetsExactlyWhatTheAdminTicked() throws Exception {
        Person reader = person(Role.EMPLOYEE);
        Person other = person(Role.EMPLOYEE, Permission.NOTIFICATION_VIEW);
        Person doer = person(Role.MODERATOR, Permission.ORDER_CREATE, Permission.EXPENSE_CREATE);

        subscribe(reader, "ORDER_CREATED", true, false).andExpect(status().isNoContent());
        Person signedIn = relogin(reader);
        createOrder(doer, "Ticked");

        feed(signedIn).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].action").value("ORDER_CREATED"));
        feed(other).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void anAdminCanBeLeftOutToo() throws Exception {
        Person doer = person(Role.MODERATOR, Permission.ORDER_CREATE);
        subscribe(admin, "ORDER_CREATED", false, false).andExpect(status().isNoContent());
        long start = unread(admin);
        createOrder(doer, "Not for this admin");
        assertThat(unread(admin)).isEqualTo(start);
    }

    @Test
    void tickingSomethingOpensTheMenuAndUntickingEverythingClosesIt() throws Exception {
        Person worker = person(Role.EMPLOYEE);
        mvc.perform(get("/api/notifications").header(HttpHeaders.AUTHORIZATION, worker.bearer())).andExpect(status().isForbidden());

        subscribe(worker, "ORDER_CREATED", true, true).andExpect(status().isNoContent());
        Person opened = relogin(worker);
        mvc.perform(get("/api/notifications").header(HttpHeaders.AUTHORIZATION, opened.bearer())).andExpect(status().isOk());

        subscribe(worker, "ORDER_CREATED", false, false).andExpect(status().isNoContent());
        Person closed = relogin(worker);
        mvc.perform(get("/api/notifications").header(HttpHeaders.AUTHORIZATION, closed.bearer())).andExpect(status().isForbidden());
    }

    @Test
    void pushGoesOnlyToThoseTickedForIt() throws Exception {
        saveFirebase().andExpect(status().isOk());
        when(fcm.send(any(), any(), anyMap())).thenReturn(FcmClient.Result.SENT);
        Person pushed = person(Role.EMPLOYEE, Permission.NOTIFICATION_VIEW);
        Person quiet = person(Role.EMPLOYEE, Permission.NOTIFICATION_VIEW);
        subscribe(pushed, "EMPLOYEE_REGISTERED", true, true).andExpect(status().isNoContent());
        subscribe(quiet, "EMPLOYEE_REGISTERED", true, false).andExpect(status().isNoContent());
        Person pushedNow = relogin(pushed);
        Person quietNow = relogin(quiet);
        String pushedToken = "device-" + UUID.randomUUID();
        String quietToken = "device-" + UUID.randomUUID();
        device(pushedNow, pushedToken).andExpect(status().isNoContent());
        device(quietNow, quietToken).andExpect(status().isNoContent());

        Person doer = person(Role.MODERATOR, Permission.ORDER_CREATE);
        jdbc.sql("insert into notification_rules (action, enabled, push, from_moderator, from_employee, from_admin, updated_at) "
                + "values ('ORDER_CREATED', true, true, true, true, false, now())").update();
        subscribe(pushed, "ORDER_CREATED", true, true).andExpect(status().isNoContent());
        subscribe(quiet, "ORDER_CREATED", true, false).andExpect(status().isNoContent());
        createOrder(doer, "Pushed");

        verify(fcm, timeout(3000)).send(any(), eq(pushedToken), anyMap());
        verify(fcm, after(300).never()).send(any(), eq(quietToken), anyMap());
    }

    @Test
    void aRuleCanKeepAnActionOutOfPushes() throws Exception {
        saveFirebase().andExpect(status().isOk());
        String token = "device-" + UUID.randomUUID();
        device(admin, token).andExpect(status().isNoContent());
        rule("ORDER_CREATED", true, false, true, true, false).andExpect(status().isNoContent());

        createOrder(person(Role.MODERATOR, Permission.ORDER_CREATE), "In the menu only");

        verify(fcm, after(300).never()).send(any(), eq(token), anyMap());
        feed(admin).andExpect(jsonPath("$.content[0].body").value(containsString("In the menu only")));
    }

    @Test
    void aDeviceThatStoppedAcceptingIsForgotten() throws Exception {
        saveFirebase().andExpect(status().isOk());
        String token = "device-" + UUID.randomUUID();
        when(fcm.send(any(), eq(token), anyMap())).thenReturn(FcmClient.Result.INVALID_TOKEN);
        device(admin, token).andExpect(status().isNoContent());

        mvc.perform(post("/api/notifications/settings/test").header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(jsonPath("$.failed").value(1));

        assertThat(jdbc.sql("select count(*) from notification_devices where token = :t").param("t", token).query(Long.class).single()).isZero();
    }

    @Test
    void aFailedActionNotifiesNobody() throws Exception {
        Person moderator = person(Role.MODERATOR, Permission.ORDER_CREATE);
        long start = unread(admin);
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("{}")
                .header(HttpHeaders.AUTHORIZATION, moderator.bearer())).andExpect(status().isBadRequest());
        assertThat(unread(admin)).isEqualTo(start);
    }

    @Test
    void deletedRecordsAreStillDescribed() throws Exception {
        Person moderator = person(Role.MODERATOR, Permission.ORDER_CREATE, Permission.ORDER_DELETE);
        long order = createOrder(moderator, "Soon Gone");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/orders/" + order)
                .header(HttpHeaders.AUTHORIZATION, moderator.bearer())).andExpect(status().is2xxSuccessful());

        feed(admin).andExpect(jsonPath("$.content[0].action").value("ORDER_DELETED"))
                .andExpect(jsonPath("$.content[0].body").value(containsString("Soon Gone")))
                .andExpect(jsonPath("$.content[0].link").value("/orders"));
    }

    @Test
    void onTimeAttendanceStaysQuietAndLateAttendanceIsSeen() throws Exception {
        assertThat(NotificationAction.ATTENDANCE_CHECK_IN.enabledByDefault()).isFalse();
        assertThat(NotificationAction.ATTENDANCE_CHECK_IN_PROBLEM.enabledByDefault()).isTrue();
    }

    @Test
    void readingIsPerPerson() throws Exception {
        Person other = person(Role.ADMIN);
        createOrder(person(Role.MODERATOR, Permission.ORDER_CREATE), "Read me");
        MvcResult mine = feed(admin).andReturn();
        Number id = read(mine, "$.content[0].id");

        mvc.perform(post("/api/notifications/" + id + "/read").header(HttpHeaders.AUTHORIZATION, admin.bearer())).andExpect(status().isNoContent());

        feed(admin).andExpect(jsonPath("$.content[0].read").value(true));
        feed(other).andExpect(jsonPath("$.content[0].read").value(false));
        mvc.perform(post("/api/notifications/read-all").header(HttpHeaders.AUTHORIZATION, other.bearer())).andExpect(status().isNoContent());
        assertThat(unread(other)).isZero();
    }

    @Test
    void theFilterAndPagesWork() throws Exception {
        Person moderator = person(Role.MODERATOR, Permission.ORDER_CREATE, Permission.ORDER_NOTE_ADD);
        for (int i = 0; i < 3; i++) {
            createOrder(moderator, "Paged");
        }
        String today = LocalDate.now(ZoneId.of("Asia/Dhaka")).toString();
        mvc.perform(get("/api/notifications").param("size", "2").param("section", "Orders").param("actorId", String.valueOf(moderator.id()))
                        .param("from", today).param("to", today).header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content.length()").value(2));
        mvc.perform(get("/api/notifications").param("section", "Stock").param("actorId", String.valueOf(moderator.id()))
                        .header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void theFirebaseKeyIsEncryptedAndNeverSentBack() throws Exception {
        MvcResult saved = saveFirebase().andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.projectId").value("erezer-test"))
                .andExpect(jsonPath("$.privateKey").doesNotExist())
                .andReturn();
        assertThat(saved.getResponse().getContentAsString()).doesNotContain("PRIVATE KEY", "AIzaSyTestKey123456");

        String stored = jdbc.sql("select private_key_encrypted from notification_settings").query(String.class).single();
        assertThat(stored).doesNotContain("PRIVATE KEY");

        mvc.perform(get("/api/notifications/push-config").header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.firebase.projectId").value("erezer-test"))
                .andExpect(jsonPath("$.vapidKey").value("vapid-key"))
                .andExpect(content -> assertThat(content.getResponse().getContentAsString()).doesNotContain("PRIVATE KEY"));
    }

    @Test
    void aWrongKeyFileIsRefused() throws Exception {
        settings("{\"nope\":true}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SERVICE_ACCOUNT"));
        settings("not json").andExpect(status().isBadRequest());
        settings("").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SERVICE_ACCOUNT_REQUIRED"));
    }

    @Test
    void setupNeedsItsPermission() throws Exception {
        Person viewer = person(Role.MODERATOR, Permission.NOTIFICATION_VIEW);
        mvc.perform(get("/api/notifications").header(HttpHeaders.AUTHORIZATION, viewer.bearer())).andExpect(status().isOk());
        mvc.perform(get("/api/notifications/rules").header(HttpHeaders.AUTHORIZATION, viewer.bearer())).andExpect(status().isForbidden());
        mvc.perform(get("/api/notifications/people").header(HttpHeaders.AUTHORIZATION, viewer.bearer())).andExpect(status().isForbidden());
        mvc.perform(get("/api/notifications/settings").header(HttpHeaders.AUTHORIZATION, viewer.bearer())).andExpect(status().isForbidden());

        Person none = person(Role.EMPLOYEE);
        mvc.perform(get("/api/notifications/unread-count").header(HttpHeaders.AUTHORIZATION, none.bearer())).andExpect(status().isForbidden());
    }

    @Test
    void theMenuListsEveryAction() throws Exception {
        mvc.perform(get("/api/notifications/rules").header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(jsonPath("$.length()").value(NotificationAction.values().length))
                .andExpect(jsonPath("$[*].action").value(hasItem("BOOST_COST_EDITED")))
                .andExpect(jsonPath("$[*].action").value(not(hasItem("LOGIN"))));
        rule("NOT_REAL", true, true, true, true, true).andExpect(status().isBadRequest());
    }

    @Test
    void aPushAssertionIsProperlySigned() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        String jwt = FcmClient.assertion(new FcmClient.Credentials("p", "svc@p.iam", pem(pair.getPrivate().getEncoded())), java.time.Instant.now());

        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);
        assertThat(new String(Base64.getUrlDecoder().decode(parts[1]))).contains("svc@p.iam", "firebase.messaging");
        var check = java.security.Signature.getInstance("SHA256withRSA");
        check.initVerify(pair.getPublic());
        check.update((parts[0] + "." + parts[1]).getBytes());
        assertThat(check.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
    }

    private static String pem(byte[] key) {
        return "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(key) + "\n-----END PRIVATE KEY-----\n";
    }

    private ResultActions saveFirebase() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String key = pem(generator.generateKeyPair().getPrivate().getEncoded()).replace("\r", "").replace("\n", "\\n");
        return settings("{\"project_id\":\"erezer-test\",\"client_email\":\"svc@erezer-test.iam\",\"private_key\":\"" + key + "\"}");
    }

    private ResultActions settings(String serviceAccount) throws Exception {
        String web = "const firebaseConfig = { apiKey: 'AIzaSyTestKey123456', projectId: 'erezer-test', messagingSenderId: '123', appId: '1:123:web:abc' };";
        String body = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(
                java.util.Map.of("serviceAccount", serviceAccount, "webConfig", web, "vapidKey", "vapid-key"));
        return mvc.perform(put("/api/notifications/settings").contentType(MediaType.APPLICATION_JSON).content(body)
                .header(HttpHeaders.AUTHORIZATION, admin.bearer()));
    }

    private ResultActions rule(String action, boolean enabled, boolean push, boolean moderator, boolean employee, boolean fromAdmin) throws Exception {
        return mvc.perform(put("/api/notifications/rules").contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, admin.bearer())
                .content("[{\"action\":\"" + action + "\",\"enabled\":" + enabled + ",\"push\":" + push + ",\"fromModerator\":" + moderator
                        + ",\"fromEmployee\":" + employee + ",\"fromAdmin\":" + fromAdmin + "}]"));
    }

    private ResultActions subscribe(Person person, String action, boolean see, boolean push) throws Exception {
        return mvc.perform(put("/api/notifications/people/" + person.id()).contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, admin.bearer())
                .content("[{\"action\":\"" + action + "\",\"see\":" + see + ",\"push\":" + push + "}]"));
    }

    private ResultActions device(Person person, String token) throws Exception {
        return mvc.perform(put("/api/notifications/devices").contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, person.bearer())
                .content("{\"token\":\"" + token + "\"}"));
    }

    private ResultActions feed(Person person) throws Exception {
        return mvc.perform(get("/api/notifications").header(HttpHeaders.AUTHORIZATION, person.bearer()));
    }

    private long unread(Person person) throws Exception {
        MvcResult result = mvc.perform(get("/api/notifications/unread-count").header(HttpHeaders.AUTHORIZATION, person.bearer())).andReturn();
        return ((Number) read(result, "$.count")).longValue();
    }

    private long createOrder(Person person, String customer) throws Exception {
        MvcResult created = mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, person.bearer())
                        .content("""
                                {"paymentMethod":"CASH","deliveryCharge":60,
                                 "customer":{"name":"%s","phone":"01711000555","address":"Mirpur-2, Dhaka"},
                                 "items":[{"productName":"T-Shirt","unitPrice":500,"quantity":1}]}
                                """.formatted(customer)))
                .andExpect(status().isCreated()).andReturn();
        return ((Number) read(created, "$.id")).longValue();
    }

    private final java.util.Map<Long, String> emails = new java.util.HashMap<>();

    private Person person(Role role, Permission... permissions) throws Exception {
        String email = createUser(role, List.of(permissions));
        Person person = login(email);
        emails.put(person.id(), email);
        return person;
    }

    /** A fresh token, carrying permissions changed since signing in. */
    private Person relogin(Person person) throws Exception {
        return login(emails.get(person.id()));
    }

    private Person login(String email) throws Exception {
        MvcResult login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return new Person(((Number) read(login, "$.user.id")).longValue(), "Bearer " + read(login, "$.accessToken"));
    }
}
