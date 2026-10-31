package com.erezer.admin.boost;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** The tests share a database, so each starts with empty boost tables and the totals can be exact. */
class BoostIntegrationTest extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.sql("delete from boost_costs").update();
        jdbc.sql("delete from boost_purchases").update();
        admin = adminBearer();
    }

    @Test
    void aCostIsTurnedIntoTakaAtTheRateGiven() throws Exception {
        addCost(admin, today(), "12.50", "121.75", "Eid sale").andExpect(status().isCreated())
                .andExpect(jsonPath("$.bdt").value(1521.88))
                .andExpect(jsonPath("$.rate").value(121.75))
                .andExpect(jsonPath("$.note").value("Eid sale"))
                .andExpect(jsonPath("$.addedBy").exists());
    }

    @Test
    void aPurchaseWorksOutWhatEachDollarCost() throws Exception {
        buy(admin, "[{\"usd\":5,\"bdt\":500},{\"usd\":6,\"bdt\":630}]").andExpect(status().isCreated())
                .andExpect(jsonPath("$.usd").value(11))
                .andExpect(jsonPath("$.bdt").value(1130))
                .andExpect(jsonPath("$.rate").value(102.7273))
                .andExpect(jsonPath("$.lines.length()").value(2));
    }

    @Test
    void theCardsShowSpendingBuyingAndWhatIsLeft() throws Exception {
        String lastMonth = LocalDate.now(ZoneId.of("Asia/Dhaka")).minusDays(40).toString();
        buy(admin, "[{\"usd\":100,\"bdt\":12000}]", lastMonth).andExpect(status().isCreated());
        buy(admin, "[{\"usd\":50,\"bdt\":6300}]", today()).andExpect(status().isCreated());
        addCost(admin, today(), "30", "120", null).andExpect(status().isCreated());
        addCost(admin, lastMonth, "20", "120", null).andExpect(status().isCreated());

        mvc.perform(get("/api/boost/summary").param("from", today()).param("to", today()).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.spentUsd").value(30))
                .andExpect(jsonPath("$.spentBdt").value(3600))
                .andExpect(jsonPath("$.costCount").value(1))
                .andExpect(jsonPath("$.boughtUsd").value(50))
                .andExpect(jsonPath("$.boughtBdt").value(6300))
                .andExpect(jsonPath("$.averageRate").value(122))
                .andExpect(jsonPath("$.balanceUsd").value(100));

        mvc.perform(get("/api/boost/rate").header(HttpHeaders.AUTHORIZATION, admin)).andExpect(jsonPath("$.rate").value(122));
    }

    @Test
    void theBalanceCanGoBelowZero() throws Exception {
        addCost(admin, today(), "10", "120", null).andExpect(status().isCreated());
        mvc.perform(get("/api/boost/summary").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.balanceUsd").value(-10))
                .andExpect(jsonPath("$.averageRate").doesNotExist());
        mvc.perform(get("/api/boost/rate").header(HttpHeaders.AUTHORIZATION, admin)).andExpect(jsonPath("$.rate").doesNotExist());
    }

    @Test
    void listsArePagedAndFiltered() throws Exception {
        for (int i = 0; i < 3; i++) {
            addCost(admin, today(), "1", "120", "Campaign " + i).andExpect(status().isCreated());
        }
        mvc.perform(get("/api/boost/costs").param("size", "2").param("page", "1").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content.length()").value(1));
        mvc.perform(get("/api/boost/costs").param("q", "CAMPAIGN 1").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/boost/costs").param("to", LocalDate.now(ZoneId.of("Asia/Dhaka")).minusDays(1).toString())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void wrongAmountsAndFutureDatesAreRefused() throws Exception {
        String tomorrow = LocalDate.now(ZoneId.of("Asia/Dhaka")).plusDays(1).toString();
        addCost(admin, tomorrow, "1", "120", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FUTURE_DATE"));
        addCost(admin, today(), "0", "120", null).andExpect(status().isBadRequest());
        addCost(admin, today(), "1", "0", null).andExpect(status().isBadRequest());
        buy(admin, "[]").andExpect(status().isBadRequest());
        buy(admin, "[{\"usd\":0,\"bdt\":500}]").andExpect(status().isBadRequest());
    }

    @Test
    void deletingRemovesFromTheTotals() throws Exception {
        long cost = id(addCost(admin, today(), "5", "120", null));
        long purchase = id(buy(admin, "[{\"usd\":5,\"bdt\":600}]"));
        mvc.perform(delete("/api/boost/costs/" + cost).header(HttpHeaders.AUTHORIZATION, admin)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/boost/purchases/" + purchase).header(HttpHeaders.AUTHORIZATION, admin)).andExpect(status().isNoContent());
        mvc.perform(get("/api/boost/summary").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.spentUsd").value(0))
                .andExpect(jsonPath("$.balanceUsd").value(0));
    }

    @Test
    void whatDollarsCostIsOnlyForThoseWhoBuy() throws Exception {
        buy(admin, "[{\"usd\":5,\"bdt\":600}]").andExpect(status().isCreated());

        String viewer = bearer(Role.MODERATOR, Permission.BOOST_VIEW);
        mvc.perform(get("/api/boost/summary").header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spentUsd").exists())
                .andExpect(jsonPath("$.boughtUsd").doesNotExist())
                .andExpect(jsonPath("$.balanceUsd").doesNotExist());
        mvc.perform(get("/api/boost/purchases").header(HttpHeaders.AUTHORIZATION, viewer)).andExpect(status().isForbidden());
        buy(viewer, "[{\"usd\":5,\"bdt\":600}]").andExpect(status().isForbidden());
        addCost(viewer, today(), "1", "120", null).andExpect(status().isForbidden());

        String adder = bearer(Role.MODERATOR, Permission.BOOST_COST_ADD);
        addCost(adder, today(), "1", "120", null).andExpect(status().isCreated());
        mvc.perform(get("/api/boost/costs").header(HttpHeaders.AUTHORIZATION, adder)).andExpect(status().isOk());
        mvc.perform(get("/api/boost/rate").header(HttpHeaders.AUTHORIZATION, adder)).andExpect(status().isOk());

        String none = bearer(Role.MODERATOR);
        mvc.perform(get("/api/boost/costs").header(HttpHeaders.AUTHORIZATION, none)).andExpect(status().isForbidden());

        long cost = id(addCost(admin, today(), "1", "120", null));
        mvc.perform(delete("/api/boost/costs/" + cost).header(HttpHeaders.AUTHORIZATION, adder)).andExpect(status().isForbidden());
    }

    @Test
    void editingACostWorksOutTheTakaAgainAndMovesTheTotals() throws Exception {
        MvcResult saved = addCost(admin, today(), "10", "120", "First").andExpect(status().isCreated()).andReturn();
        long id = ((Number) read(saved, "$.id")).longValue();
        Number version = read(saved, "$.version");

        editCost(admin, id, "20", "125", "Changed", version).andExpect(status().isOk())
                .andExpect(jsonPath("$.bdt").value(2500))
                .andExpect(jsonPath("$.note").value("Changed"));
        mvc.perform(get("/api/boost/summary").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.spentUsd").value(20))
                .andExpect(jsonPath("$.spentBdt").value(2500))
                .andExpect(jsonPath("$.costCount").value(1));

        editCost(admin, id, "30", "125", null, version).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_DATA"));
        editCost(admin, id, "30", "125", null, null).andExpect(status().isConflict());
    }

    @Test
    void editingAPurchaseReplacesItsPartsAndChangesTheAverage() throws Exception {
        MvcResult saved = buy(admin, "[{\"usd\":5,\"bdt\":500},{\"usd\":6,\"bdt\":600}]").andExpect(status().isCreated()).andReturn();
        long id = ((Number) read(saved, "$.id")).longValue();
        Number version = read(saved, "$.version");

        editPurchase(admin, id, "[{\"usd\":10,\"bdt\":1200}]", version).andExpect(status().isOk())
                .andExpect(jsonPath("$.usd").value(10))
                .andExpect(jsonPath("$.bdt").value(1200))
                .andExpect(jsonPath("$.rate").value(120))
                .andExpect(jsonPath("$.lines.length()").value(1));
        mvc.perform(get("/api/boost/summary").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.averageRate").value(120))
                .andExpect(jsonPath("$.balanceUsd").value(10));
        editPurchase(admin, id, "[{\"usd\":1,\"bdt\":100}]", version).andExpect(status().isConflict());
    }

    @Test
    void anEditCantMoveAnEntryIntoTheFuture() throws Exception {
        MvcResult saved = addCost(admin, today(), "10", "120", null).andExpect(status().isCreated()).andReturn();
        String tomorrow = LocalDate.now(ZoneId.of("Asia/Dhaka")).plusDays(1).toString();
        mvc.perform(put("/api/boost/costs/" + read(saved, "$.id")).contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, admin)
                        .content("{\"date\":\"" + tomorrow + "\",\"usd\":10,\"rate\":120,\"version\":" + read(saved, "$.version") + "}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FUTURE_DATE"));
    }

    @Test
    void editingNeedsItsOwnPermission() throws Exception {
        long cost = id(addCost(admin, today(), "1", "120", null));
        long purchase = id(buy(admin, "[{\"usd\":5,\"bdt\":600}]"));

        String adder = bearer(Role.MODERATOR, Permission.BOOST_COST_ADD);
        editCost(adder, cost, "2", "120", null, 0).andExpect(status().isForbidden());

        String editor = bearer(Role.MODERATOR, Permission.BOOST_EDIT);
        editCost(editor, cost, "2", "120", null, 0).andExpect(status().isOk());
        editPurchase(editor, purchase, "[{\"usd\":6,\"bdt\":600}]", 0).andExpect(status().isForbidden());

        String buyer = bearer(Role.MODERATOR, Permission.BOOST_BUY, Permission.BOOST_EDIT);
        editPurchase(buyer, purchase, "[{\"usd\":6,\"bdt\":600}]", 0).andExpect(status().isOk());
    }

    private ResultActions editCost(String bearer, long id, String usd, String rate, String note, Number version) throws Exception {
        return mvc.perform(put("/api/boost/costs/" + id).contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, bearer)
                .content("{\"date\":\"" + today() + "\",\"usd\":" + usd + ",\"rate\":" + rate + ",\"note\":"
                        + (note == null ? "null" : "\"" + note + "\"") + ",\"version\":" + version + "}"));
    }

    private ResultActions editPurchase(String bearer, long id, String lines, Number version) throws Exception {
        return mvc.perform(put("/api/boost/purchases/" + id).contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, bearer)
                .content("{\"date\":\"" + today() + "\",\"lines\":" + lines + ",\"version\":" + version + "}"));
    }

    private static String today() {
        return LocalDate.now(ZoneId.of("Asia/Dhaka")).toString();
    }

    private ResultActions addCost(String bearer, String date, String usd, String rate, String note) throws Exception {
        return mvc.perform(post("/api/boost/costs").contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, bearer)
                .content("{\"date\":\"" + date + "\",\"usd\":" + usd + ",\"rate\":" + rate + ",\"note\":"
                        + (note == null ? "null" : "\"" + note + "\"") + "}"));
    }

    private ResultActions buy(String bearer, String lines) throws Exception {
        return buy(bearer, lines, today());
    }

    private ResultActions buy(String bearer, String lines, String date) throws Exception {
        return mvc.perform(post("/api/boost/purchases").contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, bearer)
                .content("{\"date\":\"" + date + "\",\"lines\":" + lines + "}"));
    }

    private long id(ResultActions created) throws Exception {
        MvcResult result = created.andExpect(status().isCreated()).andReturn();
        return ((Number) read(result, "$.id")).longValue();
    }
}
