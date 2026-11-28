/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.rest;

import ai.labs.eddi.datastore.mongo.MongoTestBase;
import ai.labs.eddi.datastore.mongo.codec.JacksonProvider;
import ai.labs.eddi.datastore.postgres.PostgresTestBase;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.memory.model.ConversationState;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Updates;
import jakarta.ws.rs.WebApplicationException;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.types.ObjectId;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

/**
 * The measurements behind #931: what a {@code conversationState} listing costs
 * on MongoDB and PostgreSQL before and after the state moved into the
 * descriptor query, and what the alternative - keeping the state on every
 * descriptor - would add to each state write.
 * <p>
 * Not part of the build: it seeds up to 100,000 conversations per backend. Run
 * it with
 *
 * <pre>
 * ./mvnw test -DskipUi=true -Dtest=ConversationListingScanBenchmarkTest -Deddi.bench.listing=true
 * </pre>
 *
 * and read {@code target/conversation-listing-benchmark.md}.
 */
@EnabledIfSystemProperty(named = "eddi.bench.listing", matches = "true")
class ConversationListingScanBenchmarkTest extends MongoTestBase {

    private static final Logger LOGGER = Logger.getLogger(ConversationListingScanBenchmarkTest.class);
    private static final int[] SIZES = {10_000, 100_000};
    private static final double[] SELECTIVITIES = {0.001, 0.01, 0.1};
    private static final int LIMIT = 20;
    private static final int RUNS = 3;
    private static final int STATE_WRITES = 2_000;
    /**
     * Timed writes per configuration: every sample goes READY -> IN_PROGRESS, then
     * back.
     */
    private static final int WRITES_TIMED = 2 * STATE_WRITES;

    private final StringBuilder report = new StringBuilder();

    /** What one listing request did. */
    record Measure(double millis, long descriptorsRead, long descriptorQueries, long summaryQueries, long idQueries, long keysExamined,
            long docsExamined, String outcome) {
    }

    @Test
    void measure() throws Exception {
        var mongo = new ListingBackend.Mongo(getDatabase(), getDatabase().withCodecRegistry(CodecRegistries.fromRegistries(
                getDatabase().getCodecRegistry(), CodecRegistries.fromProviders(new JacksonProvider(objectMapper)))));
        var postgres = new ListingBackend.Postgres(ListingBackend.Postgres.pooled(PostgresTestBase.createDataSourceInstance()));

        report.append("# Conversation listing by state - measurements (#931)\n\n")
                .append("Each row: the median of ").append(RUNS).append(" runs after one warm-up, limit ").append(LIMIT)
                .append(". The two paths of one workload are sampled in turn, the order swapping every sample; `order` lists ")
                .append("each sample's (warm-up first), `b` = before ran first, `a` = after ran first")
                .append(". `before` filters the state row by row with no bound (the code before #931); `after` pushes the ids ")
                .append("in the state into the descriptor query. Descriptors read and queries are counted at the store interfaces; ")
                .append("keys/docs examined are MongoDB's serverStatus queryExecutor counters (not available on PostgreSQL).\n\n");
        for (ListingBackend backend : List.of(mongo, postgres)) {
            for (int size : SIZES) {
                listingCosts(backend, size);
            }
            writeCosts(backend);
        }
        Path out = Path.of("target", "conversation-listing-benchmark.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString());
        LOGGER.info(report);
    }

    private void listingCosts(ListingBackend backend, int size) throws Exception {
        report.append("## ").append(backend.name()).append(", ").append(size).append(" conversations\n\n")
                .append("| ENDED | page | path | ms | descriptors read | descriptor queries | summary queries | id queries | keys examined "
                        + "| docs examined | outcome | order |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        backend.clear();
        // Every conversation READY; the selectivity loop marks the first n ENDED.
        List<String> ids = backend.seed(size, i -> ConversationState.READY.name(), i -> false, 2_048);
        int ended = 0;
        for (double selectivity : SELECTIVITIES) {
            int target = (int) Math.round(size * selectivity);
            // Spread the ENDED conversations evenly over the listing order.
            int stride = size / target;
            for (int i = 0; i < size && ended < target; i += stride) {
                backend.memoryStore.setConversationState(ids.get(i), ConversationState.ENDED);
            }
            ended = countEnded(backend, ids);
            int deepIndex = Math.min(ended - LIMIT, RestConversationStore.MAX_RESULT_OFFSET) / LIMIT;
            for (int index : new int[]{0, Math.max(1, deepIndex)}) {
                StringBuilder order = new StringBuilder();
                Map<String, Measure> medians = measureListing(backend, index, order);
                for (Map.Entry<String, Measure> path : medians.entrySet()) {
                    Measure m = path.getValue();
                    report.append(String.format("| %d (%.1f%%) | %s | %s | %.1f | %d | %d | %d | %d | %s | %s | %s | %s |%n", ended,
                            100.0 * ended / size, index == 0 ? "first" : "index " + index + " (offset " + index * LIMIT + ")",
                            path.getKey(), m.millis(), m.descriptorsRead(), m.descriptorQueries(), m.summaryQueries(), m.idQueries(),
                            m.keysExamined() < 0 ? "-" : String.valueOf(m.keysExamined()),
                            m.docsExamined() < 0 ? "-" : String.valueOf(m.docsExamined()), m.outcome(), order));
                }
            }
            // Reset the ENDED ones for the next selectivity, which marks its own set.
            for (int i = 0; i < size; i += stride) {
                backend.memoryStore.setConversationState(ids.get(i), ConversationState.READY);
            }
            ended = 0;
        }
        report.append('\n');
    }

    private static int countEnded(ListingBackend backend, List<String> ids) throws Exception {
        return backend.memoryStore.findConversationIdsByState(ConversationState.ENDED, ids.size()).size();
    }

    /**
     * The median of {@link #RUNS} samples of each path for one workload, after one
     * warm-up sample of each. The paths are sampled in turn, and which one runs
     * first swaps every sample, so a drift over the measurement (cache, JIT, clock)
     * falls on both paths alike instead of on whichever ran later.
     *
     * @param order
     *            receives each sample's order, warm-up first: {@code b} when
     *            {@code before} ran first, {@code a} when {@code after} did
     */
    private Map<String, Measure> measureListing(ListingBackend backend, int index, StringBuilder order) {
        Map<String, List<Measure>> samples = new LinkedHashMap<>();
        samples.put("before", new ArrayList<>());
        samples.put("after", new ArrayList<>());
        for (int run = 0; run <= RUNS; run++) {
            List<String> paths = run % 2 == 0 ? List.of("before", "after") : List.of("after", "before");
            order.append(paths.getFirst().charAt(0));
            for (String path : paths) {
                Measure m = measureOnce(backend, index, path);
                if (run > 0) {
                    samples.get(path).add(m);
                }
            }
        }
        Map<String, Measure> medians = new LinkedHashMap<>();
        samples.forEach((path, runs) -> {
            runs.sort((a, b) -> Double.compare(a.millis(), b.millis()));
            medians.put(path, runs.get(runs.size() / 2));
        });
        return medians;
    }

    /** One timed listing request on {@code path}. */
    private Measure measureOnce(ListingBackend backend, int index, String path) {
        AtomicLong descriptorsRead = new AtomicLong();
        AtomicLong descriptorQueries = new AtomicLong();
        AtomicLong summaryQueries = new AtomicLong();
        AtomicLong idQueries = new AtomicLong();
        IConversationDescriptorStore descriptors = countingDescriptors(backend.descriptorStore, descriptorsRead, descriptorQueries);
        IConversationMemoryStore memory = countingMemory(backend.memoryStore, summaryQueries, idQueries);
        RestConversationStore listing = new ListingOver(descriptors, memory).listing(true);
        if ("before".equals(path)) {
            listing.maxStateIdSet = -1;
            listing.maxRejectedScan = Integer.MAX_VALUE;
        }
        long[] before = backend.serverScanned();
        long start = System.nanoTime();
        String outcome;
        try {
            outcome = listing.readConversationDescriptors(index, LIMIT, null, null, null, null, ConversationState.ENDED, null).size()
                    + " rows";
        } catch (WebApplicationException e) {
            outcome = "HTTP " + e.getResponse().getStatus();
        }
        double millis = (System.nanoTime() - start) / 1e6;
        long[] after = backend.serverScanned();
        return new Measure(millis, descriptorsRead.get(), descriptorQueries.get(), summaryQueries.get(), idQueries.get(),
                before == null ? -1 : after[0] - before[0], before == null ? -1 : after[1] - before[1], outcome);
    }

    /** The backend with its stores swapped for counting ones. */
    private static final class ListingOver extends ListingBackend {
        ListingOver(IConversationDescriptorStore descriptors, IConversationMemoryStore memory) {
            super(memory, descriptors);
        }

        @Override
        String name() {
            return "counting";
        }

        @Override
        String newId() {
            throw new UnsupportedOperationException();
        }

        @Override
        void clear() {
            throw new UnsupportedOperationException();
        }

        @Override
        List<String> seed(int count, IntFunction<String> stateOf, IntPredicate orphan,
                          int payloadBytes) {
            throw new UnsupportedOperationException();
        }
    }

    @SuppressWarnings("unchecked")
    private static IConversationDescriptorStore countingDescriptors(IConversationDescriptorStore real, AtomicLong rows, AtomicLong queries) {
        IConversationDescriptorStore counting = spy(real);
        try {
            doAnswer(invocation -> {
                List<ConversationDescriptor> page = (List<ConversationDescriptor>) invocation.callRealMethod();
                queries.incrementAndGet();
                rows.addAndGet(page.size());
                return page;
            }).when(counting).readDescriptors(anyString(), any(), anyInt(), anyInt(), anyBoolean(), any());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return counting;
    }

    private static IConversationMemoryStore countingMemory(IConversationMemoryStore real, AtomicLong summaryQueries, AtomicLong idQueries) {
        IConversationMemoryStore counting = spy(real);
        try {
            doAnswer(invocation -> {
                summaryQueries.incrementAndGet();
                return invocation.callRealMethod();
            }).when(counting).loadListingSummaries(anyCollection());
            doAnswer(invocation -> {
                idQueries.incrementAndGet();
                return invocation.callRealMethod();
            }).when(counting).findConversationIdsByState(any(), anyInt());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return counting;
    }

    /**
     * The write side: a state change as it is today, with and without the index the
     * id read uses (MongoDB), against the extra descriptor write that keeping the
     * state on every descriptor would add to it.
     */
    private void writeCosts(ListingBackend backend) throws Exception {
        backend.clear();
        List<String> ids = backend.seed(10_000, i -> ConversationState.READY.name(), i -> false, 2_048);
        List<String> sample = ids.subList(0, STATE_WRITES);
        report.append("## ").append(backend.name()).append(", state-write cost (").append(WRITES_TIMED)
                .append(" writes, 10,000 conversations)\n\n| write | total ms | µs per write |\n|---|---|---|\n");

        double withIndex = timeStateWrites(backend, sample);
        if (backend instanceof ListingBackend.Mongo) {
            var memories = getDatabase().getCollection("conversationmemories");
            // Counterbalanced: with, without, with again, so a drift in cache or clock
            // between configurations is not read as the index's cost.
            memories.dropIndex(Indexes.ascending("conversationState", "_id"));
            double without = timeStateWrites(backend, sample);
            memories.createIndex(Indexes.ascending("conversationState", "_id"));
            double withIndexAgain = timeStateWrites(backend, sample);
            report.append(String.format("| conversation state (as shipped; runs %.0f ms, %.0f ms, mean) | %.0f | %.0f |%n", withIndex,
                    withIndexAgain, (withIndex + withIndexAgain) / 2, (withIndex + withIndexAgain) / 2 * 1000 / WRITES_TIMED));
            report.append(String.format("| conversation state without the {conversationState, _id} index (run between them) | %.0f | %.0f |%n",
                    without, without * 1000 / WRITES_TIMED));
            var descriptors = getDatabase().getCollection("descriptors");
            long start = System.nanoTime();
            for (String target : List.of("IN_PROGRESS", "READY")) {
                for (String id : sample) {
                    descriptors.updateOne(Filters.eq("_id", new ObjectId(id)), Updates.set("conversationState", target));
                }
            }
            double sync = (System.nanoTime() - start) / 1e6;
            report.append(String.format("| + descriptor state sync (one more `$set` per state write) | %.0f | %.0f |%n", sync,
                    sync * 1000 / WRITES_TIMED));
        } else {
            report.append(String.format("| conversation state (as shipped) | %.0f | %.0f |%n", withIndex, withIndex * 1000 / WRITES_TIMED));
            DataSource dataSource = ((ListingBackend.Postgres) backend).dataSource();
            try (Connection conn = dataSource.getConnection();
                    PreparedStatement update = conn.prepareStatement("UPDATE resources SET data = jsonb_set(data, '{conversationState}', "
                            + "to_jsonb(?::text)) WHERE collection_name = 'descriptors' AND id = ?::uuid")) {
                long start = System.nanoTime();
                for (String target : List.of("IN_PROGRESS", "READY")) {
                    for (String id : sample) {
                        update.setString(1, target);
                        update.setString(2, id);
                        update.executeUpdate();
                    }
                }
                double sync = (System.nanoTime() - start) / 1e6;
                report.append(String.format("| + descriptor state sync (one more `UPDATE` per state write) | %.0f | %.0f |%n", sync,
                        sync * 1000 / WRITES_TIMED));
            }
        }
        report.append('\n');
    }

    private static double timeStateWrites(ListingBackend backend, List<String> ids) {
        // The flips a turn makes, the same for every configuration: all samples
        // READY -> IN_PROGRESS, then back. An untimed identical pass warms up first and
        // leaves every sample READY, so each timed pass starts from the same state.
        flipAll(backend, ids);
        long start = System.nanoTime();
        flipAll(backend, ids);
        return (System.nanoTime() - start) / 1e6;
    }

    private static void flipAll(ListingBackend backend, List<String> ids) {
        for (ConversationState target : List.of(ConversationState.IN_PROGRESS, ConversationState.READY)) {
            for (String id : ids) {
                backend.memoryStore.setConversationState(id, target);
            }
        }
    }
}
