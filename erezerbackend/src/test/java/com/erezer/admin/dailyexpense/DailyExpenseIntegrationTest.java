package com.erezer.admin.dailyexpense;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
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
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

class DailyExpenseIntegrationTest extends IntegrationTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D};

    private record Person(long id, String bearer) {
    }

    @MockitoBean
    StorageService storage;

    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        when(storage.presignedGetUrl(anyString())).thenReturn(URI.create("http://storage.test/bill").toURL());
        admin = adminBearer();
    }

    @Test
    void anApprovalWorksOutTheDueAndTheCardsFollow() throws Exception {
        Person employee = person(Role.EMPLOYEE, Permission.DAILY_EXPENSE_CREATE);
        long category = category("Tea " + UUID.randomUUID());

        long first = id(create(employee, category, null, "1000", "Snacks for the team", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.due").doesNotExist()));
        long second = id(create(employee, category, null, "500", null, null).andExpect(status().isCreated()));
        create(employee, category, null, "200", null, null).andExpect(status().isCreated());

        approve(first, "600").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.paid").value(600))
                .andExpect(jsonPath("$.due").value(400));
        approve(second, "500").andExpect(status().isOk()).andExpect(jsonPath("$.due").value(0));

        mvc.perform(get("/api/daily-expenses/summary").param("userId", String.valueOf(employee.id())).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.approvedCount").value(2))
                .andExpect(jsonPath("$.approved").value(1500))
                .andExpect(jsonPath("$.paid").value(1100))
                .andExpect(jsonPath("$.due").value(400))
                .andExpect(jsonPath("$.pendingCount").value(1))
                .andExpect(jsonPath("$.pending").value(200));
    }

    @Test
    void paidCantBeMoreThanTheExpenseAndCanBeRaisedLater() throws Exception {
        Person employee = person(Role.EMPLOYEE, Permission.DAILY_EXPENSE_CREATE);
        long id = id(create(employee, category("Fuel " + UUID.randomUUID()), null, "300", null, null));

        approve(id, "301").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PAID_TOO_MUCH"));
        approve(id, "100").andExpect(status().isOk());
        approve(id, "100").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_DECIDED"));

        paid(id, "300").andExpect(status().isOk()).andExpect(jsonPath("$.due").value(0));
        paid(id, "301").andExpect(status().isBadRequest());
    }

    @Test
    void aRejectionNeedsAReasonAndShowsIt() throws Exception {
        Person employee = person(Role.MODERATOR, Permission.DAILY_EXPENSE_CREATE);
        long id = id(create(employee, null, "Rickshaw", "80", null, null)
                .andExpect(jsonPath("$.category").value("Rickshaw"))
                .andExpect(jsonPath("$.other").value(true)));

        reject(id, " ").andExpect(status().isBadRequest());
        reject(id, "No receipt").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value("No receipt"))
                .andExpect(jsonPath("$.due").doesNotExist());

        paid(id, "10").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_APPROVED"));
    }

    @Test
    void theFormChecksCategoryOtherAndDate() throws Exception {
        Person employee = person(Role.EMPLOYEE, Permission.DAILY_EXPENSE_CREATE);
        create(employee, null, null, "80", null, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OTHER_REQUIRED"));
        create(employee, null, "Tip", "0", null, null).andExpect(status().isBadRequest());
        create(employee, 999_999L, null, "80", null, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CATEGORY"));

        String tomorrow = LocalDate.now(ZoneId.of("Asia/Dhaka")).plusDays(1).toString();
        send(employee, body(tomorrow, null, "Tip", "80", null, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FUTURE_DATE"));

        long off = category("Off " + UUID.randomUUID());
        mvc.perform(put("/api/daily-expense-categories/" + off).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Off " + UUID.randomUUID() + "\",\"active\":false}").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());
        create(employee, off, null, "80", null, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CATEGORY"));
    }

    @Test
    void peopleSeeTheirOwnUnlessTheyMaySeeEveryones() throws Exception {
        Person mine = person(Role.EMPLOYEE, Permission.DAILY_EXPENSE_CREATE);
        Person other = person(Role.EMPLOYEE, Permission.DAILY_EXPENSE_CREATE);
        long mineId = id(create(mine, null, "Mine", "10", null, null));
        long theirs = id(create(other, null, "Theirs", "20", null, null));

        mvc.perform(get("/api/daily-expenses").param("size", "100").param("userId", String.valueOf(other.id()))
                        .header(HttpHeaders.AUTHORIZATION, mine.bearer()))
                .andExpect(jsonPath("$.content[*].id").value(hasItem((int) mineId)))
                .andExpect(jsonPath("$.content[*].id").value(not(hasItem((int) theirs))));

        String viewer = bearer(Role.MODERATOR, Permission.DAILY_EXPENSE_VIEW);
        mvc.perform(get("/api/daily-expenses").param("size", "100").param("userId", String.valueOf(other.id()))
                        .header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(jsonPath("$.content[*].id").value(hasItem((int) theirs)));
        create(new Person(0, viewer), null, "Nope", "10", null, null).andExpect(status().isForbidden());
    }

    @Test
    void theListIsPagedAndFiltered() throws Exception {
        Person employee = person(Role.EMPLOYEE, Permission.DAILY_EXPENSE_CREATE);
        for (int i = 0; i < 3; i++) {
            create(employee, null, "Paged", "10", null, null).andExpect(status().isCreated());
        }
        long approved = id(create(employee, null, "Kept", "10", null, null));
        approve(approved, "10").andExpect(status().isOk());

        mvc.perform(get("/api/daily-expenses").param("userId", String.valueOf(employee.id())).param("size", "2").param("page", "1")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content.length()").value(2));
        mvc.perform(get("/api/daily-expenses").param("userId", String.valueOf(employee.id())).param("status", "APPROVED")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(approved));
        mvc.perform(get("/api/daily-expenses").param("userId", String.valueOf(employee.id())).param("q", "paged").param("categoryId", "0")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void nobodyDecidesTheirOwnAndOnlyPendingCanBeTakenBack() throws Exception {
        Person both = person(Role.MODERATOR, Permission.DAILY_EXPENSE_CREATE, Permission.DAILY_EXPENSE_APPROVE);
        long own = id(create(both, null, "Own", "10", null, null));
        mvc.perform(post("/api/daily-expenses/" + own + "/approve").contentType(MediaType.APPLICATION_JSON).content("{\"paid\":10}")
                        .header(HttpHeaders.AUTHORIZATION, both.bearer()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OWN_EXPENSE"));

        Person other = person(Role.EMPLOYEE, Permission.DAILY_EXPENSE_CREATE);
        mvc.perform(delete("/api/daily-expenses/" + own).header(HttpHeaders.AUTHORIZATION, other.bearer()))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/daily-expenses/" + own).header(HttpHeaders.AUTHORIZATION, both.bearer()))
                .andExpect(status().isNoContent());

        long decided = id(create(other, null, "Decided", "10", null, null));
        approve(decided, "10").andExpect(status().isOk());
        mvc.perform(delete("/api/daily-expenses/" + decided).header(HttpHeaders.AUTHORIZATION, other.bearer()))
                .andExpect(status().isConflict());
    }

    @Test
    void anAdminCanEditAnyExpense() throws Exception {
        Person employee = person(Role.EMPLOYEE, Permission.DAILY_EXPENSE_CREATE);
        long category = category("Edit " + UUID.randomUUID());
        MvcResult saved = create(employee, category, null, "1000", "First", null).andExpect(status().isCreated()).andReturn();
        long id = ((Number) read(saved, "$.id")).longValue();

        approve(id, "400").andExpect(status().isOk());
        Number version = currentVersion(id, employee);
        edit(id, body(today(), null, "Rickshaw", "900", "Changed", null), version).andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(900))
                .andExpect(jsonPath("$.category").value("Rickshaw"))
                .andExpect(jsonPath("$.other").value(true))
                .andExpect(jsonPath("$.details").value("Changed"))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.paid").value(400))
                .andExpect(jsonPath("$.due").value(500));

        edit(id, body(today(), null, "Rickshaw", "300", null, null), currentVersion(id, employee)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AMOUNT_BELOW_PAID"));
        edit(id, body(today(), null, "Rickshaw", "900", null, null), version).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_DATA"));
    }

    @Test
    void anAdminCanDeleteAnExpenseEvenAfterItWasDecided() throws Exception {
        Person employee = person(Role.EMPLOYEE, Permission.DAILY_EXPENSE_CREATE);
        long id = id(create(employee, null, "Tea", "100", null, null));
        approve(id, "100").andExpect(status().isOk());

        mvc.perform(delete("/api/daily-expenses/" + id).header(HttpHeaders.AUTHORIZATION, admin)).andExpect(status().isNoContent());
        mvc.perform(get("/api/daily-expenses/summary").param("userId", String.valueOf(employee.id())).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.approved").value(0));
    }

    @Test
    void editingAndDeletingNeedTheirPermissions() throws Exception {
        Person employee = person(Role.EMPLOYEE, Permission.DAILY_EXPENSE_CREATE);
        long id = id(create(employee, null, "Tea", "100", null, null));

        Person viewer = person(Role.MODERATOR, Permission.DAILY_EXPENSE_VIEW);
        mvc.perform(put("/api/daily-expenses/" + id).contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, viewer.bearer())
                .content(body(today(), null, "Tea", "90", null, null))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/daily-expenses/" + id).header(HttpHeaders.AUTHORIZATION, viewer.bearer())).andExpect(status().isForbidden());

        Person editor = person(Role.MODERATOR, Permission.DAILY_EXPENSE_EDIT, Permission.DAILY_EXPENSE_CREATE);
        long own = id(create(editor, null, "Mine", "100", null, null));
        mvc.perform(put("/api/daily-expenses/" + own).contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, editor.bearer())
                .content(body(today(), null, "Mine", "90", null, null).replace("}", ",\"version\":0}")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OWN_EXPENSE"));
    }

    private ResultActions edit(long id, String body, Number version) throws Exception {
        return mvc.perform(put("/api/daily-expenses/" + id).contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, admin)
                .content(body.substring(0, body.length() - 1) + ",\"version\":" + version + "}"));
    }

    private Number currentVersion(long id, Person owner) throws Exception {
        MvcResult list = mvc.perform(get("/api/daily-expenses").param("size", "100").param("userId", String.valueOf(owner.id()))
                .header(HttpHeaders.AUTHORIZATION, admin)).andReturn();
        List<Number> versions = read(list, "$.content[?(@.id == " + id + ")].version");
        return versions.getFirst();
    }

    @Test
    void adminsApproveButDontApply() throws Exception {
        send(new Person(0, admin), body(today(), null, "Tea", "10", null, null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADMIN_NO_DAILY_EXPENSE"));
    }

    @Test
    void categoriesNeedTheirOwnPermission() throws Exception {
        String employee = bearer(Role.EMPLOYEE, Permission.DAILY_EXPENSE_CREATE);
        mvc.perform(post("/api/daily-expense-categories").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Nope\"}")
                .header(HttpHeaders.AUTHORIZATION, employee)).andExpect(status().isForbidden());
        mvc.perform(get("/api/daily-expense-categories").header(HttpHeaders.AUTHORIZATION, employee)).andExpect(status().isOk());

        String manager = bearer(Role.MODERATOR, Permission.DAILY_EXPENSE_CATEGORIES);
        String name = "Managed " + UUID.randomUUID();
        mvc.perform(post("/api/daily-expense-categories").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}")
                .header(HttpHeaders.AUTHORIZATION, manager)).andExpect(status().isCreated());
        mvc.perform(post("/api/daily-expense-categories").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name.toUpperCase() + "\"}")
                .header(HttpHeaders.AUTHORIZATION, manager)).andExpect(status().isConflict());
        long used = categoryNamed(name);

        Person spender = person(Role.EMPLOYEE, Permission.DAILY_EXPENSE_CREATE);
        create(spender, used, null, "10", null, null).andExpect(status().isCreated());
        mvc.perform(delete("/api/daily-expense-categories/" + used).header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CATEGORY_IN_USE"));
    }

    @Test
    void aReceiptMustBeThePersonsOwn() throws Exception {
        Person employee = person(Role.EMPLOYEE, Permission.DAILY_EXPENSE_CREATE);
        Person other = person(Role.EMPLOYEE, Permission.DAILY_EXPENSE_CREATE);
        create(employee, null, "Bill", "10", null, receipt(employee)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.receipt.url").value("http://storage.test/bill"));
        create(employee, null, "Bill", "10", null, receipt(other)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_NOT_YOURS"));
        mvc.perform(multipart("/api/files/daily-expense-receipts").file(new MockMultipartFile("file", "a.png", "image/png", PNG))
                .header(HttpHeaders.AUTHORIZATION, bearer(Role.EMPLOYEE))).andExpect(status().isForbidden());
    }

    @Test
    void approvalNeedsThePermission() throws Exception {
        Person employee = person(Role.EMPLOYEE, Permission.DAILY_EXPENSE_CREATE);
        long id = id(create(employee, null, "Tea", "10", null, null));
        mvc.perform(post("/api/daily-expenses/" + id + "/approve").contentType(MediaType.APPLICATION_JSON).content("{\"paid\":10}")
                .header(HttpHeaders.AUTHORIZATION, employee.bearer())).andExpect(status().isForbidden());
        mvc.perform(get("/api/daily-expenses/people").header(HttpHeaders.AUTHORIZATION, employee.bearer())).andExpect(status().isForbidden());
        mvc.perform(get("/api/daily-expenses").header(HttpHeaders.AUTHORIZATION, bearer(Role.EMPLOYEE))).andExpect(status().isForbidden());
    }

    private Person person(Role role, Permission... permissions) throws Exception {
        String email = createUser(role, List.of(permissions));
        MvcResult login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return new Person(((Number) read(login, "$.user.id")).longValue(), "Bearer " + read(login, "$.accessToken"));
    }

    private static String today() {
        return LocalDate.now(ZoneId.of("Asia/Dhaka")).toString();
    }

    private static String body(String date, Long category, String other, String amount, String details, Long receipt) {
        return "{\"date\":\"" + date + "\",\"categoryId\":" + category + ",\"otherCategory\":" + (other == null ? "null" : "\"" + other + "\"")
                + ",\"amount\":" + amount + ",\"details\":" + (details == null ? "null" : "\"" + details + "\"")
                + ",\"receiptFileId\":" + receipt + "}";
    }

    private ResultActions create(Person person, Long category, String other, String amount, String details, Long receipt) throws Exception {
        return send(person, body(today(), category, other, amount, details, receipt));
    }

    private ResultActions send(Person person, String body) throws Exception {
        return mvc.perform(post("/api/daily-expenses").contentType(MediaType.APPLICATION_JSON).content(body)
                .header(HttpHeaders.AUTHORIZATION, person.bearer()));
    }

    private long id(ResultActions created) throws Exception {
        return ((Number) read(created.andReturn(), "$.id")).longValue();
    }

    private long category(String name) throws Exception {
        mvc.perform(post("/api/daily-expense-categories").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}")
                .header(HttpHeaders.AUTHORIZATION, admin)).andExpect(status().isCreated());
        return categoryNamed(name);
    }

    private long categoryNamed(String name) throws Exception {
        MvcResult list = mvc.perform(get("/api/daily-expense-categories").header(HttpHeaders.AUTHORIZATION, admin)).andReturn();
        List<Number> ids = read(list, "$[?(@.name == '" + name + "')].id");
        return ids.getFirst().longValue();
    }

    private long receipt(Person person) throws Exception {
        MvcResult upload = mvc.perform(multipart("/api/files/daily-expense-receipts").file(new MockMultipartFile("file", "a.png", "image/png", PNG))
                        .header(HttpHeaders.AUTHORIZATION, person.bearer()))
                .andExpect(status().isOk()).andReturn();
        return ((Number) read(upload, "$.id")).longValue();
    }

    private ResultActions approve(long id, String paid) throws Exception {
        return mvc.perform(post("/api/daily-expenses/" + id + "/approve").contentType(MediaType.APPLICATION_JSON)
                .content("{\"paid\":" + paid + "}").header(HttpHeaders.AUTHORIZATION, admin));
    }

    private ResultActions paid(long id, String paid) throws Exception {
        return mvc.perform(post("/api/daily-expenses/" + id + "/paid").contentType(MediaType.APPLICATION_JSON)
                .content("{\"paid\":" + paid + "}").header(HttpHeaders.AUTHORIZATION, admin));
    }

    private ResultActions reject(long id, String reason) throws Exception {
        return mvc.perform(post("/api/daily-expenses/" + id + "/reject").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"" + reason + "\"}").header(HttpHeaders.AUTHORIZATION, admin));
    }
}
