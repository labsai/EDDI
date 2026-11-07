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
import org.bson.Document;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
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

    private static final int[] SIZES = {10_000, 100_000};
    private static final double[] SELECTIVITIES = {0.001, 0.01, 0.1};
    private static final int LIMIT = 20;
    private static final int RUNS = 3;
    private static final int STATE_WRITES = 2_000;

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
        System.out.println(report);
    }

    private void listingCosts(ListingBackend backend, int size) throws Exception {
        report.append("## ").append(backend.name()).append(", ").append(size).append(" conversations\n\n")
                .append("| ENDED | page | path | ms | descriptors read | descriptor queries | summary queries | id queries | keys examined "
                        + "| docs examined | outcome |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|---|\n");
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
                for (String path : List.of("before", "after")) {
                    Measure m = measureListing(backend, index, path);
                    report.append(String.format("| %d (%.1f%%) | %s | %s | %.1f | %d | %d | %d | %d | %s | %s | %s |%n", ended,
                            100.0 * ended / size, index == 0 ? "first" : "index " + index + " (offset " + index * LIMIT + ")", path,
                            m.millis(), m.descriptorsRead(), m.descriptorQueries(), m.summaryQueries(), m.idQueries(),
                            m.keysExamined() < 0 ? "-" : String.valueOf(m.keysExamined()),
                            m.docsExamined() < 0 ? "-" : String.valueOf(m.docsExamined()), m.outcome()));
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

    private Measure measureListing(ListingBackend backend, int index, String path) {
        List<Measure> runs = new ArrayList<>();
        for (int run = 0; run <= RUNS; run++) {
            AtomicLong descriptorsRead = new AtomicLong();
            AtomicLong descriptorQueries = new AtomicLong();
            AtomicLong summaryQueries = new AtomicLong();
            AtomicLong idQueries = new AtomicLong();
            IConversationDescriptorStore descriptors = countingDescriptors(backend.descriptorStore, descriptorsRead, descriptorQueries);
            IConversationMemoryStore memory = countingMemory(backend.memoryStore, summaryQueries, idQueries);
            RestConversationStore listing = new ListingOver(backend, descriptors, memory).listing(true);
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
            if (run > 0) {
                runs.add(new Measure(millis, descriptorsRead.get(), descriptorQueries.get(), summaryQueries.get(), idQueries.get(),
                        before == null ? -1 : after[0] - before[0], before == null ? -1 : after[1] - before[1], outcome));
            }
        }
        runs.sort((a, b) -> Double.compare(a.millis(), b.millis()));
        return runs.get(runs.size() / 2);
    }

    /** The backend with its stores swapped for counting ones. */
    private static final class ListingOver extends ListingBackend {
        ListingOver(ListingBackend backend, IConversationDescriptorStore descriptors, IConversationMemoryStore memory) {
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
        report.append("## ").append(backend.name()).append(", state-write cost (").append(STATE_WRITES)
                .append(" writes, 10,000 conversations)\n\n| write | total ms | µs per write |\n|---|---|---|\n");

        double withIndex = timeStateWrites(backend, sample);
        report.append(String.format("| conversation state (as shipped) | %.0f | %.0f |%n", withIndex, withIndex * 1000 / STATE_WRITES));
        if (backend instanceof ListingBackend.Mongo) {
            var memories = getDatabase().getCollection("conversationmemories");
            memories.dropIndex(Indexes.ascending("conversationState", "_id"));
            double without = timeStateWrites(backend, sample);
            memories.createIndex(Indexes.ascending("conversationState", "_id"));
            report.append(String.format("| conversation state without the {conversationState, _id} index | %.0f | %.0f |%n", without,
                    without * 1000 / STATE_WRITES));
            var descriptors = getDatabase().getCollection("descriptors");
            long start = System.nanoTime();
            for (int i = 0; i < sample.size(); i++) {
                descriptors.updateOne(Filters.eq("_id", new ObjectId(sample.get(i))),
                        Updates.set("conversationState", i % 2 == 0 ? "IN_PROGRESS" : "READY"));
            }
            double sync = (System.nanoTime() - start) / 1e6;
            report.append(String.format("| + descriptor state sync (one more `$set` per state write) | %.0f | %.0f |%n", sync,
                    sync * 1000 / STATE_WRITES));
        } else {
            DataSource dataSource = ((ListingBackend.Postgres) backend).dataSource();
            try (Connection conn = dataSource.getConnection();
                    PreparedStatement update = conn.prepareStatement("UPDATE resources SET data = jsonb_set(data, '{conversationState}', "
                            + "to_jsonb(?::text)) WHERE collection_name = 'descriptors' AND id = ?::uuid")) {
                long start = System.nanoTime();
                for (int i = 0; i < sample.size(); i++) {
                    update.setString(1, i % 2 == 0 ? "IN_PROGRESS" : "READY");
                    update.setString(2, sample.get(i));
                    update.executeUpdate();
                }
                double sync = (System.nanoTime() - start) / 1e6;
                report.append(String.format("| + descriptor state sync (one more `UPDATE` per state write) | %.0f | %.0f |%n", sync,
                        sync * 1000 / STATE_WRITES));
            }
        }
        report.append('\n');
    }

    private static double timeStateWrites(ListingBackend backend, List<String> ids) {
        // Warm up, then time the flips a turn makes (READY -> IN_PROGRESS -> READY).
        for (String id : ids.subList(0, 100)) {
            backend.memoryStore.setConversationState(id, ConversationState.IN_PROGRESS);
        }
        long start = System.nanoTime();
        for (int i = 0; i < ids.size(); i++) {
            backend.memoryStore.setConversationState(ids.get(i), i % 2 == 0 ? ConversationState.IN_PROGRESS : ConversationState.READY);
        }
        return (System.nanoTime() - start) / 1e6;
    }
}
