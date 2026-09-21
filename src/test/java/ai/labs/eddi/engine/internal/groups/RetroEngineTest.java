/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.RetroConfig;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Properties;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionEventListener;
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink;
import ai.labs.eddi.datastore.IResourceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

/**
 * I8 — {@link RetroEngine}: the three-tier lesson parse, the per-run cap
 * enforced regardless of what the model was told, idempotent upserts under the
 * team owner, the FIFO stored-cap eviction, and the retro_recorded event. The
 * store is a real in-memory fake with the production upsert identity
 * {@code (userId, key, sourceAgentId)} — idempotency claims scripted into a
 * mock would prove nothing.
 *
 * @author tests
 */
class RetroEngineTest {

    private static final String GROUP_ID = "group-1";
    private static final String TEAM_OWNER = IUserMemoryStore.TEAM_OWNER_PREFIX + GROUP_ID;

    private InMemoryUserMemoryStore store;
    private GroupConversation gc;

    /** Honest fake: production upsert identity, recency-ordered recall. */
    static final class InMemoryUserMemoryStore implements IUserMemoryStore {
        final Map<String, UserMemoryEntry> byId = new LinkedHashMap<>();
        final AtomicLong seq = new AtomicLong();
        final AtomicLong clock = new AtomicLong();

        @Override
        public String upsert(UserMemoryEntry entry) {
            String identity = entry.userId() + "|" + entry.key() + "|" + entry.sourceAgentId();
            for (Map.Entry<String, UserMemoryEntry> existing : byId.entrySet()) {
                UserMemoryEntry e = existing.getValue();
                if (identity.equals(e.userId() + "|" + e.key() + "|" + e.sourceAgentId())) {
                    byId.put(existing.getKey(), withId(entry, existing.getKey(), e.createdAt()));
                    return existing.getKey();
                }
            }
            String id = "m-" + seq.incrementAndGet();
            byId.put(id, withId(entry, id, Instant.ofEpochMilli(clock.incrementAndGet())));
            return id;
        }

        private UserMemoryEntry withId(UserMemoryEntry e, String id, Instant createdAt) {
            return new UserMemoryEntry(id, e.userId(), e.key(), e.value(), e.category(), e.visibility(), e.sourceAgentId(),
                    e.groupIds(), e.sourceConversationId(), e.conflicted(), e.accessCount(), createdAt,
                    Instant.ofEpochMilli(clock.incrementAndGet()));
        }

        @Override
        public void deleteEntry(String entryId) {
            byId.remove(entryId);
        }

        @Override
        public List<UserMemoryEntry> getVisibleEntries(String userId, String agentId, List<String> groupIds, String recallOrder,
                                                       int maxEntries) {
            List<UserMemoryEntry> visible = new ArrayList<>(byId.values().stream()
                    .filter(e -> userId.equals(e.userId()))
                    .sorted(Comparator.comparing(UserMemoryEntry::updatedAt).reversed())
                    .toList());
            return maxEntries > 0 && visible.size() > maxEntries ? visible.subList(0, maxEntries) : visible;
        }

        // Unused surface.
        @Override
        public Properties readProperties(String userId) {
            return null;
        }

        @Override
        public void mergeProperties(String userId, Properties properties) {
        }

        @Override
        public void deleteProperties(String userId) {
        }

        @Override
        public Optional<UserMemoryEntry> findEntryById(String entryId) {
            return Optional.ofNullable(byId.get(entryId));
        }

        @Override
        public List<UserMemoryEntry> filterEntries(String userId, String query) {
            return List.of();
        }

        @Override
        public List<UserMemoryEntry> getEntriesByCategory(String userId, String category) {
            return List.of();
        }

        @Override
        public Optional<UserMemoryEntry> getByKey(String userId, String key) {
            return Optional.empty();
        }

        @Override
        public List<UserMemoryEntry> getAllEntries(String userId) {
            return List.copyOf(byId.values());
        }

        @Override
        public void deleteAllForUser(String userId) {
        }

        @Override
        public long countEntries(String userId) {
            return byId.size();
        }

        @Override
        public long deleteOlderThan(int olderThanDays) {
            return 0;
        }
    }

    @BeforeEach
    void setUp() {
        store = new InMemoryUserMemoryStore();
        gc = new GroupConversation();
        gc.setId("gc-1");
        gc.setGroupId(GROUP_ID);
    }

    private TranscriptEntry retroEntry(String content) {
        return new TranscriptEntry("mod", "Moderator", content, 0, "Retro", TranscriptEntryType.RETRO, Instant.now(), null, null);
    }

    // =================================================================
    // parsing
    // =================================================================

    @Test
    @DisplayName("strict JSON and fence-embedded JSON both parse; prose yields nothing")
    void parse_tiers() {
        assertEquals(2, RetroEngine.parseLessons(
                "{\"lessons\":[{\"lesson\":\"Cap debate rounds\",\"context\":\"long debates\"},{\"lesson\":\"Name a moderator\"}]}", 3).size());
        assertEquals(1, RetroEngine.parseLessons(
                "Here you go:\n```json\n{\"lessons\":[{\"lesson\":\"Vote earlier\"}]}\n```", 3).size());
        assertTrue(RetroEngine.parseLessons("We should have voted earlier, honestly.", 3).isEmpty(),
                "prose is not guessed into a lesson");
        assertTrue(RetroEngine.parseLessons(null, 3).isEmpty());
    }

    @Test
    @DisplayName("the per-run cap is enforced at parse time, whatever the model produced")
    void parse_capEnforced() {
        String five = "{\"lessons\":[{\"lesson\":\"a\"},{\"lesson\":\"b\"},{\"lesson\":\"c\"},{\"lesson\":\"d\"},{\"lesson\":\"e\"}]}";

        assertEquals(2, RetroEngine.parseLessons(five, 2).size());
    }

    // =================================================================
    // harvest
    // =================================================================

    @Test
    @DisplayName("lessons land team-owned with group visibility, and re-running the same retro is a no-op")
    void harvest_idempotentTeamOwnedUpsert() {
        var entries = List.of(retroEntry("{\"lessons\":[{\"lesson\":\"Cap debate rounds\",\"context\":\"long debates\"}]}"));

        RetroEngine.harvest(gc, new RetroConfig(), entries, store, "Retro", null);
        RetroEngine.harvest(gc, new RetroConfig(), entries, store, "Retro", null);

        assertEquals(1, store.byId.size(), "the idempotency key + fixed source agent make a re-run a no-op");
        UserMemoryEntry lesson = store.byId.values().iterator().next();
        assertEquals(TEAM_OWNER, lesson.userId(), "owned by the team, not the human who ran the discussion");
        assertTrue(lesson.key().startsWith(RetroEngine.KEY_PREFIX));
        assertEquals(List.of(GROUP_ID), lesson.groupIds());
        assertEquals(RetroEngine.RETRO_SOURCE, lesson.sourceAgentId());
        assertTrue(lesson.value().toString().contains("Cap debate rounds"));
        assertTrue(lesson.value().toString().contains("long debates"), "the context rides in the value");
    }

    @Test
    @DisplayName("FIFO at maxStoredLessons: storing past the cap evicts the oldest, never the newest")
    void harvest_fifoEviction() {
        var config = new RetroConfig(3, 2);
        RetroEngine.harvest(gc, config, List.of(retroEntry("{\"lessons\":[{\"lesson\":\"first oldest\"}]}")), store, "Retro", null);
        RetroEngine.harvest(gc, config, List.of(retroEntry("{\"lessons\":[{\"lesson\":\"second\"}]}")), store, "Retro", null);
        RetroEngine.harvest(gc, config, List.of(retroEntry("{\"lessons\":[{\"lesson\":\"third newest\"}]}")), store, "Retro", null);

        assertEquals(2, store.byId.size(), "the 3rd stored lesson evicts the oldest");
        List<String> values = store.byId.values().stream().map(e -> e.value().toString()).toList();
        assertTrue(values.stream().anyMatch(v -> v.contains("third newest")));
        assertTrue(values.stream().noneMatch(v -> v.contains("first oldest")), "FIFO evicts the OLDEST");
    }

    @Test
    @DisplayName("FIFO survives a reharvest: eviction orders by CREATION, not by the refreshed updatedAt")
    void harvest_fifoEviction_reharvestDoesNotShieldOldLessons() throws Exception {
        var config = new RetroConfig(3, 2);
        RetroEngine.harvest(gc, config, List.of(retroEntry("{\"lessons\":[{\"lesson\":\"alpha oldest\"}]}")), store, "Retro", null);
        Thread.sleep(5);
        RetroEngine.harvest(gc, config, List.of(retroEntry("{\"lessons\":[{\"lesson\":\"beta middle\"}]}")), store, "Retro", null);
        Thread.sleep(5);
        // Reharvest alpha: its updatedAt refreshes, its createdAt does not. The
        // recall order (most_recent = updatedAt) now lists alpha first — eviction
        // keyed on recall order would evict beta, keeping the OLDER lesson.
        RetroEngine.harvest(gc, config, List.of(retroEntry("{\"lessons\":[{\"lesson\":\"alpha oldest\"}]}")), store, "Retro", null);
        Thread.sleep(5);
        RetroEngine.harvest(gc, config, List.of(retroEntry("{\"lessons\":[{\"lesson\":\"gamma newest\"}]}")), store, "Retro", null);

        assertEquals(2, store.byId.size());
        List<String> values = store.byId.values().stream().map(e -> e.value().toString()).toList();
        assertTrue(values.stream().noneMatch(v -> v.contains("alpha oldest")),
                "FIFO evicts the oldest-CREATED lesson even after a reharvest refreshed it: " + values);
        assertTrue(values.stream().anyMatch(v -> v.contains("beta middle")), values.toString());
        assertTrue(values.stream().anyMatch(v -> v.contains("gamma newest")), values.toString());
    }

    @Test
    @DisplayName("RetroConfig clamps runaway values to hard ceilings — bounded growth is non-negotiable")
    void retroConfig_ceilingsClampRunawayValues() {
        var runaway = new RetroConfig(Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertEquals(RetroConfig.CEILING_MAX_PER_RUN, runaway.maxLessonsPerRun());
        assertEquals(RetroConfig.CEILING_MAX_STORED, runaway.maxStoredLessons());

        var sane = new RetroConfig(5, 100);
        assertEquals(5, sane.maxLessonsPerRun(), "values under the ceiling pass through");
        assertEquals(100, sane.maxStoredLessons());
    }

    @Test
    @DisplayName("retro_recorded fires with the stored count; a null store warns and never throws")
    void harvest_eventAndNullStore() {
        var listener = Mockito.mock(GroupDiscussionEventListener.class);

        RetroEngine.harvest(gc, new RetroConfig(),
                List.of(retroEntry("{\"lessons\":[{\"lesson\":\"a\"},{\"lesson\":\"b\"}]}")), store, "Retro", listener);

        var captor = ArgumentCaptor.forClass(GroupConversationEventSink.RetroRecordedEvent.class);
        verify(listener).onRetroRecorded(captor.capture());
        assertEquals(2, captor.getValue().lessonsStored());
        assertEquals(GROUP_ID, captor.getValue().groupId());

        assertDoesNotThrow(() -> RetroEngine.harvest(gc, new RetroConfig(),
                List.of(retroEntry("{\"lessons\":[{\"lesson\":\"x\"}]}")), null, "Retro", null));
    }

    @Test
    @DisplayName("a null store still fires retro_recorded with zero — the event contract holds when nothing persists")
    void harvest_nullStore_firesZeroCountEvent() {
        var listener = Mockito.mock(GroupDiscussionEventListener.class);

        RetroEngine.harvest(gc, new RetroConfig(),
                List.of(retroEntry("{\"lessons\":[{\"lesson\":\"x\"}]}")), null, "Retro", listener);

        var captor = ArgumentCaptor.forClass(GroupConversationEventSink.RetroRecordedEvent.class);
        verify(listener).onRetroRecorded(captor.capture());
        assertEquals(0, captor.getValue().lessonsStored());
    }

    @Test
    @DisplayName("maxLessonsPerRun bounds the whole harvest, not each entry — multi-speaker retros cannot multiply it")
    void harvest_capIsPerHarvest_notPerEntry() throws Exception {
        var config = new RetroConfig(2, 50);

        RetroEngine.harvest(gc, config, List.of(
                retroEntry("{\"lessons\":[{\"lesson\":\"one\"},{\"lesson\":\"two\"}]}"),
                retroEntry("{\"lessons\":[{\"lesson\":\"three\"},{\"lesson\":\"four\"}]}"),
                retroEntry("{\"lessons\":[{\"lesson\":\"five\"}]}")), store, "Retro", null);

        assertEquals(2, store.countEntries(IUserMemoryStore.TEAM_OWNER_PREFIX + GROUP_ID),
                "3 RETRO entries × cap 2 used to store up to 6 — the cap is per harvest");
    }

    @Test
    @DisplayName("a failing store loses that lesson with a warning — never the discussion")
    void harvest_storeFailure_neverThrows() throws IResourceStore.ResourceStoreException {
        var failing = Mockito.mock(IUserMemoryStore.class);
        Mockito.when(failing.upsert(Mockito.any())).thenThrow(new IResourceStore.ResourceStoreException("store down"));

        assertDoesNotThrow(() -> RetroEngine.harvest(gc, new RetroConfig(),
                List.of(retroEntry("{\"lessons\":[{\"lesson\":\"x\"}]}")), failing, "Retro", null));
    }
}
