/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The admin-action audit of in-process MCP tools works in the running
 * application, called the way an MCP client calls it.
 * <p>
 * {@code McpAuditInterceptor} is bound to the tool classes by the class-level
 * {@code @McpAuditBinding}, and quarkus-mcp-server invokes a tool through an
 * ArC invoker that looks the bean up as a contextual reference — so the
 * interceptor chain runs. Unit tests can only call the interceptor by hand; an
 * audit that silently does not fire would pass them all. This test goes through
 * the real transport: a JSON-RPC {@code tools/call} on {@code /mcp}, then the
 * ledger's {@code /auditstore/admin-actions} read, and fails unless the entry
 * is there — with the target id from the arguments and the outcome the error
 * interceptor produced (a {@code NOT_FOUND} result, so the test changes
 * nothing). A read-only tool called the same way leaves no entry.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@DisplayName("MCP admin-action audit — through the real MCP transport")
public class McpAuditInterceptionIT extends BaseIntegrationIT {

    private static final String MCP = "/mcp";
    private static final String ACCEPT = "application/json, text/event-stream";
    private static final String SESSION_HEADER = "Mcp-Session-Id";

    private String session;
    private int nextId = 1;

    private Response post(String body) {
        var request = given().contentType(ContentType.JSON).accept(ACCEPT).body(body);
        if (session != null) {
            request = request.header(SESSION_HEADER, session);
        }
        return request.post(MCP);
    }

    private void initialize() {
        Response init = post("""
                {"jsonrpc":"2.0","id":%d,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},
                 "clientInfo":{"name":"audit-it","version":"1"}}}
                """.formatted(nextId++));
        assertEquals(200, init.statusCode(), init.asString());
        session = init.getHeader(SESSION_HEADER);
        post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
    }

    private String callTool(String tool, String argumentsJson) {
        Response response = post("""
                {"jsonrpc":"2.0","id":%d,"method":"tools/call","params":{"name":"%s","arguments":%s}}
                """.formatted(nextId++, tool, argumentsJson));
        assertEquals(200, response.statusCode(), response.asString());
        return response.asString();
    }

    /** The admin-action entries whose recorded path is {@code path}. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> entriesFor(String path) {
        List<Map<String, Object>> entries = given().queryParam("limit", 500).get("/auditstore/admin-actions").then().statusCode(200).extract()
                .jsonPath().getList("$");
        return entries.stream().filter(entry -> entry.get("input") instanceof Map<?, ?> input && path.equals(input.get("path"))).toList();
    }

    /** Polls the ledger (it flushes on an interval) until the entry is there. */
    private Map<String, Object> awaitEntry(String path) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            var found = entriesFor(path);
            if (!found.isEmpty()) {
                return found.getFirst();
            }
            Thread.sleep(250);
        }
        return fail("no admin-action entry with path " + path + " — the MCP audit interceptor did not run");
    }

    @Test
    @DisplayName("an audited tool called over /mcp leaves an entry with its target and outcome; a read-only tool leaves none")
    @SuppressWarnings("unchecked")
    void auditedToolIsRecorded() throws Exception {
        initialize();
        String missing = "audit-it-" + UUID.randomUUID();

        String result = callTool("delete_schedule", "{\"scheduleId\":\"" + missing + "\"}");
        // Deleting a schedule that does not exist is idempotent (REST answers 204), so
        // the tool reports success; the entry must still carry its target.
        assertTrue(result.contains("\"isError\":false") && result.contains("schedule_deleted"), "an idempotent delete: " + result);

        Map<String, Object> entry = awaitEntry("/mcp/delete_schedule/" + missing);
        Map<String, Object> input = (Map<String, Object>) entry.get("input");
        assertEquals("MCP", input.get("method"));
        assertEquals("McpAdminTools#deleteSchedule", input.get("resource"));
        Object status = ((Map<String, Object>) entry.get("output")).get("status");
        // The recorded outcome is the tool's: a success is recorded as 200.
        assertEquals(Integer.valueOf(200), status, entry.toString());

        // The read-only twin, recorded nowhere. Called before the audited one would
        // prove nothing: after it, the ledger has had a flush to show an entry.
        String readMissing = "audit-it-read-" + UUID.randomUUID();
        callTool("read_schedule", "{\"scheduleId\":\"" + readMissing + "\"}");
        String marker = "audit-it-marker-" + UUID.randomUUID();
        callTool("delete_schedule", "{\"scheduleId\":\"" + marker + "\"}");
        awaitEntry("/mcp/delete_schedule/" + marker);
        assertTrue(entriesFor("/mcp/read_schedule/" + readMissing).isEmpty() && entriesFor("/mcp/read_schedule").isEmpty(),
                "a read-only tool must not be recorded");
    }
}
