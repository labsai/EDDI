package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.datastore.IResourceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link UserMemoryTool}.
 */
class UserMemoryToolTest {

    private IUserMemoryStore store;
    private UserMemoryTool tool;
    private AgentConfiguration.UserMemoryConfig config;

    @BeforeEach
    void setUp() {
        store = mock(IUserMemoryStore.class);
        config = new AgentConfiguration.UserMemoryConfig();
        // Use defaults (maxKeyLength=100, maxValueLength=1000, maxWritesPerTurn=10,
        // maxEntriesPerUser=500)
        tool = new UserMemoryTool(store, "user-1", "agent-1", "conv-1", List.of(), config);
    }

    @Test
    void rememberFact_shouldStoreEntrySuccessfully() throws Exception {
        when(store.countEntries("user-1")).thenReturn(0L);
        when(store.upsert(any())).thenReturn("entry-id-1");

        String result = tool.rememberFact("favorite_color", "blue", "preference", "self");

        assertTrue(result.contains("✅ Remembered"));
        assertTrue(result.contains("favorite_color"));
        verify(store).upsert(any(UserMemoryEntry.class));
    }

    @Test
    void rememberFact_shouldRejectEmptyKey() {
        String result = tool.rememberFact("", "value", "fact", "self");
        assertTrue(result.contains("⚠️ Key must not be empty"));
        verifyNoInteractions(store);
    }

    @Test
    void rememberFact_shouldRejectKeyTooLong() {
        String longKey = "a".repeat(101);
        String result = tool.rememberFact(longKey, "value", "fact", "self");
        assertTrue(result.contains("⚠️ Key too long"));
        verifyNoInteractions(store);
    }

    @Test
    void rememberFact_shouldRejectValueTooLong() throws Exception {
        when(store.countEntries("user-1")).thenReturn(0L);
        String longValue = "a".repeat(1001);
        String result = tool.rememberFact("key", longValue, "fact", "self");
        assertTrue(result.contains("⚠️ Value too long"));
    }

    @Test
    void rememberFact_shouldEnforceWriteRateLimit() throws Exception {
        when(store.countEntries("user-1")).thenReturn(0L);
        when(store.upsert(any())).thenReturn("entry-id");

        // Use maxWritesPerTurn=10 (default)
        for (int i = 0; i < 10; i++) {
            tool.rememberFact("key-" + i, "value", "fact", "self");
        }

        // 11th write should be rejected
        String result = tool.rememberFact("key-11", "value", "fact", "self");
        assertTrue(result.contains("⚠️ Maximum writes per turn"));
        verify(store, times(10)).upsert(any());
    }

    @Test
    void rememberFact_shouldDefaultUnknownCategoryToFact() throws Exception {
        when(store.countEntries("user-1")).thenReturn(0L);
        when(store.upsert(any())).thenReturn("entry-id");

        String result = tool.rememberFact("key", "value", "unknown_category", "self");
        assertTrue(result.contains("✅ Remembered"));
        assertTrue(result.contains("fact"));
    }

    @Test
    void rememberFact_shouldRejectWhenCapReachedAndPolicyIsReject() throws Exception {
        config.setMaxEntriesPerUser(5);
        config.setOnCapReached("reject");
        tool = new UserMemoryTool(store, "user-1", "agent-1", "conv-1", List.of(), config);

        when(store.countEntries("user-1")).thenReturn(5L);

        String result = tool.rememberFact("key", "value", "fact", "self");
        assertTrue(result.contains("⚠️ Memory capacity reached"));
        verify(store, never()).upsert(any());
    }

    @Test
    void rememberFact_shouldDefaultVisibilityToSelfOnInvalidInput() throws Exception {
        when(store.countEntries("user-1")).thenReturn(0L);
        when(store.upsert(any())).thenReturn("entry-id");

        String result = tool.rememberFact("key", "value", "fact", "invalid_visibility");
        assertTrue(result.contains("✅ Remembered"));
        assertTrue(result.contains("self"));
    }

    @Test
    void recallMemories_shouldReturnFormattedEntries() throws Exception {
        var entries = List.of(
                new UserMemoryEntry("1", "user-1", "name", "Alice", "fact", Visibility.self, "agent-1", List.of(), "conv-1", false, 5, Instant.now(),
                        Instant.now()),
                new UserMemoryEntry("2", "user-1", "color", "blue", "preference", Visibility.global, "agent-1", List.of(), "conv-1", false, 3,
                        Instant.now(), Instant.now()));
        when(store.getVisibleEntries("user-1", "agent-1", List.of(), "most_recent", 50)).thenReturn(entries);

        String result = tool.recallMemories();

        assertTrue(result.contains("name = Alice"));
        assertTrue(result.contains("color = blue"));
    }

    @Test
    void recallMemories_shouldReturnEmptyMessage() throws Exception {
        when(store.getVisibleEntries("user-1", "agent-1", List.of(), "most_recent", 50)).thenReturn(List.of());

        String result = tool.recallMemories();
        assertTrue(result.contains("No memories found"));
    }

    @Test
    void forgetFact_shouldDeleteExistingEntry() throws Exception {
        var entry = new UserMemoryEntry("entry-1", "user-1", "name", "Alice", "fact", Visibility.self, "agent-1", List.of(), "conv-1", false, 0,
                Instant.now(), Instant.now());
        when(store.getByKey("user-1", "name")).thenReturn(Optional.of(entry));

        String result = tool.forgetFact("name");

        assertTrue(result.contains("✅ Forgotten: name"));
        verify(store).deleteEntry("entry-1");
    }

    @Test
    void forgetFact_shouldReportMissingKey() throws Exception {
        when(store.getByKey("user-1", "nonexistent")).thenReturn(Optional.empty());

        String result = tool.forgetFact("nonexistent");
        assertTrue(result.contains("No memory with key 'nonexistent' found"));
        verify(store, never()).deleteEntry(any());
    }

    @Test
    void forgetFact_shouldRejectEmptyKey() {
        String result = tool.forgetFact("");
        assertTrue(result.contains("⚠️ Key must not be empty"));
        verifyNoInteractions(store);
    }

    @Test
    void searchMemory_shouldReturnMatchingEntries() throws Exception {
        var entries = List.of(new UserMemoryEntry("1", "user-1", "language", "English", "preference", Visibility.self, "agent-1", List.of(), "conv-1",
                false, 1, Instant.now(), Instant.now()));
        when(store.filterEntries("user-1", "lang")).thenReturn(entries);

        String result = tool.searchMemory("lang");
        assertTrue(result.contains("language = English"));
    }

    @Test
    void searchMemory_shouldReturnNoResultsMessage() throws Exception {
        when(store.filterEntries("user-1", "xyz")).thenReturn(List.of());

        String result = tool.searchMemory("xyz");
        assertTrue(result.contains("No memories matching 'xyz' found"));
    }

    @Test
    void rememberFact_shouldHandleStoreException() throws Exception {
        when(store.countEntries("user-1")).thenReturn(0L);
        when(store.upsert(any())).thenThrow(new IResourceStore.ResourceStoreException("DB error"));

        String result = tool.rememberFact("key", "value", "fact", "self");
        assertTrue(result.contains("❌ Failed to store memory"));
    }
}
