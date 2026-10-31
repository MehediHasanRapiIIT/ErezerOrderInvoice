package com.erezer.admin.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
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
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The clock is the real one, so shifts are chosen to make any time of day late or on time: 00:00 to 00:01 makes every
 * check-in late and no check-out early, and 23:58 to 23:59 the opposite. Tests skip in the few minutes around midnight.
 */
class AttendanceIntegrationTest extends IntegrationTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D};
    private static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");
    private static final String ALL_LATE = "\"00:00\",\"00:01\"";
    private static final String NEVER_LATE = "\"23:58\",\"23:59\"";
    /** Spreads each test's location apart: locations stay in the shared database. */
    private static final AtomicInteger NEXT_LATITUDE = new AtomicInteger(-80);

    private record Staff(long id, String bearer) {
    }

    @MockitoBean
    StorageService storage;

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbc;

    private String admin;
    private double latitude;
    private final double longitude = 90.4;

    @BeforeEach
    void setUp() throws Exception {
        LocalTime now = LocalTime.now(DHAKA);
        assumeTrue(now.isAfter(LocalTime.of(0, 5)) && now.isBefore(LocalTime.of(23, 50)));
        when(storage.presignedGetUrl(anyString())).thenReturn(URI.create("http://storage.test/selfie").toURL());
        jdbc.sql("delete from attendance_special_hours").update();
        admin = adminBearer();
        latitude = NEXT_LATITUDE.addAndGet(2);
        createLocation("Place " + UUID.randomUUID(), latitude, longitude).andExpect(status().isOk());
    }

    @Test
    void onTimeAtALocationNeedsNoReasonAndLeavingEarlyDoes() throws Exception {
        Staff employee = employee(NEVER_LATE, "[]");
        long photo = selfie(employee);

        checkIn(employee, inRange(photo, null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.lateMinutes").value(0))
                .andExpect(jsonPath("$.checkInInRange").value(true))
                .andExpect(jsonPath("$.checkInDistanceM").value(0))
                .andExpect(jsonPath("$.checkInLocation").value(containsString("Place")))
                .andExpect(jsonPath("$.photoUrl").value("http://storage.test/selfie"))
                .andExpect(jsonPath("$.reviewStatus").value("NONE"));
        checkIn(employee, inRange(selfie(employee), null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_CHECKED_IN"));

        checkOut(employee, "null").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REASON_REQUIRED"))
                .andExpect(jsonPath("$.detail").value(containsString("early")));
        checkOut(employee, "\"Doctor\"").andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("PENDING"))
                .andExpect(jsonPath("$.checkOutReason").value("Doctor"));
        checkOut(employee, "\"Again\"").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_CHECKED_OUT"));
    }

    @Test
    void lateNeedsAReasonAndTheAdminCanAddAFee() throws Exception {
        Staff employee = employee(ALL_LATE, "[]");
        long photo = selfie(employee);

        checkIn(employee, inRange(photo, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REASON_REQUIRED"))
                .andExpect(jsonPath("$.detail").value(containsString("late")));
        MvcResult result = checkIn(employee, inRange(photo, "Traffic")).andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("PENDING"))
                .andExpect(jsonPath("$.checkInReason").value("Traffic"))
                .andReturn();
        int id = read(result, "$.id");
        assertThat((Integer) read(result, "$.lateMinutes")).isPositive();

        mvc.perform(get("/api/attendance/pending").param("size", "100").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.content[*].id").value(hasItem(id)));
        // Leaving after the shift's end needs nothing more.
        checkOut(employee, "null").andExpect(status().isOk());

        review(id, "150", "Second time").andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("APPROVED"))
                .andExpect(jsonPath("$.lateFee").value(150))
                .andExpect(jsonPath("$.reviewNote").value("Second time"));

        mvc.perform(get("/api/attendance/pending").param("size", "100").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.content[*].id").value(org.hamcrest.Matchers.not(hasItem(id))));
        mvc.perform(get("/api/attendance/month").param("month", YearMonth.now(DHAKA).toString()).param("size", "100")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.rows[?(@.userId == " + employee.id() + ")].lateFees").value(hasItem(150.0)))
                .andExpect(jsonPath("$.rows[?(@.userId == " + employee.id() + ")].late").value(hasItem(1)));
        mvc.perform(get("/api/attendance/day").param("date", today().toString()).param("size", "100").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.content[?(@.userId == " + employee.id() + ")].status").value(hasItem("LATE")))
                .andExpect(jsonPath("$.content[?(@.userId == " + employee.id() + ")].record.photoUrl")
                        .value(hasItem("http://storage.test/selfie")));
    }

    @Test
    void leavingEarlyAfterAnApprovedLateArrivalGoesBackForReviewAndKeepsTheFee() throws Exception {
        Staff employee = employee("\"00:00\",\"23:59\"", "[]");
        int id = read(checkIn(employee, inRange(selfie(employee), "Bus")).andExpect(status().isOk()).andReturn(), "$.id");
        review(id, "100", null).andExpect(status().isOk());

        checkOut(employee, "\"Sick\"").andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("PENDING"))
                .andExpect(jsonPath("$.lateFee").value(100));
    }

    @Test
    void awayFromEveryLocationOrWithoutAPositionNeedsAReason() throws Exception {
        Staff away = employee(NEVER_LATE, "[]");
        String far = "{\"latitude\":" + (latitude + 0.5) + ",\"longitude\":" + longitude + ",\"photoFileId\":" + selfie(away)
                + ",\"reason\":%s}";
        checkIn(away, far.formatted("null")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("not at any work location")));
        checkIn(away, far.formatted("\"Delivering stock\"")).andExpect(status().isOk())
                .andExpect(jsonPath("$.checkInInRange").value(false))
                .andExpect(jsonPath("$.reviewStatus").value("PENDING"));

        Staff noGps = employee(NEVER_LATE, "[]");
        String none = "{\"photoFileId\":" + selfie(noGps) + ",\"reason\":%s}";
        checkIn(noGps, none.formatted("null")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("could not be read")));
        checkIn(noGps, none.formatted("\"Phone GPS is broken\"")).andExpect(status().isOk())
                .andExpect(jsonPath("$.checkInLatitude").doesNotExist());
    }

    @Test
    void aDayOffIsNotLateAndNotAbsent() throws Exception {
        String today = today().getDayOfWeek().name();
        Staff working = employee(ALL_LATE, "[\"" + today + "\",\"" + today().plusDays(1).getDayOfWeek() + "\"]");
        Staff resting = employee(ALL_LATE, "[\"" + today + "\"]");

        mvc.perform(get("/api/attendance/me").header(HttpHeaders.AUTHORIZATION, working.bearer()))
                .andExpect(jsonPath("$.weeklyOff").value(true))
                .andExpect(jsonPath("$.shift.weeklyOff.length()").value(2));
        checkIn(working, inRange(selfie(working), null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.lateMinutes").value(0))
                .andExpect(jsonPath("$.reviewStatus").value("NONE"));
        mvc.perform(get("/api/attendance/day").param("date", today().toString()).param("size", "100").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.content[?(@.userId == " + resting.id() + ")].status").value(hasItem("OFF")));
    }

    @Test
    void onlyModeratorsWithAShiftCanCheckInWithTheirOwnSelfie() throws Exception {
        checkIn(new Staff(0, admin), "{\"photoFileId\":1}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADMIN_NO_ATTENDANCE"));

        Staff noShift = staff(Role.MODERATOR, Permission.ATTENDANCE_MARK);
        checkIn(noShift, inRange(selfie(noShift), null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_SHIFT"));

        Staff employee = employee(NEVER_LATE, "[]");
        Staff other = employee(NEVER_LATE, "[]");
        checkIn(employee, inRange(selfie(other), null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_NOT_YOURS"));

        Staff uploader = staff(Role.MODERATOR, Permission.ATTENDANCE_MARK, Permission.ORDER_CREATE);
        setShift(uploader.id(), NEVER_LATE, "[]").andExpect(status().isNoContent());
        MvcResult image = mvc.perform(multipart("/api/files/images")
                        .file(new MockMultipartFile("files", "a.png", "image/png", PNG))
                        .header(HttpHeaders.AUTHORIZATION, uploader.bearer()))
                .andExpect(status().isOk()).andReturn();
        int imageId = read(image, "$[0].id");
        checkIn(uploader, inRange(imageId, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IMAGE"));
    }

    @Test
    void permissionsGuardEveryScreen() throws Exception {
        Staff none = staff(Role.MODERATOR);
        mvc.perform(get("/api/attendance/me").header(HttpHeaders.AUTHORIZATION, none.bearer())).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/files/selfies").file(new MockMultipartFile("file", "a.png", "image/png", PNG))
                .header(HttpHeaders.AUTHORIZATION, none.bearer())).andExpect(status().isForbidden());

        Staff marker = staff(Role.MODERATOR, Permission.ATTENDANCE_MARK);
        mvc.perform(get("/api/attendance/day").param("date", today().toString()).header(HttpHeaders.AUTHORIZATION, marker.bearer()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/attendance/shifts").header(HttpHeaders.AUTHORIZATION, marker.bearer())).andExpect(status().isForbidden());

        Staff approver = staff(Role.MODERATOR, Permission.ATTENDANCE_APPROVE);
        mvc.perform(get("/api/attendance/day").param("date", today().toString()).header(HttpHeaders.AUTHORIZATION, approver.bearer()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/attendance/locations").header(HttpHeaders.AUTHORIZATION, approver.bearer())).andExpect(status().isForbidden());
    }

    @Test
    void anAdminCorrectsADayByHandButNobodyTheirOwn() throws Exception {
        Staff employee = employee("\"10:00\",\"18:00\"", "[]");
        String date = today().minusDays(3).toString();

        correct(admin, employee.id(), date, "\"10:30\"", "\"17:00\"", "Forgot the phone").andExpect(status().isNoContent());
        mvc.perform(get("/api/attendance/day").param("date", date).param("size", "100").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.content[?(@.userId == " + employee.id() + ")].status").value(hasItem("LATE_EARLY")))
                .andExpect(jsonPath("$.content[?(@.userId == " + employee.id() + ")].record.lateMinutes").value(hasItem(30)))
                .andExpect(jsonPath("$.content[?(@.userId == " + employee.id() + ")].record.earlyMinutes").value(hasItem(60)))
                .andExpect(jsonPath("$.content[?(@.userId == " + employee.id() + ")].record.editNote").value(hasItem("Forgot the phone")));

        correct(admin, employee.id(), date, "\"17:00\"", "\"10:00\"", "Wrong way").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TIMES"));

        correct(admin, employee.id(), date, "null", "null", "Was not here").andExpect(status().isNoContent());
        mvc.perform(get("/api/attendance/day").param("date", date).param("size", "100").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.content[?(@.userId == " + employee.id() + ")].status").value(hasItem("NONE")))
                .andExpect(jsonPath("$.content[?(@.userId == " + employee.id() + ")].record").isEmpty());

        Staff editor = staff(Role.MODERATOR, Permission.ATTENDANCE_EDIT);
        correct(editor.bearer(), editor.id(), date, "\"10:00\"", "\"18:00\"", "Mine").andExpect(status().isForbidden());
    }

    @Test
    void shiftsAreForModeratorsAndEndAfterTheyStart() throws Exception {
        Staff boss = staff(Role.ADMIN);
        setShift(boss.id(), NEVER_LATE, "[]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ADMIN_NO_ATTENDANCE"));

        Staff employee = staff(Role.MODERATOR);
        setShift(employee.id(), "\"18:00\",\"09:00\"", "[]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TIMES"));

        setShift(employee.id(), "\"09:00\",\"18:00\"", "[\"FRIDAY\",\"SATURDAY\"]").andExpect(status().isNoContent());
        mvc.perform(get("/api/attendance/shifts").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$[?(@.userId == " + employee.id() + ")].checkIn").value(hasItem("09:00:00")))
                .andExpect(jsonPath("$[?(@.userId == " + employee.id() + ")].weeklyOff[*]").value(hasItem("SATURDAY")));
    }

    @Test
    void locationsAreManagedByTheAdmin() throws Exception {
        String name = "Depot " + UUID.randomUUID();
        createLocation(name, 10.0, 20.0).andExpect(status().isOk());
        createLocation(name.toUpperCase(), 11.0, 21.0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOCATION_EXISTS"));
        mvc.perform(post("/api/attendance/locations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Half\",\"latitude\":10,\"radiusM\":100}")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isBadRequest());

        MvcResult list = mvc.perform(get("/api/attendance/locations").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$[*].name").value(hasItem("Shop")))
                .andExpect(jsonPath("$[*].name").value(hasItem("Warehouse")))
                .andReturn();
        List<Integer> ids = read(list, "$[?(@.name == '" + name + "')].id");
        mvc.perform(delete("/api/attendance/locations/" + ids.getFirst()).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk());

        Staff employee = employee(NEVER_LATE, "[]");
        int used = read(checkIn(employee, inRange(selfie(employee), null)).andExpect(status().isOk()).andReturn(), "$.id");
        assertThat(used).isPositive();
        MvcResult after = mvc.perform(get("/api/attendance/locations").header(HttpHeaders.AUTHORIZATION, admin)).andReturn();
        List<Integer> usedIds = read(after, "$[?(@.used == true && @.latitude == " + latitude + ")].id");
        mvc.perform(delete("/api/attendance/locations/" + usedIds.getFirst()).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOCATION_IN_USE"));
    }

    @Test
    void listsArePagedAndTheCardsFollowTheChosenEmployee() throws Exception {
        Staff first = employee("\"10:00\",\"18:00\"", "[]");
        Staff second = employee("\"10:00\",\"18:00\"", "[]");
        LocalDate date = today().minusDays(3);
        String month = YearMonth.from(date).toString();
        correct(admin, first.id(), date.toString(), "\"10:30\"", "\"17:00\"", "Hand entry").andExpect(status().isNoContent());
        correct(admin, second.id(), date.toString(), "\"10:00\"", "\"18:00\"", "Hand entry").andExpect(status().isNoContent());
        correct(admin, second.id(), date.minusDays(1).toString(), "\"10:00\"", "null", "No check-out").andExpect(status().isNoContent());

        mvc.perform(get("/api/attendance/summary").param("month", month).param("userId", String.valueOf(first.id()))
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.workedMinutes").value(390))
                .andExpect(jsonPath("$.lateFees").value(0));
        mvc.perform(get("/api/attendance/month").param("month", month).param("userId", String.valueOf(first.id()))
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].workedMinutes").value(390))
                .andExpect(jsonPath("$.totalElements").value(1));

        mvc.perform(get("/api/attendance/day").param("date", today().toString()).param("page", "1").param("size", "1")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(1));
        mvc.perform(get("/api/attendance/month").param("month", month).param("page", "0").param("size", "1")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.rows.length()").value(1));

        // Before the shift began, days aren't listed unless a record exists; the day without a check-out adds no hours.
        mvc.perform(get("/api/attendance/employees/" + second.id() + "/days").param("month", month).param("size", "31")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.content[?(@.date == '" + date + "')].record.workedMinutes").value(hasItem(480)));
        mvc.perform(get("/api/attendance/employees/" + second.id() + "/days").param("month", month).param("size", "1")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.content.length()").value(1));
        mvc.perform(get("/api/attendance/summary").param("month", month).param("userId", String.valueOf(second.id()))
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.workedMinutes").value(480));

        Staff late = employee("\"00:00\",\"23:59\"", "[]");
        int id = read(checkIn(late, inRange(selfie(late), "Bus")).andExpect(status().isOk()).andReturn(), "$.id");
        mvc.perform(get("/api/attendance/pending").param("userId", String.valueOf(late.id())).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id))
                .andExpect(jsonPath("$.totalElements").value(1));
        review(id, "75.50", null).andExpect(status().isOk());
        mvc.perform(get("/api/attendance/summary").param("month", YearMonth.now(DHAKA).toString()).param("userId", String.valueOf(late.id()))
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.workedMinutes").value(0))
                .andExpect(jsonPath("$.lateFees").value(75.5));
        mvc.perform(get("/api/attendance/employees").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$[*].userId").value(hasItem((int) late.id())));
    }

    @Test
    void theEmployeeRoleGivesAttendanceWhenAnAdminAllowsIt() throws Exception {
        MvcResult created = mvc.perform(post("/api/staff").contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, admin)
                        .content("{\"fullName\":\"Rahim\",\"email\":\"e-" + UUID.randomUUID() + "@test.local\",\"role\":\"EMPLOYEE\","
                                + "\"password\":\"" + PASSWORD + "\",\"permissions\":[\"ATTENDANCE_MARK\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("EMPLOYEE"))
                .andExpect(jsonPath("$.permissions").value(hasItem("ATTENDANCE_MARK")))
                .andReturn();
        assertThat((Number) read(created, "$.id")).isNotNull();

        Staff allowed = staff(Role.EMPLOYEE, Permission.ATTENDANCE_MARK);
        setShift(allowed.id(), NEVER_LATE, "[]").andExpect(status().isNoContent());
        checkIn(allowed, inRange(selfie(allowed), null)).andExpect(status().isOk());

        Staff refused = staff(Role.EMPLOYEE);
        mvc.perform(get("/api/attendance/me").header(HttpHeaders.AUTHORIZATION, refused.bearer())).andExpect(status().isForbidden());
    }

    @Test
    void savingHoursNeverGrantsThePermission() throws Exception {
        Staff worker = staff(Role.EMPLOYEE);
        setShift(worker.id(), NEVER_LATE, "[]").andExpect(status().isNoContent());
        mvc.perform(get("/api/attendance/shifts").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$[?(@.userId == " + worker.id() + ")].canCheckIn").value(hasItem(false)));
        mvc.perform(get("/api/attendance/me").header(HttpHeaders.AUTHORIZATION, worker.bearer())).andExpect(status().isForbidden());
    }

    @Test
    void aDateCanHaveItsOwnHours() throws Exception {
        Staff employee = employee(ALL_LATE, "[]");
        special("{\"userId\":" + employee.id() + ",\"dateFrom\":\"" + today() + "\",\"dayOff\":false,\"checkIn\":\"23:58\",\"checkOut\":\"23:59\",\"note\":\"Eid\"}")
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/attendance/me").header(HttpHeaders.AUTHORIZATION, employee.bearer()))
                .andExpect(jsonPath("$.todayCheckIn").value("23:58:00"))
                .andExpect(jsonPath("$.shift.checkIn").value("00:00:00"));
        checkIn(employee, inRange(selfie(employee), null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.lateMinutes").value(0))
                .andExpect(jsonPath("$.shiftStart").value("23:58:00"));
    }

    @Test
    void aWeekdayCanHaveItsOwnHoursAndADateBeatsIt() throws Exception {
        Staff employee = employee(NEVER_LATE, "[]");
        String weekday = today().getDayOfWeek().name();
        special("{\"userId\":" + employee.id() + ",\"weekDay\":\"" + weekday + "\",\"dayOff\":false,\"checkIn\":\"00:00\",\"checkOut\":\"00:01\"}")
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/attendance/me").header(HttpHeaders.AUTHORIZATION, employee.bearer()))
                .andExpect(jsonPath("$.todayCheckIn").value("00:00:00"));

        special("{\"userId\":" + employee.id() + ",\"dateFrom\":\"" + today() + "\",\"dayOff\":false,\"checkIn\":\"12:00\",\"checkOut\":\"13:00\"}")
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/attendance/me").header(HttpHeaders.AUTHORIZATION, employee.bearer()))
                .andExpect(jsonPath("$.todayCheckIn").value("12:00:00"));
    }

    @Test
    void aSettingForEveryoneGivesWayToSomeonesOwn() throws Exception {
        Staff general = employee(ALL_LATE, "[]");
        Staff own = employee(ALL_LATE, "[]");
        special("{\"dateFrom\":\"" + today() + "\",\"dayOff\":false,\"checkIn\":\"09:00\",\"checkOut\":\"15:00\"}").andExpect(status().isNoContent());
        special("{\"userId\":" + own.id() + ",\"dateFrom\":\"" + today() + "\",\"dayOff\":false,\"checkIn\":\"10:00\",\"checkOut\":\"16:00\"}").andExpect(status().isNoContent());

        mvc.perform(get("/api/attendance/me").header(HttpHeaders.AUTHORIZATION, general.bearer())).andExpect(jsonPath("$.todayCheckIn").value("09:00:00"));
        mvc.perform(get("/api/attendance/me").header(HttpHeaders.AUTHORIZATION, own.bearer())).andExpect(jsonPath("$.todayCheckIn").value("10:00:00"));
    }

    @Test
    void aDateRangeCanBeADayOffAndADateCanCallSomeoneInOnTheirDayOff() throws Exception {
        Staff resting = employee(ALL_LATE, "[]");
        special("{\"userId\":" + resting.id() + ",\"dateFrom\":\"" + today().minusDays(1) + "\",\"dateTo\":\"" + today().plusDays(1) + "\",\"dayOff\":true}")
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/attendance/day").param("date", today().toString()).param("size", "100").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.content[?(@.userId == " + resting.id() + ")].status").value(hasItem("OFF")));
        checkIn(resting, inRange(selfie(resting), null)).andExpect(status().isOk()).andExpect(jsonPath("$.lateMinutes").value(0));
        // Working on a day off is never "left early".
        checkOut(resting, "null").andExpect(status().isOk()).andExpect(jsonPath("$.earlyMinutes").value(0));

        Staff called = employee(NEVER_LATE, "[\"" + today().getDayOfWeek() + "\"]");
        special("{\"userId\":" + called.id() + ",\"dateFrom\":\"" + today() + "\",\"dayOff\":false,\"checkIn\":\"12:00\",\"checkOut\":\"13:00\"}")
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/attendance/me").header(HttpHeaders.AUTHORIZATION, called.bearer()))
                .andExpect(jsonPath("$.weeklyOff").value(false))
                .andExpect(jsonPath("$.todayCheckIn").value("12:00:00"));
    }

    @Test
    void specialHoursAreCheckedEditedAndDeleted() throws Exception {
        special("{\"dayOff\":true}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SPECIAL_DAY"));
        special("{\"dateFrom\":\"" + today() + "\",\"weekDay\":\"FRIDAY\",\"dayOff\":true}").andExpect(status().isBadRequest());
        special("{\"dateFrom\":\"" + today() + "\",\"dayOff\":false}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TIMES"));
        special("{\"dateFrom\":\"" + today() + "\",\"dayOff\":false,\"checkIn\":\"18:00\",\"checkOut\":\"09:00\"}").andExpect(status().isBadRequest());

        special("{\"weekDay\":\"FRIDAY\",\"dayOff\":false,\"checkIn\":\"09:00\",\"checkOut\":\"13:00\",\"note\":\"Short Friday\"}").andExpect(status().isNoContent());
        MvcResult list = mvc.perform(get("/api/attendance/special-hours").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$[0].note").value("Short Friday")).andReturn();
        int id = read(list, "$[0].id");
        mvc.perform(put("/api/attendance/special-hours/" + id).contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, admin)
                        .content("{\"weekDay\":\"SATURDAY\",\"dayOff\":true}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/attendance/special-hours").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$[0].weekDay").value("SATURDAY")).andExpect(jsonPath("$[0].dayOff").value(true));
        mvc.perform(delete("/api/attendance/special-hours/" + id).header(HttpHeaders.AUTHORIZATION, admin)).andExpect(status().isNoContent());

        String marker = staff(Role.MODERATOR, Permission.ATTENDANCE_MARK).bearer();
        mvc.perform(get("/api/attendance/special-hours").header(HttpHeaders.AUTHORIZATION, marker)).andExpect(status().isForbidden());
    }

    private ResultActions special(String body) throws Exception {
        return mvc.perform(post("/api/attendance/special-hours").contentType(MediaType.APPLICATION_JSON).content(body)
                .header(HttpHeaders.AUTHORIZATION, admin));
    }

    @Test
    void theMonthExportsToExcel() throws Exception {
        employee(NEVER_LATE, "[]");
        MvcResult file = mvc.perform(get("/api/attendance/month/export.xlsx").param("month", YearMonth.now(DHAKA).toString())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk()).andReturn();
        assertThat(file.getResponse().getContentAsByteArray()).startsWith((byte) 'P', (byte) 'K');
    }

    private static LocalDate today() {
        return LocalDate.now(DHAKA);
    }

    private Staff staff(Role role, Permission... permissions) throws Exception {
        String email = createUser(role, List.of(permissions));
        MvcResult login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        Number id = read(login, "$.user.id");
        return new Staff(id.longValue(), "Bearer " + read(login, "$.accessToken"));
    }

    private Staff employee(String times, String weeklyOff) throws Exception {
        Staff employee = staff(Role.MODERATOR, Permission.ATTENDANCE_MARK);
        setShift(employee.id(), times, weeklyOff).andExpect(status().isNoContent());
        return employee;
    }

    private ResultActions setShift(long userId, String times, String weeklyOff) throws Exception {
        String[] parts = times.split(",");
        return mvc.perform(put("/api/attendance/shifts/" + userId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"checkIn\":" + parts[0] + ",\"checkOut\":" + parts[1] + ",\"graceMinutes\":0,\"weeklyOff\":" + weeklyOff + "}")
                .header(HttpHeaders.AUTHORIZATION, admin));
    }

    private ResultActions createLocation(String name, double lat, double lng) throws Exception {
        return mvc.perform(post("/api/attendance/locations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"latitude\":" + lat + ",\"longitude\":" + lng + ",\"radiusM\":100}")
                .header(HttpHeaders.AUTHORIZATION, admin));
    }

    private long selfie(Staff employee) throws Exception {
        MvcResult upload = mvc.perform(multipart("/api/files/selfies")
                        .file(new MockMultipartFile("file", "selfie.png", "image/png", PNG))
                        .header(HttpHeaders.AUTHORIZATION, employee.bearer()))
                .andExpect(status().isOk()).andReturn();
        Number id = read(upload, "$.id");
        return id.longValue();
    }

    private String inRange(long photo, String reason) {
        return "{\"latitude\":" + latitude + ",\"longitude\":" + longitude + ",\"photoFileId\":" + photo
                + ",\"reason\":" + (reason == null ? "null" : "\"" + reason + "\"") + "}";
    }

    private ResultActions checkIn(Staff employee, String body) throws Exception {
        return mvc.perform(post("/api/attendance/check-in").contentType(MediaType.APPLICATION_JSON).content(body)
                .header(HttpHeaders.AUTHORIZATION, employee.bearer()));
    }

    private ResultActions checkOut(Staff employee, String reason) throws Exception {
        return mvc.perform(post("/api/attendance/check-out").contentType(MediaType.APPLICATION_JSON)
                .content("{\"latitude\":" + latitude + ",\"longitude\":" + longitude + ",\"reason\":" + reason + "}")
                .header(HttpHeaders.AUTHORIZATION, employee.bearer()));
    }

    private ResultActions review(int id, String fee, String note) throws Exception {
        return mvc.perform(post("/api/attendance/records/" + id + "/review").contentType(MediaType.APPLICATION_JSON)
                .content("{\"lateFee\":" + fee + ",\"note\":" + (note == null ? "null" : "\"" + note + "\"") + "}")
                .header(HttpHeaders.AUTHORIZATION, admin));
    }

    private ResultActions correct(String by, long userId, String date, String in, String out, String note) throws Exception {
        return mvc.perform(put("/api/attendance/day").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":" + userId + ",\"date\":\"" + date + "\",\"checkIn\":" + in + ",\"checkOut\":" + out
                        + ",\"note\":\"" + note + "\"}")
                .header(HttpHeaders.AUTHORIZATION, by));
    }
}
