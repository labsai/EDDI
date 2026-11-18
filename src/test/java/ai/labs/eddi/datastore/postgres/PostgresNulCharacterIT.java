/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.model.LogEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The character U+0000 reaches the engine from users, models and tools.
 * PostgreSQL cannot store it in {@code TEXT} or {@code jsonb}; every store must
 * store the value with U+FFFD in its place (see {@link PostgresText}) instead
 * of failing the statement and losing the turn, the resource, the memory or the
 * whole log batch. Runs against a real database, through the same stores the
 * engine uses.
 */
@DisplayName("PostgreSQL stores accept U+0000 (NUL) input")
class PostgresNulCharacterIT extends PostgresTestBase {

    private static final String FFFD = "�";

    private static Instance<DataSource> dsInstance;
    private static IJsonSerialization json;

    @BeforeAll
    static void init() {
        dsInstance = createDataSourceInstance();
        json = new JsonSerialization(SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false));
    }

    @Test
    @DisplayName("conversation memory: a NUL in the user id and in an output is stored and read back replaced")
    void conversationMemory() throws Exception {
        var store = new PostgresConversationMemoryStore(dsInstance, json);
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setAgentId("agent-nul");
        snapshot.setAgentVersion(1);
        snapshot.setUserId("user\u0000nul");
        snapshot.setConversationState(ConversationState.READY);
        snapshot.setEnvironment(Deployment.Environment.production);
        var output = new ConversationOutput();
        output.put("input", "a\u0000b");
        output.put("escaped", "literal \\u0000 text stays");
        output.put("nested", Map.of("k", List.of("x\u0000y")));
        snapshot.setConversationOutputs(new ArrayList<>(List.of(output)));

        String id = store.storeConversationMemorySnapshot(snapshot);

        var loaded = store.loadConversationMemorySnapshot(id);
        assertNotNull(loaded);
        assertEquals("user" + FFFD + "nul", loaded.getUserId());
        ConversationOutput stored = loaded.getConversationOutputs().getFirst();
        assertEquals("a" + FFFD + "b", stored.get("input"));
        assertEquals("literal \\u0000 text stays", stored.get("escaped"));
        assertEquals(Map.of("k", List.of("x" + FFFD + "y")), stored.get("nested"));
    }

    @Test
    @DisplayName("resource storage: a NUL inside a stored document is stored and read back replaced")
    void resourceStorage() throws Exception {
        @SuppressWarnings("unchecked")
        Class<Map<String, Object>> type = (Class<Map<String, Object>>) (Class<?>) Map.class;
        var storage = new PostgresResourceStorage<>(createDataSource(), "nul_resources", json, type);

        var resource = storage.newResource(Map.of("name", "nul\u0000here", "list", List.of("a\u0000", "b")));
        storage.store(resource);

        var found = storage.read(resource.getId(), resource.getVersion());
        assertNotNull(found);
        assertEquals("nul" + FFFD + "here", found.getData().get("name"));
        assertEquals(List.of("a" + FFFD, "b"), found.getData().get("list"));
    }

    @Test
    @DisplayName("user memory: a NUL in the key, the value and the user id is stored and found again")
    void userMemory() throws Exception {
        var store = new PostgresUserMemoryStore(dsInstance);
        var entry = new UserMemoryEntry(null, "user\u0000mem", "key\u0000k", "value\u0000v", "fact", Visibility.global, null, List.of(), null, false,
                0, null, null);

        store.upsert(entry);

        // the lookup is cleaned the same way as the write, so the caller's own id still
        // finds its rows
        var entries = store.getAllEntries("user\u0000mem");
        assertEquals(1, entries.size());
        assertEquals("key" + FFFD + "k", entries.getFirst().key());
        assertEquals("value" + FFFD + "v", entries.getFirst().value());
    }

    @Test
    @DisplayName("database logs: one NUL in a batch does not lose the other entries")
    void databaseLogs() {
        var logs = new PostgresDatabaseLogs(dsInstance);
        long now = System.currentTimeMillis();
        List<LogEntry> batch = List.of(
                new LogEntry(now, "INFO", "ai.labs.Test", "fine", "production", "agent-nul-log", 1, "conv-nul-log", "user-nul-log", "node-1"),
                new LogEntry(now, "WARN", "ai.labs.Test", "bad\u0000message", "production", "agent-nul-log", 1, "conv-nul-log", "user-nul-log",
                        "node-1"),
                new LogEntry(now, "INFO", "ai.labs.Test", "also fine", "production", "agent-nul-log", 1, "conv-nul-log", "user-nul-log", "node-1"));

        logs.addLogsBatch(batch);

        List<LogEntry> stored = logs.getLogs(Deployment.Environment.production, "agent-nul-log", 1, "conv-nul-log", "user-nul-log", "node-1", 0, 10);
        assertEquals(3, stored.size());
        assertTrue(stored.stream().anyMatch(e -> e.message().equals("bad" + FFFD + "message")));
    }
}
