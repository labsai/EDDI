/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.schedule.model.ScheduleFireLog;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jboss.logging.Logger;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an MCP tool tells its caller when something failed.
 * <p>
 * Two constraints pull against each other here. A 4xx raised by EDDI's own REST
 * stores — which these tools call in-process — carries its useful text in the
 * response entity, and that text has to reach the MCP client or the MCP/REST
 * parity this release restored is only half done: "Unknown field
 * &#39;setProperties&#39;" would arrive as "HTTP 400 Bad Request". A 5xx entity
 * is not written to that contract and can carry endpoint or datastore detail,
 * so it is never lifted verbatim.
 */
@DisplayName("MCP failure descriptions")
class McpToolUtilsDescribeTest {

    private static Response entity(int status, String body) {
        return Response.status(status).entity(body).type(MediaType.TEXT_PLAIN_TYPE).build();
    }

    @Test
    @DisplayName("a 4xx entity reaches the caller, so MCP and REST say the same thing")
    void clientErrorEntityIsSurfaced() {
        var rejected = new BadRequestException(entity(400,
                "Unknown field 'setProperties' in PropertySetterConfiguration. Known fields: [setOnActions]"));

        assertTrue(McpToolUtils.describe(rejected).contains("setOnActions"));
    }

    @Test
    @DisplayName("a 5xx entity is not lifted verbatim")
    void serverErrorEntityIsNotSurfaced() {
        var failure = new InternalServerErrorException(entity(500,
                "com.mongodb.MongoSocketOpenException: internal-db.prod.local:27017"));

        assertFalse(McpToolUtils.describe(failure).contains("internal-db.prod.local"),
                "a 5xx body is not written for the caller and may name internal infrastructure");
    }

    @Test
    @DisplayName("an exception with no message names its class rather than rendering null")
    void nullMessageNamesTheClass() {
        assertEquals("IOException", McpToolUtils.describe(new IOException()));
    }

    @Test
    @DisplayName("an ordinary message is used as-is")
    void plainMessageIsUsed() {
        assertEquals("boom", McpToolUtils.describe(new IllegalStateException("boom")));
    }

    @Test
    @DisplayName("no cause at all still produces something readable")
    void nullCause() {
        assertEquals("unknown cause", McpToolUtils.describe(null));
    }

    @Test
    @DisplayName("toolFailure keeps what failed, hides a server-side cause and gives a reference that is in the log")
    void toolFailureHidesInternalText() {
        String json = McpToolUtils.toolFailure(Logger.getLogger(McpToolUtilsDescribeTest.class), "create_resource",
                "Failed to create resource", new IllegalStateException("mongodb://db-7.internal:27017 refused"));

        assertTrue(json.contains("Failed to create resource"));
        assertTrue(json.contains("\"errorCode\":\"INTERNAL\""), json);
        assertTrue(json.contains("reference"), json);
        assertFalse(json.contains("db-7.internal"), json);
    }
    @Test
    @DisplayName("an agent mismatch is described without the agent id stored with the conversation")
    void agentMismatchDoesNotNameTheStoredAgent() {
        var mismatch = new IConversationService.AgentMismatchException(
                "conversationId: 'c1' does not belong to Agent with conversationId: 'a1'. (provided agentId='a1', agentId in ConversationMemory='other-tenant-agent')");

        String said = McpToolUtils.safeMessage(mismatch, "fallback");

        assertFalse(said.contains("other-tenant-agent"), said);
        assertEquals("The conversation does not belong to the given agent", said);
    }

    @Test
    @DisplayName("a schedule fire's thrown-failure text is reduced to its exception type; the executor's own sentences are kept")
    void fireErrorKeepsTheTypeNotTheText() {
        assertEquals("MongoTimeoutException (details in the server log)",
                McpToolUtils.fireErrorForCaller("MongoTimeoutException: Timed out connecting to db-7.internal:27017"));
        assertEquals("Conversation ended in state ERROR", McpToolUtils.fireErrorForCaller("Conversation ended in state ERROR"));
        assertEquals("Turn skipped without consuming the input — conversation was in state ENDED",
                McpToolUtils.fireErrorForCaller("Turn skipped without consuming the input — conversation was in state ENDED"));
        assertEquals(null, McpToolUtils.fireErrorForCaller(null));

        var log = new ScheduleFireLog("l", "s", "f", null, null, null, "FAILED", "i", "c", "IOException: /var/lib/eddi/x refused", 1, 0.0);
        assertEquals("IOException (details in the server log)", McpToolUtils.fireLogForCaller(log).errorMessage());
        assertEquals("FAILED", McpToolUtils.fireLogForCaller(log).status());
    }
}
