/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit;

import ai.labs.eddi.datastore.postgres.PostgresAuditStore;
import ai.labs.eddi.datastore.postgres.PostgresTestBase;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sign, store, read, verify against a real PostgreSQL, with U+0000 in the
 * places a user can put it: the entry is made storable before it is signed, so
 * the row the database holds is the row that was signed and verifies.
 */
@DisplayName("A signed audit entry containing NUL verifies after a PostgreSQL round trip")
class AuditNulSigningPostgresIT extends PostgresTestBase {

    /** A payload value that is a POJO until the ledger normalises it. */
    public static class Payload {
        private final String text;

        public Payload(String text) {
            this.text = text;
        }

        public String getText() {
            return text;
        }
    }

    @TempDir
    Path tempDir;

    @Test
    void signStoreReadVerify() throws Exception {
        Instance<DataSource> dataSource = createDataSourceInstance();
        IJsonSerialization json = new JsonSerialization(SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false));
        var store = new PostgresAuditStore(dataSource, json);
        var ledger = AuditLedgerService.createForTesting(store, true, 60, "test-master-key-for-the-audit-hmac-0123456789", new SimpleMeterRegistry(),
                1000,
                tempDir.resolve("deadletter.jsonl").toString());
        ledger.init();
        String conversationId = "conv-" + UUID.randomUUID() + "\u0000x";
        for (int i = 0; i < 3; i++) {
            ledger.submit(new AuditEntry(UUID.randomUUID().toString(), conversationId, "agent\u0000", 1, "us\u0000er", "production", i, "task",
                    "LlmTask", 0, 5L,
                    Map.of("pojo", new Payload("a\u0000b \uD83D"), "text", "line\nwith \"quotes\" 😀 and \\u0000 literal"),
                    Map.of("text", "out\u0000"),
                    null, null, List.of("act\u0000"), 0.0, Instant.now(), null, null));
        }

        ledger.flush();

        List<AuditEntry> stored = store.getEntries(conversationId, 0, 10);
        assertEquals(3, stored.size(), "none of the three entries was lost to the NUL");
        for (AuditEntry entry : stored) {
            assertFalse(entry.conversationId().contains("\u0000"));
            assertTrue(entry.conversationId().endsWith("�x"));
            assertEquals(AuditVerificationStatus.VALID, ledger.verifyEntry(entry), "the stored row is what was signed");
        }
    }
}
