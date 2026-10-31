package com.erezer.admin.employee;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
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
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

class EmployeeApiIntegrationTest extends IntegrationTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D};

    @MockitoBean
    StorageService storage;

    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        when(storage.presignedGetUrl(anyString())).thenReturn(URI.create("http://storage.test/doc").toURL());
        admin = adminBearer();
    }

    @Test
    void registersAnEmployeeWithEveryField() throws Exception {
        String code = code();
        String nid = nid();
        long photo = image(admin);
        long nidImage = image(admin);
        long idCard = image(admin);
        MvcResult created = save(admin, """
                {"employeeCode":"%s","fullName":" Rahim Uddin ","phone":"01711111111",
                 "fatherName":"Karim","fatherPhone":"01722222222","motherName":"Amina","motherPhone":"01733333333",
                 "presentAddress":"Mirpur, Dhaka","permanentAddress":"Barishal","nidNumber":"%s",
                 "photoFileId":%d,"nidImageFileId":%d,"idCardImageFileId":%d,
                 "salaryAccounts":[
                   {"type":"BANK","provider":"City Bank","accountName":"Rahim Uddin","accountNumber":"123456",
                    "branch":"Mirpur Branch","routingNumber":"225261732"},
                   {"type":"BKASH","accountNumber":"01711111111","branch":"Ignored","routingNumber":"1"}]}
                """.formatted(code, nid, photo, nidImage, idCard))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fullName").value("Rahim Uddin"))
                .andExpect(jsonPath("$.photo.fileId").value(photo))
                .andExpect(jsonPath("$.nidImage.url").value("http://storage.test/doc"))
                .andExpect(jsonPath("$.idCardImage.fileId").value(idCard))
                .andReturn();
        Number id = read(created, "$.id");

        mvc.perform(get("/api/employees/" + id).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fatherName").value("Karim"))
                .andExpect(jsonPath("$.motherPhone").value("01733333333"))
                .andExpect(jsonPath("$.salaryAccounts.length()").value(2))
                .andExpect(jsonPath("$.salaryAccounts[0].provider").value("City Bank"))
                .andExpect(jsonPath("$.salaryAccounts[0].branch").value("Mirpur Branch"))
                .andExpect(jsonPath("$.salaryAccounts[0].routingNumber").value("225261732"))
                .andExpect(jsonPath("$.salaryAccounts[1].branch").doesNotExist())
                .andExpect(jsonPath("$.salaryAccounts[1].routingNumber").doesNotExist())
                .andExpect(jsonPath("$.salaryAccounts[1].type").value("BKASH"));
    }

    @Test
    void everyStarredFieldIsRequired() throws Exception {
        long image = image(admin);
        String[] missingOne = {
            required("", "01711111111", "Dhaka", nid(), image),
            required("Rahim", "", "Dhaka", nid(), image),
            required("Rahim", "01711111111", "", nid(), image),
            required("Rahim", "01711111111", "Dhaka", "", image),
            required("Rahim", "01711111111", "Dhaka", "123", image),
        };
        for (String body : missingOne) {
            save(admin, body).andExpect(status().isBadRequest());
        }
        save(admin, """
                {"employeeCode":"%s","fullName":"Rahim","phone":"01711111111","presentAddress":"Dhaka","nidNumber":"%s",
                 "photoFileId":%d}
                """.formatted(code(), nid(), image)).andExpect(status().isBadRequest());
        save(admin, """
                {"employeeCode":"%s","fullName":"Rahim","phone":"01711111111","presentAddress":"Dhaka","nidNumber":"%s",
                 "nidImageFileId":%d}
                """.formatted(code(), nid(), image)).andExpect(status().isBadRequest());
        save(admin, """
                {"fullName":"Rahim","phone":"01711111111","presentAddress":"Dhaka","nidNumber":"%s","photoFileId":%d,
                 "nidImageFileId":%d}
                """.formatted(nid(), image, image)).andExpect(status().isBadRequest());
    }

    @Test
    void anEmployeeIdAndAnNidBelongToOnePerson() throws Exception {
        String code = code();
        String nid = nid();
        save(admin, required(code, "Rahim", nid, image(admin))).andExpect(status().isCreated());

        save(admin, required(code.toLowerCase(), "Karim", nid(), image(admin))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMPLOYEE_CODE_EXISTS"));
        save(admin, required(code(), "Karim", nid, image(admin))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NID_EXISTS"));
    }

    @Test
    void aBankAccountNeedsTheBanksName() throws Exception {
        String body = required(code(), "Rahim", nid(), image(admin));
        save(admin, body.replace("\"salaryAccounts\":[]", "\"salaryAccounts\":[{\"type\":\"BANK\",\"accountNumber\":\"1\"}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACCOUNT_PROVIDER_REQUIRED"));
    }

    @Test
    void theListIsPagedAndSearchable() throws Exception {
        String marker = UUID.randomUUID().toString().substring(0, 8);
        for (int i = 0; i < 3; i++) {
            save(admin, required(code(), "Paged " + marker + " " + i, nid(), image(admin))).andExpect(status().isCreated());
        }
        mvc.perform(get("/api/employees").param("q", marker.toUpperCase()).param("size", "2").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[0].fullName").value(startsWith("Paged")))
                .andExpect(jsonPath("$.content[0].photoUrl").value("http://storage.test/doc"));
        mvc.perform(get("/api/employees").param("q", marker).param("size", "2").param("page", "1").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].fullName").value("Paged " + marker + " 2"));
    }

    @Test
    void editingKeepsTheImagesAndChecksTheVersion() throws Exception {
        String code = code();
        String nid = nid();
        long image = image(admin);
        MvcResult created = save(admin, required(code, "Rahim", nid, image)).andExpect(status().isCreated()).andReturn();
        Number id = read(created, "$.id");
        Number version = read(created, "$.version");
        String editor = bearer(Role.MODERATOR, Permission.EMPLOYEE_EDIT);
        String body = required(code, "Rahim Uddin", nid, image);

        update(editor, id, body.replace("\"salaryAccounts\":[]", "\"salaryAccounts\":[],\"version\":" + version))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Rahim Uddin"))
                .andExpect(jsonPath("$.nidImage.fileId").value(image));
        update(editor, id, body.replace("\"salaryAccounts\":[]", "\"salaryAccounts\":[],\"version\":" + version))
                .andExpect(status().isConflict());
    }

    @Test
    void imagesMustBeTheUploadersOwnEmployeeImages() throws Exception {
        String other = bearer(Role.MODERATOR, Permission.EMPLOYEE_CREATE);
        save(admin, required(code(), "Rahim", nid(), image(other))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_NOT_YOURS"));

        MvcResult product = mvc.perform(multipart("/api/files/images")
                        .file(new MockMultipartFile("files", "a.png", "image/png", PNG))
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk()).andReturn();
        Number productImage = read(product, "$[0].id");
        save(admin, required(code(), "Rahim", nid(), productImage.longValue())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IMAGE"));

        mvc.perform(multipart("/api/files/employee-images")
                        .file(new MockMultipartFile("file", "evil.png", "image/png", "<script>".getBytes()))
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isBadRequest());
    }

    @Test
    void permissionsGuardTheRecords() throws Exception {
        Number id = read(save(admin, required(code(), "Rahim", nid(), image(admin))).andReturn(), "$.id");

        String none = bearer(Role.MODERATOR);
        mvc.perform(get("/api/employees").header(HttpHeaders.AUTHORIZATION, none)).andExpect(status().isForbidden());
        mvc.perform(get("/api/employees/" + id).header(HttpHeaders.AUTHORIZATION, none)).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/files/employee-images").file(new MockMultipartFile("file", "a.png", "image/png", PNG))
                .header(HttpHeaders.AUTHORIZATION, none)).andExpect(status().isForbidden());

        String viewer = bearer(Role.MODERATOR, Permission.EMPLOYEE_VIEW);
        mvc.perform(get("/api/employees/" + id).header(HttpHeaders.AUTHORIZATION, viewer)).andExpect(status().isOk());
        save(viewer, required(code(), "Karim", nid(), 1)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/employees/" + id).header(HttpHeaders.AUTHORIZATION, viewer)).andExpect(status().isForbidden());

        String creator = bearer(Role.MODERATOR, Permission.EMPLOYEE_CREATE);
        mvc.perform(get("/api/employees").header(HttpHeaders.AUTHORIZATION, creator)).andExpect(status().isOk());
    }

    @Test
    void deletingRemovesTheRecord() throws Exception {
        Number id = read(save(admin, required(code(), "Rahim", nid(), image(admin))).andReturn(), "$.id");
        mvc.perform(delete("/api/employees/" + id).header(HttpHeaders.AUTHORIZATION, admin)).andExpect(status().isNoContent());
        mvc.perform(get("/api/employees/" + id).header(HttpHeaders.AUTHORIZATION, admin)).andExpect(status().isNotFound());
        mvc.perform(get("/api/employees").param("size", "100").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.content[*].id").value(org.hamcrest.Matchers.not(hasItem(id.intValue()))));
    }

    private static String code() {
        return "EMP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private static String nid() {
        return String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
    }

    private static String required(String code, String name, String nid, long image) {
        return """
                {"employeeCode":"%s","fullName":"%s","phone":"01711111111","presentAddress":"Dhaka","nidNumber":"%s",
                 "photoFileId":%d,"nidImageFileId":%d,"salaryAccounts":[]}
                """.formatted(code, name, nid, image, image);
    }

    private static String required(String name, String phone, String address, String nid, long image) {
        return """
                {"employeeCode":"%s","fullName":"%s","phone":"%s","presentAddress":"%s","nidNumber":"%s",
                 "photoFileId":%d,"nidImageFileId":%d}
                """.formatted(code(), name, phone, address, nid, image, image);
    }

    private long image(String bearer) throws Exception {
        MvcResult upload = mvc.perform(multipart("/api/files/employee-images")
                        .file(new MockMultipartFile("file", "nid.png", "image/png", PNG))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk()).andReturn();
        Number id = read(upload, "$.id");
        return id.longValue();
    }

    private ResultActions save(String bearer, String body) throws Exception {
        return mvc.perform(post("/api/employees").contentType(MediaType.APPLICATION_JSON).content(body)
                .header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private ResultActions update(String bearer, Number id, String body) throws Exception {
        return mvc.perform(put("/api/employees/" + id).contentType(MediaType.APPLICATION_JSON).content(body)
                .header(HttpHeaders.AUTHORIZATION, bearer));
    }
}
