package com.erezer.admin.courier;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** In-process stand-in for the Steadfast API (Steadfast has no sandbox). */
final class FakeSteadfast implements AutoCloseable {

    enum Mode { OK, VALIDATION_ERROR, SERVER_ERROR, AUTH_ERROR, RATE_LIMITED, DOWN }

    record Received(String method, String path, String apiKey, String secretKey, String body) {
    }

    private final HttpServer server;
    private final AtomicInteger nextConsignment = new AtomicInteger(9_000_000);
    final List<Received> requests = new CopyOnWriteArrayList<>();
    /** Invoices Steadfast "knows", with their delivery status. */
    final Map<String, String> statusByInvoice = new ConcurrentHashMap<>();
    final Map<String, String> statusByConsignment = new ConcurrentHashMap<>();
    volatile Mode mode = Mode.OK;
    /** Invoices whose create_order should fail validation even in OK mode. */
    final List<String> rejectInvoices = new CopyOnWriteArrayList<>();
    /** Invoices Steadfast already has (booked elsewhere): create_order answers THIS_INVOICE_ALREADY_EXISTS. */
    final List<String> existingInvoices = new CopyOnWriteArrayList<>();

    FakeSteadfast() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort() + "/api/v1";
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath().replace("/api/v1", "");
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new Received(ex.getRequestMethod(), path, ex.getRequestHeaders().getFirst("Api-Key"),
                ex.getRequestHeaders().getFirst("Secret-Key"), body));
        if (mode == Mode.DOWN) {
            reply(ex, 503, "<html>Service unavailable</html>");
            return;
        }
        if (path.equals("/ping")) {
            reply(ex, 200, "{\"status\":\"ok\"}");
            return;
        }
        if (mode == Mode.AUTH_ERROR) {
            reply(ex, 401, "{\"message\":\"Unauthenticated.\"}");
            return;
        }
        if (mode == Mode.RATE_LIMITED) {
            reply(ex, 429, "{\"message\":\"Too Many Attempts.\"}");
            return;
        }
        if (path.equals("/get_balance")) {
            reply(ex, 200, "{\"status\":200,\"current_balance\":1520.5}");
        } else if (path.equals("/create_order")) {
            String invoice = body.replaceAll(".*\"invoice\":\"([^\"]+)\".*", "$1");
            if (mode == Mode.SERVER_ERROR) {
                statusByInvoice.put(invoice, "in_review"); // Steadfast booked it, but our client never saw the reply
                reply(ex, 502, "<html>Bad gateway</html>");
            } else if (existingInvoices.contains(invoice)) {
                statusByInvoice.putIfAbsent(invoice, "pending");
                reply(ex, 422, "{\"status\":422,\"message\":\"THIS_INVOICE_ALREADY_EXISTS\"}");
            } else if (mode == Mode.VALIDATION_ERROR || rejectInvoices.contains(invoice)) {
                reply(ex, 422, "{\"status\":422,\"errors\":{\"recipient_address\":[\"The recipient address field is required.\"]}}");
            } else {
                int cid = nextConsignment.incrementAndGet();
                statusByInvoice.put(invoice, "in_review");
                statusByConsignment.put(String.valueOf(cid), "in_review");
                reply(ex, 200, """
                        {"status":200,"message":"Consignment has been created successfully.",
                         "consignment":{"consignment_id":%d,"invoice":"%s","tracking_code":"SF%dTRK",
                                        "tracking_link":"https://steadfast.com.bd/t/SF%dTRK","status":"in_review"}}
                        """.formatted(cid, invoice, cid, cid));
            }
        } else if (path.startsWith("/status_by_invoice/")) {
            String status = statusByInvoice.get(path.substring("/status_by_invoice/".length()));
            reply(ex, status == null ? 404 : 200, status == null ? "{\"status\":404,\"message\":\"Not found\"}"
                    : "{\"status\":200,\"delivery_status\":\"" + status + "\"}");
        } else if (path.startsWith("/fraud_check/score/")) {
            reply(ex, 200, """
                    {"status":200,"data":{"phone":"%s","delivery_ratio":0.82,"cancellation_ratio":null,
                     "volume_band":"medium","volume_range":"11-50","delivered_count":23,"cancelled_count":5,
                     "total_reports":1,"fraud_categories":["fake_order"],"score":null,"scoring_disabled":true}}
                    """.formatted(path.substring("/fraud_check/score/".length())));
        } else if (path.startsWith("/status_by_cid/")) {
            String status = statusByConsignment.get(path.substring("/status_by_cid/".length()));
            reply(ex, status == null ? 404 : 200, status == null ? "{\"status\":404,\"message\":\"Not found\"}"
                    : "{\"status\":200,\"delivery_status\":\"" + status + "\"}");
        } else {
            reply(ex, 404, "{\"message\":\"Unknown route\"}");
        }
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    Received lastCreate() {
        return requests.stream().filter(r -> r.path().equals("/create_order")).reduce((a, b) -> b).orElseThrow();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
