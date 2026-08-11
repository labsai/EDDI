/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import ai.labs.eddi.configs.groups.ISharedArtifactStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ArtifactConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ArtifactValidator;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ValidatorKind;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.SharedArtifact;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.internal.groups.LiveDiscussionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * I17 — {@link ArtifactTools}: create/read/update/list against a real in-memory
 * store with genuine version-CAS semantics, so the stale-version retry contract
 * and the concurrency claims are exercised for real rather than scripted into a
 * mock.
 *
 * @author tests
 */
class ArtifactToolsTest {

    private static final String GC_ID = "gc-1";
    private static final String AGENT = "agent-a";

    private LiveDiscussionRegistry registry;
    private GroupConversation gc;
    private InMemoryArtifactStore store;

    /**
     * Minimal but honest store: whole-document map with a synchronized version CAS
     * — the same conflict semantics the real backends provide atomically.
     */
    static final class InMemoryArtifactStore implements ISharedArtifactStore {
        final Map<String, SharedArtifact> byId = new ConcurrentHashMap<>();
        final AtomicLong seq = new AtomicLong();
        volatile boolean failing;

        private void maybeFail() throws IResourceStore.ResourceStoreException {
            if (failing) {
                throw new IResourceStore.ResourceStoreException("store down");
            }
        }

        /** Deep-enough copy so callers cannot mutate stored state in place. */
        private static SharedArtifact copy(SharedArtifact a) {
            var c = new SharedArtifact();
            c.setId(a.getId());
            c.setGroupConversationId(a.getGroupConversationId());
            c.setOwnerUserId(a.getOwnerUserId());
            c.setName(a.getName());
            c.setType(a.getType());
            c.setContent(a.getContent());
            c.setVersion(a.getVersion());
            c.setLastEditorAgentId(a.getLastEditorAgentId());
            c.setStatus(a.getStatus());
            c.setHistory(new ArrayList<>(a.getHistory()));
            c.setCreatedAt(a.getCreatedAt());
            c.setUpdatedAt(a.getUpdatedAt());
            return c;
        }

        @Override
        public String create(SharedArtifact artifact) throws IResourceStore.ResourceStoreException {
            maybeFail();
            String id = "art-" + seq.incrementAndGet();
            artifact.setId(id);
            byId.put(id, copy(artifact));
            return id;
        }

        @Override
        public SharedArtifact read(String id) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
            maybeFail();
            SharedArtifact a = byId.get(id);
            if (a == null) {
                throw new IResourceStore.ResourceNotFoundException("Shared artifact not found.");
            }
            return copy(a);
        }

        @Override
        public synchronized void updateIfVersion(SharedArtifact artifact, long expectedVersion)
                throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException {
            maybeFail();
            SharedArtifact current = byId.get(artifact.getId());
            if (current == null) {
                throw new ArtifactGoneException("Shared artifact no longer exists.", null);
            }
            if (current.getVersion() != expectedVersion) {
                throw new IResourceStore.ResourceModifiedException("version conflict");
            }
            byId.put(artifact.getId(), copy(artifact));
        }

        @Override
        public void delete(String id) {
            byId.remove(id);
        }

        @Override
        public List<SharedArtifact> listByGroupConversationId(String groupConversationId) throws IResourceStore.ResourceStoreException {
            maybeFail();
            return byId.values().stream()
                    .filter(a -> groupConversationId.equals(a.getGroupConversationId()))
                    .map(InMemoryArtifactStore::copy)
                    .toList();
        }

        @Override
        public long deleteByGroupConversationId(String groupConversationId) {
            long before = byId.size();
            byId.values().removeIf(a -> groupConversationId.equals(a.getGroupConversationId()));
            return before - byId.size();
        }

        @Override
        public long deleteAllForUser(String userId) {
            long before = byId.size();
            byId.values().removeIf(a -> userId.equals(a.getOwnerUserId()));
            return before - byId.size();
        }
    }

    @BeforeEach
    void setUp() {
        registry = new LiveDiscussionRegistry();
        gc = new GroupConversation();
        gc.setId(GC_ID);
        gc.setGroupId("group-1");
        gc.setUserId("user-1");
        registry.register(gc);
        store = new InMemoryArtifactStore();
    }

    private ArtifactTools tools() {
        return tools(new ArtifactConfig(true, 5, List.of()));
    }

    private ArtifactTools tools(ArtifactConfig config) {
        return new ArtifactTools(registry, GC_ID, config, AGENT, store);
    }

    // =================================================================
    // create
    // =================================================================

    @Test
    @DisplayName("a created artifact lands in the store at v1, owner-stamped, and queues an announce event")
    void create_happyPath() throws Exception {
        String reply = tools().createArtifact("design-doc", "markdown", "# Draft");

        assertTrue(reply.startsWith("Created"), reply);
        List<SharedArtifact> stored = store.listByGroupConversationId(GC_ID);
        assertEquals(1, stored.size());
        SharedArtifact a = stored.get(0);
        assertEquals("design-doc", a.getName());
        assertEquals(SharedArtifact.ArtifactType.MARKDOWN, a.getType());
        assertEquals(1, a.getVersion());
        assertEquals("user-1", a.getOwnerUserId(), "GDPR erasure sweeps by this stamp");
        assertEquals(AGENT, a.getLastEditorAgentId());

        var changes = gc.drainArtifactChanges();
        assertEquals(1, changes.size());
        assertTrue(changes.get(0).created());
        assertEquals("design-doc", changes.get(0).name());
        assertTrue(gc.drainArtifactChanges().isEmpty(), "each change is drained exactly once");
    }

    @Test
    @DisplayName("duplicate names are refused with the way forward, and nothing is stored")
    void create_duplicateName_refused() throws Exception {
        tools().createArtifact("draft", "text", "one");

        String reply = tools().createArtifact("DRAFT", "text", "two");

        assertFalse(reply.startsWith("Created"), reply);
        assertTrue(reply.contains("proposeArtifactUpdate"), "the refusal must teach the retry: " + reply);
        assertEquals(1, store.listByGroupConversationId(GC_ID).size());
    }

    @Test
    @DisplayName("the per-discussion count cap refuses creation past the limit")
    void create_countCap() throws Exception {
        var tools = tools(new ArtifactConfig(true, 2, List.of()));
        tools.createArtifact("one", "text", "1");
        tools.createArtifact("two", "text", "2");

        String reply = tools.createArtifact("three", "text", "3");

        assertFalse(reply.startsWith("Created"), reply);
        assertTrue(reply.contains("limit"), reply);
        assertEquals(2, store.listByGroupConversationId(GC_ID).size());
    }

    @Test
    @DisplayName("bad inputs are refused as sentences: missing name, unknown type, empty content, oversized content")
    void create_inputValidation() throws Exception {
        assertTrue(tools().createArtifact(" ", "text", "x").contains("name"));
        assertTrue(tools().createArtifact("a", "pdf", "x").contains("TEXT, MARKDOWN or JSON"));
        assertTrue(tools().createArtifact("a", "text", " ").contains("content"));
        String oversized = "x".repeat(SharedArtifact.MAX_CONTENT_BYTES + 1);
        assertTrue(tools().createArtifact("a", "text", oversized).contains("KB"));
        assertTrue(store.listByGroupConversationId(GC_ID).isEmpty(), "every refusal must leave the store untouched");
    }

    @Test
    @DisplayName("the configured validator chain gates creation — a failing write stores nothing")
    void create_validatorChain() throws Exception {
        var config = new ArtifactConfig(true, 5, List.of(new ArtifactValidator(ValidatorKind.REGEX, "^# ")));

        String rejected = tools(config).createArtifact("doc", "markdown", "no heading");
        assertTrue(rejected.contains("pattern"), rejected);
        assertTrue(store.listByGroupConversationId(GC_ID).isEmpty());

        String accepted = tools(config).createArtifact("doc", "markdown", "# Heading");
        assertTrue(accepted.startsWith("Created"), accepted);
    }

    @Test
    @DisplayName("an unregistered (paused/finished) discussion refuses writes instead of writing anyway")
    void create_unregisteredDiscussion_refuses() throws Exception {
        registry.unregister(GC_ID);

        String reply = tools().createArtifact("doc", "text", "x");

        assertTrue(reply.contains("finished") || reply.contains("paused"), reply);
        assertTrue(store.byId.isEmpty());
    }

    // =================================================================
    // update (CAS)
    // =================================================================

    @Test
    @DisplayName("an update presenting the version it read is accepted and bumps the version with history")
    void update_happyPath() throws Exception {
        tools().createArtifact("doc", "text", "v1 content");
        gc.drainArtifactChanges();

        String reply = tools().proposeArtifactUpdate("doc", "v2 content", 1, null);

        assertTrue(reply.contains("v2"), reply);
        SharedArtifact a = store.listByGroupConversationId(GC_ID).get(0);
        assertEquals(2, a.getVersion());
        assertEquals("v2 content", a.getContent());
        assertEquals(1, a.getHistory().size(), "the superseded revision rides in the bounded history");
        assertEquals("v1 content", a.getHistory().get(0).content());

        var changes = gc.drainArtifactChanges();
        assertEquals(1, changes.size());
        assertFalse(changes.get(0).created());
        assertEquals(2, changes.get(0).version());
    }

    @Test
    @DisplayName("a stale version gets the plan's re-read-and-merge sentence naming the CURRENT version")
    void update_staleVersion_retrySentence() throws Exception {
        tools().createArtifact("doc", "text", "v1");
        tools().proposeArtifactUpdate("doc", "v2", 1, null);

        String reply = tools().proposeArtifactUpdate("doc", "based on v1", 1, null);

        assertTrue(reply.contains("changed since you read it"), reply);
        assertTrue(reply.contains("now v2"), reply);
        assertEquals("v2", store.listByGroupConversationId(GC_ID).get(0).getContent(), "the stale write must not land");
    }

    @Test
    @DisplayName("markFinal freezes the artifact; further updates are refused")
    void update_finalFreezes() throws Exception {
        tools().createArtifact("doc", "text", "v1");

        String finalized = tools().proposeArtifactUpdate("doc", "final text", 1, true);
        assertTrue(finalized.contains("FINAL"), finalized);

        String afterFinal = tools().proposeArtifactUpdate("doc", "more", 2, null);
        assertTrue(afterFinal.contains("FINAL"), afterFinal);
        assertEquals("final text", store.listByGroupConversationId(GC_ID).get(0).getContent());
    }

    @Test
    @DisplayName("unknown artifacts refuse with the discovery hint; ids from OTHER discussions do not resolve")
    void update_scopedResolution() throws Exception {
        // An artifact of a different discussion — resolvable by raw id only if the
        // tool did a global store read, which would be an IDOR.
        var foreign = new SharedArtifact();
        foreign.setGroupConversationId("gc-other");
        foreign.setOwnerUserId("someone-else");
        foreign.setName("their-doc");
        foreign.setVersion(1);
        String foreignId = store.create(foreign);

        String reply = tools().proposeArtifactUpdate(foreignId, "hijack", 1, null);

        assertTrue(reply.contains("listArtifacts"), reply);
        assertEquals("their-doc", store.read(foreignId).getName());
    }

    @Test
    @DisplayName("two concurrent updates from the same version: exactly one wins, the loser gets the retry sentence")
    void update_concurrentCas_oneWins() throws Exception {
        tools().createArtifact("doc", "text", "base");
        int writers = 2;
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(writers);
        var accepted = new AtomicInteger();
        var retries = new AtomicInteger();

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            final int n = i;
            threads.add(Thread.ofVirtual().unstarted(() -> {
                try {
                    start.await();
                    String reply = tools().proposeArtifactUpdate("doc", "writer-" + n, 1, null);
                    if (reply.startsWith("Updated")) {
                        accepted.incrementAndGet();
                    } else if (reply.contains("changed since you read it")) {
                        retries.incrementAndGet();
                    }
                } catch (Exception e) {
                    // collected via counts — an exception fails the totals below
                } finally {
                    done.countDown();
                }
            }));
        }
        threads.forEach(Thread::start);
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "concurrent update deadlocked");

        assertEquals(1, accepted.get(), "exactly one writer may win a CAS from the same version");
        assertEquals(1, retries.get(), "the loser gets the deterministic retry, not an exception");
        assertEquals(2, store.listByGroupConversationId(GC_ID).get(0).getVersion());
    }

    // =================================================================
    // read / list
    // =================================================================

    @Test
    @DisplayName("readArtifact hands back the version — the CAS token the model needs")
    void read_carriesVersion() throws Exception {
        tools().createArtifact("doc", "json", "{\"a\":1}");

        String reply = tools().readArtifact("doc");

        assertTrue(reply.contains("v1"), reply);
        assertTrue(reply.contains("{\"a\":1}"), reply);
    }

    @Test
    @DisplayName("listArtifacts shows name, type, status and version; empty case teaches createArtifact")
    void list_formats() throws Exception {
        assertTrue(tools().listArtifacts().contains("createArtifact"));

        tools().createArtifact("doc", "text", "x");
        String listing = tools().listArtifacts();
        assertTrue(listing.contains("\"doc\""), listing);
        assertTrue(listing.contains("v1"), listing);
        assertTrue(listing.contains("DRAFT"), listing);
    }

    @Test
    @DisplayName("a store outage yields a try-again sentence, never an exception into the tool loop")
    void storeOutage_refusesGracefully() {
        store.failing = true;

        assertDoesNotThrow(() -> {
            assertTrue(tools().createArtifact("doc", "text", "x").contains("unavailable"));
            assertTrue(tools().listArtifacts().contains("unavailable"));
        });
    }
}
