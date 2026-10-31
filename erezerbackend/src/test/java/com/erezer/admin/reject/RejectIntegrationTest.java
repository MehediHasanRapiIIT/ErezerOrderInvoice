package com.erezer.admin.reject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erezer.admin.security.Permission;
import com.erezer.admin.security.Role;
import com.erezer.admin.support.IntegrationTest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** The tests share a database, so each starts with no rejects and the totals can be exact. */
class RejectIntegrationTest extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.sql("delete from rejects").update();
        admin = adminBearer();
    }

    @Test
    void aStockVariantLosesTheRejectedPieces() throws Exception {
        long variant = variant(20);
        add(admin, body(today(), variant, null, "PRINTED", 3)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.fromStock").value(true))
                .andExpect(jsonPath("$.quantity").value(3))
                .andExpect(jsonPath("$.kind").value("PRINTED"));

        assertThat(stock(variant)).isEqualTo(17);
        assertThat(jdbc.sql("select quantity from stock_variants where id = :id").param("id", variant).query(Integer.class).single()).isEqualTo(17);
        assertThat(jdbc.sql("select reason from stock_movements where variant_id = :id order by id desc limit 1").param("id", variant)
                .query(String.class).single()).isEqualTo("REJECTED");
    }

    @Test
    void otherOnlyRecordsAName() throws Exception {
        long variant = variant(20);
        add(admin, body(today(), null, "Sample fabric", "SOLID", 4)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.fromStock").value(false))
                .andExpect(jsonPath("$.variantName").value("Sample fabric"))
                .andExpect(jsonPath("$.stockItemId").doesNotExist());
        assertThat(stock(variant)).isEqualTo(20);

        add(admin, body(today(), null, " ", "SOLID", 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OTHER_NAME_REQUIRED"));
    }

    @Test
    void moreThanIsInStockIsRefusedAndNothingIsSaved() throws Exception {
        long variant = variant(2);
        add(admin, body(today(), variant, null, "SOLID", 3)).andExpect(status().isConflict());
        assertThat(stock(variant)).isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from rejects").query(Long.class).single()).isZero();
    }

    @Test
    void deletingPutsThePiecesBack() throws Exception {
        long variant = variant(20);
        long id = id(add(admin, body(today(), variant, null, "SOLID", 5)));
        assertThat(stock(variant)).isEqualTo(15);

        mvc.perform(delete("/api/rejects/" + id).header(HttpHeaders.AUTHORIZATION, admin)).andExpect(status().isNoContent());
        assertThat(stock(variant)).isEqualTo(20);

        long other = id(add(admin, body(today(), null, "Other thing", "SOLID", 5)));
        mvc.perform(delete("/api/rejects/" + other).header(HttpHeaders.AUTHORIZATION, admin)).andExpect(status().isNoContent());
        assertThat(stock(variant)).isEqualTo(20);
    }

    @Test
    void editingMovesStockToMatch() throws Exception {
        long first = variant(20);
        long second = variant(20);
        MvcResult saved = add(admin, body(today(), first, null, "SOLID", 5)).andExpect(status().isCreated()).andReturn();
        long id = ((Number) read(saved, "$.id")).longValue();
        Number version = read(saved, "$.version");

        edit(id, body(today(), first, null, "SOLID", 8), version).andExpect(status().isOk()).andExpect(jsonPath("$.quantity").value(8));
        assertThat(stock(first)).isEqualTo(12);

        edit(id, body(today(), second, null, "SOLID", 8), currentVersion(id)).andExpect(status().isOk());
        assertThat(stock(first)).isEqualTo(20);
        assertThat(stock(second)).isEqualTo(12);

        edit(id, body(today(), null, "Typed now", "SOLID", 8), currentVersion(id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.fromStock").value(false));
        assertThat(stock(second)).isEqualTo(20);

        edit(id, body(today(), null, "Stale", "SOLID", 1), version).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_DATA"));
    }

    @Test
    void anEditThatCantBeMetChangesNothing() throws Exception {
        long variant = variant(5);
        MvcResult saved = add(admin, body(today(), variant, null, "SOLID", 2)).andExpect(status().isCreated()).andReturn();
        long id = ((Number) read(saved, "$.id")).longValue();
        edit(id, body(today(), variant, null, "SOLID", 50), read(saved, "$.version")).andExpect(status().isConflict());
        assertThat(stock(variant)).isEqualTo(3);
    }

    @Test
    void theCardsCountPiecesAndFollowTheDates() throws Exception {
        long variant = variant(20);
        String old = LocalDate.now(ZoneId.of("Asia/Dhaka")).minusDays(10).toString();
        add(admin, body(today(), variant, null, "SOLID", 3)).andExpect(status().isCreated());
        add(admin, body(today(), variant, null, "PRINTED", 2)).andExpect(status().isCreated());
        add(admin, body(today(), null, "Other", "PRINTED", 4)).andExpect(status().isCreated());
        add(admin, body(old, variant, null, "SOLID", 1)).andExpect(status().isCreated());

        mvc.perform(get("/api/rejects/summary").param("from", today()).param("to", today()).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.total").value(9))
                .andExpect(jsonPath("$.entries").value(3))
                .andExpect(jsonPath("$.solid").value(3))
                .andExpect(jsonPath("$.printed").value(6))
                .andExpect(jsonPath("$.fromStock").value(5))
                .andExpect(jsonPath("$.other").value(4));
    }

    @Test
    void theListIsPagedAndFiltered() throws Exception {
        long variant = variant(20);
        for (int i = 0; i < 3; i++) {
            add(admin, body(today(), variant, null, "SOLID", 1)).andExpect(status().isCreated());
        }
        add(admin, body(today(), null, "Findable", "PRINTED", 1)).andExpect(status().isCreated());

        mvc.perform(get("/api/rejects").param("size", "3").param("page", "1").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content.length()").value(1));
        mvc.perform(get("/api/rejects").param("kind", "PRINTED").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/rejects").param("fromStock", "false").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.content[*].variantName").value(hasItem("Findable")));
        mvc.perform(get("/api/rejects").param("q", "FINDABLE").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void wrongInputIsRefused() throws Exception {
        String tomorrow = LocalDate.now(ZoneId.of("Asia/Dhaka")).plusDays(1).toString();
        add(admin, body(tomorrow, null, "Late", "SOLID", 1)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FUTURE_DATE"));
        add(admin, body(today(), null, "Zero", "SOLID", 0)).andExpect(status().isBadRequest());
        add(admin, body(today(), null, "Bad", "STRIPED", 1)).andExpect(status().isBadRequest());
        add(admin, body(today(), 999_999L, null, "SOLID", 1)).andExpect(status().isBadRequest());
    }

    @Test
    void permissionsGuardEveryStep() throws Exception {
        String none = bearer(Role.MODERATOR);
        mvc.perform(get("/api/rejects").header(HttpHeaders.AUTHORIZATION, none)).andExpect(status().isForbidden());

        String viewer = bearer(Role.MODERATOR, Permission.REJECT_VIEW);
        mvc.perform(get("/api/rejects").header(HttpHeaders.AUTHORIZATION, viewer)).andExpect(status().isOk());
        add(viewer, body(today(), null, "No", "SOLID", 1)).andExpect(status().isForbidden());

        String adder = bearer(Role.MODERATOR, Permission.REJECT_CREATE);
        long id = id(add(adder, body(today(), null, "Yes", "SOLID", 1)));
        mvc.perform(get("/api/rejects").header(HttpHeaders.AUTHORIZATION, adder)).andExpect(status().isOk());
        mvc.perform(delete("/api/rejects/" + id).header(HttpHeaders.AUTHORIZATION, adder)).andExpect(status().isForbidden());
        mvc.perform(put("/api/rejects/" + id).contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, adder)
                .content(body(today(), null, "Yes", "SOLID", 1))).andExpect(status().isForbidden());
    }

    private static String today() {
        return LocalDate.now(ZoneId.of("Asia/Dhaka")).toString();
    }

    private static String body(String date, Long variant, String other, String kind, int quantity) {
        return "{\"date\":\"" + date + "\",\"variantId\":" + variant + ",\"otherName\":" + (other == null ? "null" : "\"" + other + "\"")
                + ",\"kind\":\"" + kind + "\",\"quantity\":" + quantity + "}";
    }

    private long variant(int quantity) throws Exception {
        MvcResult created = mvc.perform(post("/api/stock/variants").contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, admin)
                        .content("{\"name\":\"Reject " + UUID.randomUUID() + "\",\"quantity\":" + quantity + "}"))
                .andExpect(status().isCreated()).andReturn();
        return ((Number) read(created, "$.id")).longValue();
    }

    private int stock(long variant) {
        return jdbc.sql("select sum(quantity) from stock_items where variant_id = :id").param("id", variant).query(Integer.class).single();
    }

    private ResultActions add(String bearer, String body) throws Exception {
        return mvc.perform(post("/api/rejects").contentType(MediaType.APPLICATION_JSON).content(body).header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private ResultActions edit(long id, String body, Number version) throws Exception {
        return mvc.perform(put("/api/rejects/" + id).contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, admin)
                .content(body.substring(0, body.length() - 1) + ",\"version\":" + version + "}"));
    }

    private Number currentVersion(long id) {
        return jdbc.sql("select version from rejects where id = :id").param("id", id).query(Long.class).single();
    }

    private long id(ResultActions created) throws Exception {
        return ((Number) read(created.andExpect(status().isCreated()).andReturn(), "$.id")).longValue();
    }
}
