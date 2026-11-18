/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rag;

import ai.labs.eddi.modules.llm.impl.InMemoryStoreFullException;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.ModelNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.BatchUpdateException;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RagFailures")
class RagFailuresTest {

    @Test
    @DisplayName("a driver failure with SQL in it becomes a fixed sentence")
    void driverTextNeverLeaves() {
        Exception batch = new RuntimeException("insert failed",
                new BatchUpdateException("Batch entry 0 INSERT INTO eddi_kbid_x (embedding_id, embedding, text, metadata) VALUES (...)",
                        new int[0], new SQLException("ERROR: different vector dimensions 8 and 16")));

        String message = RagFailures.clientMessage(batch);

        assertFalse(message.contains("INSERT"), message);
        assertFalse(message.contains("dimensions 8 and 16"), message);
        assertTrue(message.contains("server log"), message);
    }

    @Test
    @DisplayName("an unexpected failure is the generic sentence, whatever its message says")
    void genericFailure() {
        assertEquals(RagFailures.GENERIC, RagFailures.clientMessage(new IllegalStateException("jdbc:postgresql://secret-host/db")));
        assertEquals(RagFailures.GENERIC, RagFailures.clientMessage(null));
    }

    @Test
    @DisplayName("rejected credentials and an unknown model are named, without the provider's text")
    void providerFailures() {
        String auth = RagFailures.clientMessage(new RuntimeException("wrapped", new AuthenticationException("401 sk-live-abc")));
        assertTrue(auth.contains("credentials"), auth);
        assertFalse(auth.contains("sk-live"), auth);

        String model = RagFailures.clientMessage(new ModelNotFoundException("no such model gpt-xyz"));
        assertTrue(model.contains("model"), model);
        assertFalse(model.contains("gpt-xyz"), model);
    }

    @Test
    @DisplayName("messages EDDI wrote for the operator pass through")
    void ownMessagesPassThrough() {
        assertEquals("refused", RagFailures.clientMessage(new VectorSpaceMismatchException("refused")));
        assertEquals("full", RagFailures.clientMessage(new InMemoryStoreFullException("full")));
        assertEquals("Invalid integer value for storeParameters.port",
                RagFailures.clientMessage(new IllegalArgumentException("Invalid integer value for storeParameters.port")));
    }

    @Test
    @DisplayName("only failures that repeat for every document end a run")
    void endsRun() {
        assertTrue(RagFailures.endsRun(new VectorSpaceMismatchException("x")));
        assertTrue(RagFailures.endsRun(new RuntimeException(new AuthenticationException("401"))));
        assertTrue(RagFailures.endsRun(new ModelNotFoundException("404")));
        assertFalse(RagFailures.endsRun(new IllegalStateException("one page failed")));
    }
}
