/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.rest;

import ai.labs.eddi.datastore.mongo.MongoTestBase;
import ai.labs.eddi.datastore.postgres.PostgresTestBase;
import ai.labs.eddi.datastore.mongo.codec.JacksonProvider;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.memory.model.ConversationState;
import jakarta.ws.rs.WebApplicationException;
import org.bson.codecs.configuration.CodecRegistries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static ai.labs.eddi.engine.memory.model.ConversationState.ENDED;
import static ai.labs.eddi.engine.memory.model.ConversationState.READY;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code conversationState} filter of {@code GET
 * /conversationstore/conversations} against a real MongoDB and a real
 * PostgreSQL (#931).
 * <p>
 * The state lives in the conversation memory, so the listing reads the ids in
 * that state off the memory store's state index and pages the descriptor query
 * through those alone. Every test compares that path with the per-row filter it
 * replaces (the path a state too common for the id set still takes), so the two
 * can never disagree on which conversations a page holds.
 */
@DisplayName("Conversation listing by state - MongoDB and PostgreSQL alike")
class ConversationListingStateFilterParityTest extends MongoTestBase {

    private static final Map<String, ListingBackend> BACKENDS = new ConcurrentHashMap<>();

    static Stream<Arguments> backends() {
        Supplier<ListingBackend> mongo = () -> BACKENDS.computeIfAbsent("mongo",
                ignored -> new ListingBackend.Mongo(getDatabase(), getDatabase().withCodecRegistry(CodecRegistries.fromRegistries(
                        getDatabase().getCodecRegistry(), CodecRegistries.fromProviders(new JacksonProvider(objectMapper))))));
        Supplier<ListingBackend> postgres = () -> BACKENDS.computeIfAbsent("postgres",
                ignored -> new ListingBackend.Postgres(PostgresTestBase.createDataSourceInstance()));
        return Stream.of(Arguments.of("mongo", mongo), Arguments.of("postgres", postgres));
    }

    private static ListingBackend fresh(Supplier<ListingBackend> factory) throws Exception {
        ListingBackend backend = factory.get();
        backend.clear();
        return backend;
    }

    /** The listing with the state pushed into the query (the default). */
    private static RestConversationStore byIdSet(ListingBackend backend, boolean seesAll) {
        return backend.listing(seesAll);
    }

    /** The listing with the state filtered row by row, as before #931. */
    private static RestConversationStore rowByRow(ListingBackend backend, boolean seesAll) {
        RestConversationStore listing = backend.listing(seesAll);
        listing.maxStateIdSet = -1;
        return listing;
    }

    private static List<String> ids(List<ConversationDescriptor> page) {
        return page.stream().map(d -> {
            String path = d.getResource().getPath();
            return path.substring(path.lastIndexOf('/') + 1);
        }).toList();
    }

    /** The rows a listing must return, newest (highest index) first. */
    private static List<String> expected(List<String> ids, IntPredicate matches) {
        List<String> expected = new ArrayList<>();
        for (int i = ids.size() - 1; i >= 0; i--) {
            if (matches.test(i)) {
                expected.add(ids.get(i));
            }
        }
        return expected;
    }

    private static List<String> page(RestConversationStore listing, int index, int limit, ConversationState state) {
        return ids(listing.readConversationDescriptors(index, limit, null, null, null, null, state, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    @DisplayName("sparse matches: every page is exact, and the same as the per-row filter's")
    void sparseMatchesPageExactly(String name, Supplier<ListingBackend> factory) throws Exception {
        ListingBackend backend = fresh(factory);
        IntPredicate ended = i -> i % 17 == 3;
        List<String> ids = backend.seed(120, i -> ended.test(i) ? ENDED.name() : READY.name(), i -> false, 64);
        List<String> expected = expected(ids, ended);
        assertEquals(7, expected.size(), name);

        for (boolean seesAll : new boolean[]{true, false}) {
            List<String> paged = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                List<String> page = page(byIdSet(backend, seesAll), index, 2, ENDED);
                assertEquals(page, page(rowByRow(backend, seesAll), index, 2, ENDED), name + " page " + index);
                assertTrue(page.size() <= 2, name);
                paged.addAll(page);
            }
            assertEquals(expected, paged, name + (seesAll ? " (sees all)" : " (owner-scoped)"));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    @DisplayName("no conversation in the state: an empty listing, without paging any descriptors")
    void noMatchesIsEmpty(String name, Supplier<ListingBackend> factory) throws Exception {
        ListingBackend backend = fresh(factory);
        backend.seed(40, i -> READY.name(), i -> false, 16);

        assertEquals(List.of(), page(byIdSet(backend, true), 0, 20, ENDED), name);
        assertEquals(List.of(), page(rowByRow(backend, true), 0, 20, ENDED), name);
        assertEquals(20, page(byIdSet(backend, true), 0, 20, READY).size(), name);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    @DisplayName("a state change by any write path is listed at once - the memory store is the only copy of the state")
    void stateWritesNeedNoSync(String name, Supplier<ListingBackend> factory) throws Exception {
        ListingBackend backend = fresh(factory);
        List<String> ids = backend.seed(30, i -> READY.name(), i -> false, 16);

        backend.memoryStore.setConversationState(ids.get(5), ENDED);
        assertEquals(List.of(ids.get(5)), page(byIdSet(backend, true), 0, 20, ENDED), name + ": narrow set");

        assertTrue(backend.memoryStore.compareAndSetState(ids.get(9), READY, ENDED), name);
        assertEquals(List.of(ids.get(9), ids.get(5)), page(byIdSet(backend, true), 0, 20, ENDED), name + ": compare-and-set");

        assertTrue(backend.memoryStore.compareAndSetState(ids.get(5), ENDED, READY), name);
        assertEquals(List.of(ids.get(9)), page(byIdSet(backend, true), 0, 20, ENDED), name + ": leaving the state");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    @DisplayName("orphans before the matches: the id set never reads them; the per-row filter reports the bound")
    void orphansAndTheScanBound(String name, Supplier<ListingBackend> factory) throws Exception {
        ListingBackend backend = fresh(factory);
        // Rows 0-9 are ENDED; rows 10-59 (the newest) are orphans: a descriptor, no
        // memory.
        List<String> ids = backend.seed(60, i -> ENDED.name(), i -> i >= 10, 16);
        List<String> ended = expected(ids, i -> i < 10);

        RestConversationStore viaIds = byIdSet(backend, true);
        viaIds.maxRejectedScan = 5;
        assertEquals(ended.subList(0, 5), page(viaIds, 0, 5, ENDED), name + ": the orphans are not in the id set");

        RestConversationStore perRow = rowByRow(backend, true);
        perRow.maxRejectedScan = 5;
        var refused = assertThrows(WebApplicationException.class, () -> page(perRow, 0, 5, ENDED), name);
        assertEquals(422, refused.getResponse().getStatus(), name);
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) refused.getResponse().getEntity();
        assertEquals(RestConversationStore.SCAN_BOUND_EXCEEDED, body.get("error"), name);

        // Unfiltered, the orphans are rejected row by row on every path.
        RestConversationStore unfiltered = backend.listing(true);
        unfiltered.maxRejectedScan = 5;
        assertThrows(WebApplicationException.class, () -> page(unfiltered, 0, 5, null), name);
        assertEquals(ended.subList(0, 5), page(backend.listing(true), 0, 5, null), name + ": within the default bound");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    @DisplayName("reaching the bound on the last batch is a complete answer, not a refusal")
    void boundOnTheLastBatchIsComplete(String name, Supplier<ListingBackend> factory) throws Exception {
        ListingBackend backend = fresh(factory);
        // 12 READY rows and 2 ENDED, all read in the first (and last) batch of 20.
        List<String> ids = backend.seed(14, i -> i < 2 ? ENDED.name() : READY.name(), i -> false, 16);
        RestConversationStore perRow = rowByRow(backend, true);
        perRow.maxRejectedScan = 3;
        assertEquals(expected(ids, i -> i < 2), page(perRow, 0, 20, ENDED), name);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    @DisplayName("a state too common for the id set is filtered row by row, with the same result")
    void commonStateFallsBackToRowByRow(String name, Supplier<ListingBackend> factory) throws Exception {
        ListingBackend backend = fresh(factory);
        List<String> ids = backend.seed(50, i -> i % 5 == 0 ? READY.name() : ENDED.name(), i -> false, 16);
        RestConversationStore small = byIdSet(backend, true);
        small.maxStateIdSet = 10;
        List<String> expected = expected(ids, i -> i % 5 != 0);
        assertEquals(expected.subList(10, 20), page(small, 1, 10, ENDED), name);
        assertEquals(expected.subList(10, 20), page(byIdSet(backend, true), 1, 10, ENDED), name);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    @DisplayName("agentVersion is part of the descriptor query")
    void agentVersionInTheQuery(String name, Supplier<ListingBackend> factory) throws Exception {
        ListingBackend backend = fresh(factory);
        List<String> ids = backend.seed(10, i -> READY.name(), i -> false, 16);
        var listing = byIdSet(backend, true);
        listing.maxRejectedScan = 1;
        assertEquals(expected(ids, i -> true),
                ids(listing.readConversationDescriptors(0, 20, null, null, ListingBackend.AGENT_ID, 1, null, null)), name);
        // Version 2 matches none of the descriptors in the query itself: nothing is
        // read and rejected, so a bound of one is not reached.
        assertEquals(List.of(), ids(listing.readConversationDescriptors(0, 20, null, null, ListingBackend.AGENT_ID, 2, null, null)), name);
        assertEquals(List.of(), ids(listing.readConversationDescriptors(0, 20, null, null, ListingBackend.AGENT_ID, 11, null, null)), name);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    @DisplayName("the id set and a deep page: every page of the state, in order, without gaps or repeats")
    void deepPagesConcatenateToTheWholeState(String name, Supplier<ListingBackend> factory) throws Exception {
        ListingBackend backend = fresh(factory);
        IntPredicate ended = i -> i % 3 == 0;
        List<String> ids = backend.seed(300, i -> ended.test(i) ? ENDED.name() : READY.name(), i -> false, 16);
        List<String> expected = expected(ids, ended);
        List<String> all = new ArrayList<>();
        IntStream.range(0, 12).forEach(index -> all.addAll(page(byIdSet(backend, true), index, 9, ENDED)));
        assertEquals(expected, all, name);
    }
}
