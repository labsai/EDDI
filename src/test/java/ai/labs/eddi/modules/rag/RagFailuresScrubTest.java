/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RagFailures — nothing a caller typed or a driver wrote comes back")
class RagFailuresScrubTest {

    @Test
    @DisplayName("URL userinfo and query strings are removed from a message that passes through")
    void scrubsUrls() {
        assertEquals("Could not fetch https://example.com/docs",
                RagFailures.scrub("Could not fetch https://user:s3cret@example.com/docs?token=abc#frag"));
        assertEquals("", RagFailures.scrub(null));
        assertEquals("no url here", RagFailures.scrub("no url here"));
    }

    @Test
    @DisplayName("a connector's own message passes through, scrubbed")
    void operatorMessagesAreScrubbed() {
        String message = RagFailures.clientMessage(new OperatorFailureException("Page https://u:p@host/a?key=1 refused the request"));

        assertEquals("Page https://host/a refused the request", message);
    }

    @Test
    @DisplayName("an IllegalArgumentException from URI.create, which quotes the input with its credentials, is the generic sentence")
    void uriFailureIsGeneric() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> URI.create("http://user:pw@ho st/"));

        assertEquals(RagFailures.GENERIC, RagFailures.clientMessage(failure));
    }

    @Test
    @DisplayName("a NumberFormatException, which quotes what was parsed, is the generic sentence")
    void numberFormatIsGeneric() {
        assertEquals(RagFailures.GENERIC, RagFailures.clientMessage(new NumberFormatException("For input string: \"hunter2\"")));
        assertEquals(RagFailures.GENERIC, RagFailures.clientMessage(new IllegalArgumentException("bad", new NumberFormatException("x"))));
    }

    @Test
    @DisplayName("a message with a URL in it, or a very long one, is not passed through; a parameter rule is")
    void onlyParameterRulesPass() {
        assertEquals(RagFailures.GENERIC, RagFailures.clientMessage(new IllegalArgumentException("Invalid URI scheme in https://u:p@h/")));
        assertEquals(RagFailures.GENERIC, RagFailures.clientMessage(new IllegalArgumentException("x".repeat(500))));
        String rule = "Invalid integer value for storeParameters.port";
        assertEquals(rule, RagFailures.clientMessage(new IllegalArgumentException(rule)));
    }

    @Test
    @DisplayName("a short IllegalArgumentException that quotes its input — an enum or UUID parse, a file path — is the generic sentence")
    void parseTextAndPathsAreGeneric() {
        IllegalArgumentException enumParse = assertThrows(IllegalArgumentException.class, () -> Thread.State.valueOf("hunter2"));
        IllegalArgumentException uuidParse = assertThrows(IllegalArgumentException.class, () -> UUID.fromString("hunter2"));

        assertEquals(RagFailures.GENERIC, RagFailures.clientMessage(enumParse));
        assertEquals(RagFailures.GENERIC, RagFailures.clientMessage(uuidParse));
        assertEquals(RagFailures.GENERIC, RagFailures.clientMessage(new IllegalArgumentException("cannot read C:\\data\\eddi\\kb.json")));
        assertEquals(RagFailures.GENERIC, RagFailures.clientMessage(new IllegalArgumentException("cannot read /var/lib/eddi/kb.json")));
        assertEquals(RagFailures.GENERIC, RagFailures.clientMessage(new IllegalArgumentException("cannot read \\\\fileserver\\share")));
        String rule = "storeParameters.table must be a plain table name";
        assertEquals(rule, RagFailures.clientMessage(new IllegalArgumentException(rule)));
    }

    @Test
    @DisplayName("a store failure ends a run: every further document would cost a provider call and fail the same way")
    void sqlFailuresEndTheRun() {
        assertTrue(RagFailures.endsRun(new RuntimeException("insert failed", new SQLException("relation does not exist", "42P01"))));
        assertFalse(RagFailures.endsRun(new IllegalStateException("one page was empty")));
    }
}
