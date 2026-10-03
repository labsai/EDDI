/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.datastore.IResourceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
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
        verify(store, never()).upsertIfOwnedBy(any(), any());
    }

    @Test
    void rememberFact_shouldRejectEmptyKey() {
        String result = tool.rememberFact("", "value", "fact", "self");
        assertTrue(result.contains("⚠️ Key must not be empty"));
        verifyNoInteractions(store);
    }

    /**
     * H9c: a model writing {@code _gdpr_processing_restricted=true} locked its own
     * user out with a GDPR 403 no admin had applied; as a global entry the same
     * call overwrote an admin's real restriction row in place.
     */
    @Test
    void rememberFact_refusesAReservedGdprKey() {
        String result = tool.rememberFact(" _gdpr_processing_restricted ", "true", "fact", "global");

        assertTrue(result.contains("reserved"), result);
        verifyNoInteractions(store);
    }

    /**
     * H9b: the tool writes straight to the store mid-turn, so a turn cancelled by a
     * GDPR erasure would otherwise recreate memories while its tool loop wound
     * down.
     */
    @Test
    void rememberFact_writesNothingOnceTheTurnIsCancelled() {
        var cancelledTool = new UserMemoryTool(store, "user-1", "agent-1", "conv-1", List.of(), config, () -> true);

        String result = cancelledTool.rememberFact("favorite_color", "blue", "preference", "self");

        assertTrue(result.contains("cancelled"), result);
        verifyNoInteractions(store);
    }

    /** H9c: nor may a model lift a restriction by forgetting the row. */
    @Test
    void forgetFact_refusesAReservedGdprKey() {
        String result = tool.forgetFact("_gdpr_processing_restricted");

        assertTrue(result.contains("reserved"), result);
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

    private static UserMemoryEntry stored(String key, String value, String category, Visibility visibility, String owner, List<String> groups) {
        return new UserMemoryEntry("id-" + key, "user-1", key, value, category, visibility, owner, groups, "conv-0", false, 0, Instant.now(),
                Instant.now());
    }

    /**
     * Live-reproduced on Claude: a user at the cap wanted to correct a fact, and
     * reject mode refused — although an update adds no row.
     */
    @Test
    void rememberFact_updatingAnExistingFactAtTheCap_isAllowedEvenInRejectMode() throws Exception {
        config.setMaxEntriesPerUser(3);
        config.setOnCapReached("reject");
        tool = new UserMemoryTool(store, "user-1", "agent-1", "conv-1", List.of(), config);
        when(store.countEntries("user-1")).thenReturn(3L);
        when(store.getAllEntries("user-1")).thenReturn(List.of(stored("color", "teal", "fact", Visibility.self, "agent-1", List.of()),
                stored("city", "Vienna", "fact", Visibility.self, "agent-1", List.of()),
                stored("pet", "Rex", "fact", Visibility.self, "agent-1", List.of())));

        String update = tool.rememberFact("color", "red", "fact", "self");
        String newFact = tool.rememberFact("food", "pasta", "fact", "self");

        assertTrue(update.contains("✅ Remembered"), update);
        assertTrue(newFact.contains("Memory capacity reached"), "a NEW fact is still refused at the cap: " + newFact);
        verify(store, times(1)).upsert(any());
    }

    /**
     * Live-reproduced on Claude with maxWritesPerTurn=2: the model re-saved the two
     * facts it had already stored (it does not see its earlier tool calls), the
     * re-saves spent the budget, and the new facts were refused — every turn.
     */
    @Test
    void rememberFact_unchangedReSave_isNotAWriteAndSpendsNoBudget() throws Exception {
        config.getGuardrails().setMaxWritesPerTurn(2);
        tool = new UserMemoryTool(store, "user-1", "agent-1", "conv-1", List.of(), config);
        when(store.getAllEntries("user-1")).thenReturn(List.of(stored("name", "Gregor", "fact", Visibility.self, "agent-1", List.of()),
                stored("favorite_color", "teal", "preference", Visibility.self, "agent-1", List.of())));

        String reName = tool.rememberFact("name", "Gregor", "fact", "self");
        String reColor = tool.rememberFact("favorite_color", "teal", "preference", "self");
        String city = tool.rememberFact("city", "Vienna", "fact", "self");
        String dog = tool.rememberFact("dog", "Rex", "fact", "self");

        assertTrue(reName.contains("Already remembered"), reName);
        assertTrue(reColor.contains("Already remembered"), reColor);
        assertTrue(city.contains("✅ Remembered"), city);
        assertTrue(dog.contains("✅ Remembered"), dog);
        verify(store, times(2)).upsert(any());
    }

    @Test
    void rememberFact_changedValueOrVisibility_isAWrite() throws Exception {
        // group writes must be permitted explicitly — the default is self only
        config.getGuardrails().setAllowedVisibilities(List.of("self", "group"));
        when(store.getAllEntries("user-1")).thenReturn(List.of(stored("lang", "German", "preference", Visibility.self, "agent-1", List.of())));

        assertTrue(tool.rememberFact("lang", "English", "preference", "self").contains("✅ Remembered"));
        assertTrue(tool.rememberFact("lang", "German", "preference", "group").contains("✅ Remembered"), "self → group is a change");
        verify(store, times(2)).upsert(any());
    }

    @Test
    void rememberFact_sameGroupValueForAnotherGroup_isAWrite() throws Exception {
        config.getGuardrails().setAllowedVisibilities(List.of("self", "group"));
        tool = new UserMemoryTool(store, "user-1", "agent-1", "conv-1", List.of("team-2"), config);
        when(store.getAllEntries("user-1")).thenReturn(List.of(stored("goal", "ship", "fact", Visibility.group, "agent-1", List.of("team-1"))));

        assertTrue(tool.rememberFact("goal", "ship", "fact", "group").contains("✅ Remembered"));
    }

    @Test
    void rememberFact_writeLimitMessageTellsTheModelNotToRetry() throws Exception {
        config.getGuardrails().setMaxWritesPerTurn(1);
        tool = new UserMemoryTool(store, "user-1", "agent-1", "conv-1", List.of(), config);

        tool.rememberFact("a", "1", "fact", "self");
        String refused = tool.rememberFact("b", "2", "fact", "self");

        assertTrue(refused.contains("Do not retry this turn"), refused);
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

    // --- Finding 5: visibility guardrail ---

    @Test
    void rememberFact_defaultConfig_refusesGlobalVisibility() throws Exception {
        // Default allowedVisibilities = [self]: the model cannot broadcast a memory
        // globally unless the agent is explicitly configured to allow it.
        String result = tool.rememberFact("shared_key", "value", "fact", "global");

        assertTrue(result.contains("not permitted"), "expected a visibility refusal, got: " + result);
        verify(store, never()).upsert(any());
    }

    @Test
    void rememberFact_globalAllowed_butKeyOwnedByAnotherAgent_refused() throws Exception {
        config.getGuardrails().setAllowedVisibilities(List.of("self", "global"));
        var otherAgentsGlobal = new UserMemoryEntry("id-x", "user-1", "shared_key", "old", "fact",
                Visibility.global, "agent-OTHER", List.of(), "conv-x", false, 0, Instant.now(), Instant.now());
        when(store.getAllEntries("user-1")).thenReturn(List.of(otherAgentsGlobal));

        String result = tool.rememberFact("shared_key", "new value", "fact", "global");

        assertTrue(result.contains("owned by another agent"), "expected a cross-agent refusal, got: " + result);
        verify(store, never()).upsert(any());
        verify(store, never()).upsertIfOwnedBy(any(), any());
    }

    @Test
    void rememberFact_globalAllowed_ownKey_succeeds() throws Exception {
        config.getGuardrails().setAllowedVisibilities(List.of("self", "global"));
        when(store.getAllEntries("user-1")).thenReturn(List.of());
        when(store.countEntries("user-1")).thenReturn(0L);
        when(store.upsertIfOwnedBy(any(), eq("agent-1"))).thenReturn(true);

        String result = tool.rememberFact("my_key", "value", "fact", "global");

        assertTrue(result.contains("✅ Remembered"), "expected success, got: " + result);
        // The ownership decision is made by the store's conditional write, not the
        // read-then-upsert that raced.
        ArgumentCaptor<UserMemoryEntry> captor = ArgumentCaptor.forClass(UserMemoryEntry.class);
        verify(store).upsertIfOwnedBy(captor.capture(), eq("agent-1"));
        assertEquals("agent-1", captor.getValue().sourceAgentId());
        assertEquals(Visibility.global, captor.getValue().visibility());
        verify(store, never()).upsert(any());
    }

    @Test
    void rememberFact_globalKeyClaimedConcurrently_refused() throws Exception {
        // Both agents saw the key as free; the other agent's write landed first, so
        // the store's owner-conditional write applies nothing.
        config.getGuardrails().setAllowedVisibilities(List.of("self", "global"));
        when(store.getAllEntries("user-1")).thenReturn(List.of());
        when(store.countEntries("user-1")).thenReturn(0L);
        when(store.upsertIfOwnedBy(any(), eq("agent-1"))).thenReturn(false);

        String result = tool.rememberFact("shared_key", "value", "fact", "global");

        assertTrue(result.contains("owned by another agent"), "expected a cross-agent refusal, got: " + result);
        assertFalse(result.contains("✅"), result);
        verify(store, never()).upsert(any());
    }

    @Test
    void rememberFact_refusedGlobalWrite_doesNotUseUpTheTurnBudget() throws Exception {
        config.getGuardrails().setAllowedVisibilities(List.of("self", "global"));
        config.getGuardrails().setMaxWritesPerTurn(1);
        when(store.getAllEntries("user-1")).thenReturn(List.of());
        when(store.countEntries("user-1")).thenReturn(0L);
        when(store.upsertIfOwnedBy(any(), eq("agent-1"))).thenReturn(false);
        when(store.upsert(any())).thenReturn("entry-id");

        tool.rememberFact("shared_key", "value", "fact", "global");
        String second = tool.rememberFact("own_key", "value", "fact", "self");

        assertTrue(second.contains("✅ Remembered"), "a refused write must not count against the turn, got: " + second);
    }

    @Test
    void rememberFact_globalWithoutAgentIdentity_refused() throws Exception {
        // With no agent id there is no owner to write as, so the conditional write
        // cannot be made; refuse rather than store an ownerless global entry.
        config.getGuardrails().setAllowedVisibilities(List.of("self", "global"));
        var anonymous = new UserMemoryTool(store, "user-1", null, "conv-1", List.of(), config);

        String result = anonymous.rememberFact("shared_key", "value", "fact", "global");

        assertTrue(result.contains("owned by another agent"), "expected a refusal, got: " + result);
        verify(store, never()).upsert(any());
        verify(store, never()).upsertIfOwnedBy(any(), any());
    }

    @Test
    void rememberFact_appliesConfiguredDefaultVisibility_whenModelOmitsIt() throws Exception {
        // A configured defaultVisibility is honoured for a null choice AND is always
        // permitted (unioned into the allowed set) so it is never self-blocking.
        config.setDefaultVisibility("group");
        when(store.countEntries("user-1")).thenReturn(0L);
        when(store.upsert(any())).thenReturn("entry-id");

        String result = tool.rememberFact("team_key", "value", "fact", null);

        assertTrue(result.contains("✅ Remembered"), "expected success, got: " + result);
        ArgumentCaptor<UserMemoryEntry> captor = ArgumentCaptor.forClass(UserMemoryEntry.class);
        verify(store).upsert(captor.capture());
        assertEquals(Visibility.group, captor.getValue().visibility());
    }

    @Test
    void rememberFact_globalAllowed_butExistingKeyHasUnknownOwner_refused() throws Exception {
        // A legacy/migrated global entry with no sourceAgentId cannot be shown to be
        // this agent's, so the overwrite fails closed.
        config.getGuardrails().setAllowedVisibilities(List.of("self", "global"));
        var ownerlessGlobal = new UserMemoryEntry("id-x", "user-1", "shared_key", "old", "fact",
                Visibility.global, null, List.of(), "conv-x", false, 0, Instant.now(), Instant.now());
        when(store.getAllEntries("user-1")).thenReturn(List.of(ownerlessGlobal));

        String result = tool.rememberFact("shared_key", "new value", "fact", "global");

        assertTrue(result.contains("owner is unknown"), "expected an unknown-owner refusal, got: " + result);
        verify(store, never()).upsert(any());
        verify(store, never()).upsertIfOwnedBy(any(), any());
    }

    @Test
    void rememberFact_unknownOwner_allowedWhenOverwriteEnabled() throws Exception {
        config.getGuardrails().setAllowedVisibilities(List.of("self", "global"));
        config.getGuardrails().setAllowGlobalKeyOverwrite(true);
        var ownerlessGlobal = new UserMemoryEntry("id-x", "user-1", "shared_key", "old", "fact",
                Visibility.global, null, List.of(), "conv-x", false, 0, Instant.now(), Instant.now());
        lenient().when(store.getAllEntries("user-1")).thenReturn(List.of(ownerlessGlobal));
        when(store.countEntries("user-1")).thenReturn(0L);
        when(store.upsert(any())).thenReturn("entry-id");

        String result = tool.rememberFact("shared_key", "new value", "fact", "global");

        assertTrue(result.contains("✅ Remembered"), "expected success, got: " + result);
        // allowGlobalKeyOverwrite keeps the plain upsert: the operator opted in to
        // cross-agent overwrites, so there is no ownership to enforce.
        verify(store).upsert(any());
        verify(store, never()).upsertIfOwnedBy(any(), any());
    }

    // === Concurrent inserts at the cap (enforceCapacity race) ===

    private static UserMemoryEntry storedAt(String key, String owner, Instant createdAt) {
        return new UserMemoryEntry("id-" + key, "user-1", key, "v", "fact", Visibility.self, owner, List.of(), "conv-0", false, 0, createdAt,
                createdAt);
    }

    private List<UserMemoryEntry> fourExisting(Instant base) {
        return List.of(storedAt("a", "agent-1", base.minusSeconds(40)), storedAt("b", "agent-1", base.minusSeconds(30)),
                storedAt("c", "agent-1", base.minusSeconds(20)), storedAt("d", "agent-1", base.minusSeconds(10)));
    }

    /**
     * Two conversations of one user at cap - 1 both passed the count check and both
     * inserted, leaving the user above the cap for good. The write that is over the
     * cap — the newest — is now undone in reject mode.
     */
    @Test
    void rememberFact_concurrentInsertOvershootsTheCap_rejectModeUndoesTheNewestInsert() throws Exception {
        config.setMaxEntriesPerUser(5);
        config.setOnCapReached("reject");
        tool = new UserMemoryTool(store, "user-1", "agent-1", "conv-1", List.of(), config);
        Instant base = Instant.now();
        var after = new ArrayList<>(fourExisting(base));
        after.add(storedAt("drink", "agent-2", base.minusSeconds(1))); // the other conversation's insert
        after.add(storedAt("food", "agent-1", base)); // ours, the newest
        when(store.getAllEntries("user-1")).thenReturn(fourExisting(base), after);
        when(store.countEntries("user-1")).thenReturn(4L, 6L);
        when(store.upsert(any())).thenReturn("id-food");

        String result = tool.rememberFact("food", "pasta", "fact", "self");

        assertTrue(result.contains("NOT saved"), result);
        verify(store).deleteEntry("id-food");
        verify(store, never()).deleteEntry("id-drink");
    }

    @Test
    void rememberFact_concurrentInsertOvershootsTheCap_theOlderInsertIsKept() throws Exception {
        config.setMaxEntriesPerUser(5);
        config.setOnCapReached("reject");
        tool = new UserMemoryTool(store, "user-1", "agent-1", "conv-1", List.of(), config);
        Instant base = Instant.now();
        var after = new ArrayList<>(fourExisting(base));
        after.add(storedAt("food", "agent-1", base.minusSeconds(1))); // ours landed first
        after.add(storedAt("drink", "agent-2", base)); // the other one is over the cap and settles it
        when(store.getAllEntries("user-1")).thenReturn(fourExisting(base), after);
        when(store.countEntries("user-1")).thenReturn(4L, 6L);
        when(store.upsert(any())).thenReturn("id-food");

        String result = tool.rememberFact("food", "pasta", "fact", "self");

        assertTrue(result.contains("✅ Remembered"), result);
        verify(store, never()).deleteEntry(anyString());
    }

    @Test
    void rememberFact_concurrentInsertOvershootsTheCap_evictModeEvictsTheOldestOwnEntry() throws Exception {
        config.setMaxEntriesPerUser(5);
        config.setOnCapReached("evict_oldest");
        tool = new UserMemoryTool(store, "user-1", "agent-1", "conv-1", List.of(), config);
        Instant base = Instant.now();
        var after = new ArrayList<>(fourExisting(base));
        after.add(storedAt("drink", "agent-2", base.minusSeconds(1)));
        after.add(storedAt("food", "agent-1", base));
        when(store.getAllEntries("user-1")).thenReturn(fourExisting(base), after);
        when(store.countEntries("user-1")).thenReturn(4L, 6L);
        when(store.upsert(any())).thenReturn("id-food");

        String result = tool.rememberFact("food", "pasta", "fact", "self");

        assertTrue(result.contains("✅ Remembered"), result);
        verify(store).deleteEntry("id-a");
        verify(store, never()).deleteEntry("id-food");
    }
}
