package com.erezer.admin.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erezer.admin.security.Permission;
import com.erezer.admin.security.Role;
import com.erezer.admin.storage.StorageService;
import com.erezer.admin.support.IntegrationTest;
import java.net.URI;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** Same real-clock trick as the office tests: 00:00 to 00:01 makes every Reply On late, 23:58 to 23:59 every finish early. */
class RemoteIntegrationTest extends IntegrationTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D};
    private static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");
    private static final String ALL_LATE = "\"00:00\",\"00:01\"";
    private static final String NEVER_LATE = "\"23:58\",\"23:59\"";

    private record Staff(long id, String bearer) {
    }

    @MockitoBean
    StorageService storage;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    RemoteService remoteService;

    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        LocalTime now = LocalTime.now(DHAKA);
        assumeTrue(now.isAfter(LocalTime.of(0, 5)) && now.isBefore(LocalTime.of(23, 50)));
        when(storage.presignedGetUrl(anyString())).thenReturn(URI.create("http://storage.test/remote").toURL());
        jdbc.sql("delete from attendance_special_hours").update();
        admin = adminBearer();
    }

    @Test
    void onlyRemoteWorkersReplyAndTheyNoLongerCheckIn() throws Exception {
        Staff person = staff(Permission.REMOTE_MARK, Permission.ATTENDANCE_MARK);
        setShift(person.id(), NEVER_LATE);
        on(person, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_REMOTE"));
        mvc.perform(get("/api/attendance/my-mode").header(HttpHeaders.AUTHORIZATION, person.bearer()))
                .andExpect(jsonPath("$.remote").value(false));

        setMode(person.id(), true).andExpect(status().isNoContent());
        mvc.perform(get("/api/attendance/my-mode").header(HttpHeaders.AUTHORIZATION, person.bearer()))
                .andExpect(jsonPath("$.remote").value(true));
        mvc.perform(get("/api/attendance/shifts").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$[?(@.userId == " + person.id() + ")].remote").value(hasItem(true)))
                .andExpect(jsonPath("$[?(@.userId == " + person.id() + ")].canReply").value(hasItem(true)));
        mvc.perform(post("/api/attendance/check-in").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":1,\"longitude\":1,\"photoFileId\":1}")
                        .header(HttpHeaders.AUTHORIZATION, person.bearer()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REMOTE_WORKER"));
        // Remote workers are not in the office lists.
        mvc.perform(get("/api/attendance/day").param("date", today().toString()).param("size", "100")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.content[?(@.userId == " + person.id() + ")]").isEmpty());

        on(person, null).andExpect(status().isOk()).andExpect(jsonPath("$.on").value(true));
        // Pressing again changes nothing.
        on(person, null).andExpect(status().isOk()).andExpect(jsonPath("$.day.sessions.length()").value(1));
    }

    @Test
    void aLateReplyOnNeedsAReasonAndTheAdminCanAddAFee() throws Exception {
        Staff person = remote(ALL_LATE);
        on(person, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        MvcResult result = on(person, "\"Power cut\"").andExpect(status().isOk())
                .andExpect(jsonPath("$.day.status").value("WORKING"))
                .andExpect(jsonPath("$.day.reviewStatus").value("PENDING"))
                .andExpect(jsonPath("$.day.lateReason").value("Power cut"))
                .andReturn();
        int day = read(result, "$.day.id");
        assertThat((Integer) read(result, "$.day.lateMinutes")).isPositive();
        off(person, true, null).andExpect(status().isOk()).andExpect(jsonPath("$.on").value(false));

        mvc.perform(get("/api/remote/pending").param("size", "100").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.content[*].id").value(hasItem(day)));
        review(day, "200").andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("APPROVED"))
                .andExpect(jsonPath("$.lateFee").value(200))
                .andExpect(jsonPath("$.status").value("LATE"));
        mvc.perform(get("/api/remote/summary").param("month", YearMonth.now(DHAKA).toString()).param("userId", String.valueOf(person.id()))
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.lateDays").value(1))
                .andExpect(jsonPath("$.daysWorked").value(1))
                .andExpect(jsonPath("$.lateFees").value(200));
        // The fee can be removed again.
        review(day, "0").andExpect(status().isOk()).andExpect(jsonPath("$.lateFee").value(0));
    }

    @Test
    void breaksNeedNoReasonButFinishingEarlyDoes() throws Exception {
        Staff person = remote(NEVER_LATE);
        on(person, null).andExpect(status().isOk()).andExpect(jsonPath("$.day.lateMinutes").value(0));
        off(person, false, null).andExpect(status().isOk()).andExpect(jsonPath("$.on").value(false));
        on(person, null).andExpect(status().isOk());
        off(person, true, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        off(person, true, "\"Sick\"").andExpect(status().isOk())
                .andExpect(jsonPath("$.day.sessions.length()").value(2))
                .andExpect(jsonPath("$.day.earlyReason").value("Sick"))
                .andExpect(jsonPath("$.day.status").value("EARLY"))
                .andExpect(jsonPath("$.day.reviewStatus").value("PENDING"));
        // Off again changes nothing.
        off(person, true, null).andExpect(status().isOk());
    }

    @Test
    void withoutHoursNothingIsLateAndAnImageCanBeAdded() throws Exception {
        Staff person = staff(Permission.REMOTE_MARK);
        setMode(person.id(), true).andExpect(status().isNoContent());
        MvcResult upload = mvc.perform(multipart("/api/files/remote-images")
                        .file(new MockMultipartFile("file", "work.png", "image/png", PNG))
                        .header(HttpHeaders.AUTHORIZATION, person.bearer()))
                .andExpect(status().isOk()).andReturn();
        Number image = read(upload, "$.id");
        mvc.perform(post("/api/remote/on").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageFileId\":" + image + ",\"note\":\"Starting chats\"}")
                        .header(HttpHeaders.AUTHORIZATION, person.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.day.reviewStatus").value("NONE"))
                .andExpect(jsonPath("$.day.sessions[0].imageUrl").value("http://storage.test/remote"))
                .andExpect(jsonPath("$.day.sessions[0].note").value("Starting chats"));
        mvc.perform(get("/api/remote/people").param("month", YearMonth.now(DHAKA).toString()).param("size", "100")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.content[?(@.userId == " + person.id() + ")].on").value(hasItem(true)));
        off(person, true, null).andExpect(status().isOk()).andExpect(jsonPath("$.day.status").value("PRESENT"));
    }

    @Test
    void workingPastTheEndStaysOnAndCountsAsExtra() throws Exception {
        Staff person = remote(ALL_LATE);
        on(person, "\"Late start\"").andExpect(status().isOk());
        // The hours ended at 00:01, but nothing closes the session before midnight.
        remoteService.closeDue();
        mvc.perform(get("/api/remote/me").header(HttpHeaders.AUTHORIZATION, person.bearer()))
                .andExpect(jsonPath("$.on").value(true));
        jdbc.sql("update remote_sessions set on_at = on_at - interval '90 minutes' where user_id = :u").param("u", person.id()).update();
        MvcResult off = off(person, true, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.day.earlyMinutes").value(0))
                .andExpect(jsonPath("$.day.notTurnedOff").value(false))
                .andReturn();
        int worked = read(off, "$.day.workedMinutes");
        int extra = read(off, "$.day.extraMinutes");
        assertThat(extra).isPositive().isLessThanOrEqualTo(worked);
        mvc.perform(get("/api/remote/summary").param("month", YearMonth.now(DHAKA).toString()).param("userId", String.valueOf(person.id()))
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.extraMinutes").value(extra));
    }

    @Test
    void startingBeforeTheStartCountsAsExtra() throws Exception {
        Staff person = remote(NEVER_LATE);
        on(person, null).andExpect(status().isOk()).andExpect(jsonPath("$.day.lateMinutes").value(0));
        jdbc.sql("update remote_sessions set on_at = on_at - interval '30 minutes' where user_id = :u").param("u", person.id()).update();
        off(person, false, null).andExpect(status().isOk()).andExpect(jsonPath("$.day.extraMinutes").value(30));
    }

    @Test
    void aForgottenReplyOffIsClosedAtMidnightAndFlagged() throws Exception {
        Staff person = staff(Permission.REMOTE_MARK);
        setMode(person.id(), true).andExpect(status().isNoContent());
        LocalDate yesterday = today().minusDays(1);
        long day = jdbc.sql("""
                        insert into remote_days (user_id, work_date, created_at, updated_at) values (:u, :d, now(), now()) returning id
                        """).param("u", person.id()).param("d", Date.valueOf(yesterday)).query(Long.class).single();
        jdbc.sql("""
                        insert into remote_sessions (day_id, user_id, on_at, created_at, updated_at) values (:day, :u, :on, now(), now())
                        """).param("day", day).param("u", person.id())
                .param("on", Timestamp.from(yesterday.atTime(20, 0).atZone(DHAKA).toInstant())).update();

        remoteService.closeDue();

        mvc.perform(get("/api/remote/days").param("userId", String.valueOf(person.id())).param("month", YearMonth.from(yesterday).toString())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.content[0].status").value("NOT_TURNED_OFF"))
                .andExpect(jsonPath("$.content[0].notTurnedOff").value(true))
                .andExpect(jsonPath("$.content[0].reviewStatus").value("PENDING"))
                .andExpect(jsonPath("$.content[0].workedMinutes").value(240))
                .andExpect(jsonPath("$.content[0].extraMinutes").value(0))
                .andExpect(jsonPath("$.content[0].sessions[0].autoClosed").value(true));
    }

    @Test
    void theAdminCanAddEditAndDeleteSessions() throws Exception {
        Staff person = remote(NEVER_LATE);
        String yesterday = today().minusDays(1).toString();
        MvcResult added = session(post("/api/remote/sessions"), person.id(), yesterday, "10:00", "12:00").andExpect(status().isOk())
                .andExpect(jsonPath("$.workedMinutes").value(120))
                .andReturn();
        int dayId = read(added, "$.id");
        int sessionId = read(added, "$.sessions[0].id");
        session(post("/api/remote/sessions"), person.id(), yesterday, "11:00", "13:00").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OVERLAP"));
        session(put("/api/remote/sessions/" + sessionId), person.id(), yesterday, "09:00", "12:30").andExpect(status().isOk())
                .andExpect(jsonPath("$.workedMinutes").value(210))
                .andExpect(jsonPath("$.sessions[0].editNote").value("Fixed by hand"));

        // Nobody changes their own.
        Staff editor = staff(Permission.REMOTE_EDIT, Permission.REMOTE_MARK);
        session(post("/api/remote/sessions"), editor.id(), yesterday, "10:00", "11:00", editor.bearer())
                .andExpect(status().isForbidden());
        // Without the permission nothing is visible.
        mvc.perform(get("/api/remote/people").param("month", YearMonth.now(DHAKA).toString()).header(HttpHeaders.AUTHORIZATION, person.bearer()))
                .andExpect(status().isForbidden());

        mvc.perform(delete("/api/remote/sessions/" + sessionId).header(HttpHeaders.AUTHORIZATION, admin)).andExpect(status().isNoContent());
        assertThat(jdbc.sql("select count(*) from remote_days where id = :id").param("id", dayId).query(Long.class).single()).isZero();
    }

    // ---- Helpers ----

    private Staff remote(String times) throws Exception {
        Staff person = staff(Permission.REMOTE_MARK);
        setShift(person.id(), times);
        setMode(person.id(), true).andExpect(status().isNoContent());
        return person;
    }

    private Staff staff(Permission... permissions) throws Exception {
        String email = createUser(Role.MODERATOR, List.of(permissions));
        MvcResult login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        Number id = read(login, "$.user.id");
        return new Staff(id.longValue(), "Bearer " + read(login, "$.accessToken"));
    }

    private void setShift(long userId, String times) throws Exception {
        String[] parts = times.split(",");
        mvc.perform(put("/api/attendance/shifts/" + userId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"checkIn\":" + parts[0] + ",\"checkOut\":" + parts[1] + ",\"graceMinutes\":0,\"weeklyOff\":[]}")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());
    }

    private ResultActions setMode(long userId, boolean remote) throws Exception {
        return mvc.perform(put("/api/attendance/mode/" + userId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"remote\":" + remote + "}").header(HttpHeaders.AUTHORIZATION, admin));
    }

    private ResultActions on(Staff person, String reason) throws Exception {
        return mvc.perform(post("/api/remote/on").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":" + (reason == null ? "null" : reason) + "}").header(HttpHeaders.AUTHORIZATION, person.bearer()));
    }

    private ResultActions off(Staff person, boolean done, String reason) throws Exception {
        return mvc.perform(post("/api/remote/off").contentType(MediaType.APPLICATION_JSON)
                .content("{\"done\":" + done + ",\"reason\":" + (reason == null ? "null" : reason) + "}")
                .header(HttpHeaders.AUTHORIZATION, person.bearer()));
    }

    private ResultActions review(int dayId, String fee) throws Exception {
        return mvc.perform(post("/api/remote/days/" + dayId + "/review").contentType(MediaType.APPLICATION_JSON)
                .content("{\"lateFee\":" + fee + "}").header(HttpHeaders.AUTHORIZATION, admin));
    }

    private ResultActions session(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, long userId,
            String date, String on, String off) throws Exception {
        return session(request, userId, date, on, off, admin);
    }

    private ResultActions session(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, long userId,
            String date, String on, String off, String by) throws Exception {
        return mvc.perform(request.contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":" + userId + ",\"date\":\"" + date + "\",\"on\":\"" + on + "\",\"off\":\"" + off
                        + "\",\"note\":\"Fixed by hand\"}")
                .header(HttpHeaders.AUTHORIZATION, by));
    }

    private static LocalDate today() {
        return LocalDate.now(DHAKA);
    }
}
