/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration.cluster;

import ai.labs.eddi.integration.cluster.ClusterHarness.Db;
import ai.labs.eddi.integration.cluster.ConversationHistoryChecker.LlmCall;
import ai.labs.eddi.integration.cluster.ConversationHistoryChecker.Outcome;
import ai.labs.eddi.integration.cluster.ConversationHistoryChecker.Report;
import ai.labs.eddi.integration.cluster.ConversationHistoryChecker.Turn;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The multi-process end-to-end cluster suite (review Phase 3, item 1; see
 * {@code planning/cluster-production-readiness-review.md} and the "How cluster
 * mode is tested" section of {@code docs/clustering.md}).
 * <p>
 * Every scenario drives real turns through the REST API of three EDDI
 * processes, injects one fault, then hands what the clients saw, what was
 * committed and what the LLM saw to {@link ConversationHistoryChecker}. A
 * scenario passes only when the history is clean <em>and</em> its own liveness
 * expectation holds. Scenarios whose contract the code on {@code main} is known
 * not to meet carry the finding ID of the review in their display name; their
 * assertions state the contract, not today's behaviour.
 * <p>
 * Opt-in: the suite needs Docker, a packaged app ({@code ./mvnw package
 * -DskipTests -DskipUi=true}) and about 15 minutes, so it runs only with
 * {@code -Deddi.cluster.e2e=true} — nightly in
 * {@code .github/workflows/cluster-e2e-nightly.yml}, never in the normal build.
 * Run it through surefire, which has no fork timeout and no rerun:
 *
 * <pre>
 * ./mvnw test -Dtest=MongoClusterE2EIT -Deddi.cluster.e2e=true -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * Results (one JSON file per scenario plus every node's log) go to
 * {@code target/cluster-e2e/<db>/}.
 */
@Tag("cluster-e2e")
@EnabledIfSystemProperty(named = "eddi.cluster.e2e", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
abstract class AbstractClusterE2EIT {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final Duration TURN_TIMEOUT = Duration.ofSeconds(150);

    /** Set by the subclass's {@code @BeforeAll}. */
    static ClusterHarness cluster;
    static String agent;

    static void startCluster(Db db) {
        cluster = ClusterHarness.get(db);
        agent = cluster.setupAgent("cluster-e2e-" + db.name().toLowerCase());
    }

    static Path resultDir() {
        return Path.of("target", "cluster-e2e", cluster.db.name().toLowerCase());
    }

    @AfterEach
    void heal() {
        cluster.healAll();
        // A scenario leaves every surviving node connected, so the next one starts
        // clean.
        cluster.allConnected(Duration.ofSeconds(90));
    }

    @AfterAll
    static void saveLogs() throws IOException {
        if (cluster != null) {
            cluster.writeLogs(resultDir());
        }
    }

    // ------------------------------------------------------------------ a run's
    // bookkeeping

    /** The turns of one scenario and the conversations they touched. */
    final class Run {
        final String name;
        final ConcurrentLinkedQueue<Turn> turns = new ConcurrentLinkedQueue<>();
        final Set<String> conversations = new LinkedHashSet<>();
        final Map<String, Object> facts = new LinkedHashMap<>();
        final String tag = UUID.randomUUID().toString().substring(0, 8);
        private final AtomicInteger seq = new AtomicInteger();
        long llmCallsSeen;
        List<String> taskErrors = List.of();

        Run(String name) {
            this.name = name;
        }

        String conversation(int node) {
            String c = cluster.startConversation(node, agent);
            synchronized (conversations) {
                conversations.add(c);
            }
            return c;
        }

        /**
         * A unique input; {@code kind} picks the mock LLM's behaviour (nap, linger,
         * stall, fast).
         */
        String input(String kind) {
            return "[" + tag + "] " + kind + " " + seq.incrementAndGet();
        }

        Turn say(int node, String conversation, String kind) {
            Turn t = cluster.say(node, conversation, input(kind), TURN_TIMEOUT);
            turns.add(t);
            return t;
        }

        /**
         * A client that retries a refused turn after its {@code Retry-After}, each
         * retry a new input (the refused one was not consumed). Returns the last
         * answer.
         */
        Turn sayWithRetry(int node, String conversation, String kind, int attempts) {
            Turn t = null;
            for (int a = 0; a < attempts; a++) {
                t = say((node + a) % ClusterHarness.NODES, conversation, kind);
                if (t.outcome() != Outcome.REFUSED) {
                    return t;
                }
                ClusterHarness.sleep(Duration.ofSeconds(2));
            }
            return t;
        }

        CompletableFuture<Turn> sayAsync(ExecutorService pool, int node, String conversation, String kind) {
            return CompletableFuture.supplyAsync(() -> say(node, conversation, kind), pool);
        }

        /**
         * Reads the committed histories (waiting until every 200 is there or 20 s
         * passed: a reply is rendered before its write commits), checks them, writes
         * the result file and returns the report.
         */
        Report verify() {
            Map<String, List<String>> committed = new LinkedHashMap<>();
            for (String c : conversations) {
                Set<String> answered = new HashSet<>();
                turns.stream().filter(t -> t.conversationId().equals(c) && t.outcome() == Outcome.OK).forEach(t -> answered.add(t.input()));
                List<List<String>> last = new ArrayList<>();
                ClusterHarness.waitUntil(Duration.ofSeconds(20), () -> {
                    List<String> h = cluster.committedInputs(c);
                    last.clear();
                    last.add(h);
                    return h.containsAll(answered);
                });
                committed.put(c, last.isEmpty() ? cluster.committedInputs(c) : last.get(0));
            }
            List<LlmCall> calls = cluster.llmCalls();
            Set<String> inputs = new HashSet<>();
            turns.forEach(t -> inputs.add(t.input()));
            llmCallsSeen = calls.stream().filter(c -> inputs.contains(c.user())).count();
            taskErrors = turns.stream().map(Turn::input).filter(cluster.answeredWithTaskError::contains).toList();
            facts.put("llmCallsOfThisRun", llmCallsSeen);
            facts.put("answeredWithTaskError", taskErrors);
            Report report = ConversationHistoryChecker.check(new ArrayList<>(turns), committed, calls);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("scenario", name);
            out.put("db", cluster.db.name().toLowerCase());
            out.put("clean", report.clean());
            out.put("summary", report.summary());
            out.put("violations", report.violations().stream().map(Object::toString).toList());
            out.put("facts", facts);
            out.put("committed", committed);
            out.put("turns", new ArrayList<>(turns));
            out.put("deadLetters", cluster.deadLetters());
            try {
                Files.createDirectories(resultDir());
                Files.writeString(resultDir().resolve(name + ".json"), JSON.writeValueAsString(out));
            } catch (IOException e) {
                throw new IllegalStateException("could not write the result of " + name, e);
            }
            return report;
        }
    }

    private static String scenario(TestInfo info) {
        return info.getTestMethod().map(m -> m.getName()).orElse("scenario");
    }

    /**
     * The history is clean, and not vacuously: the LLM was reached (otherwise the
     * overlap and context checks grade nothing, and the slow turns the faults are
     * timed against were instant), and no turn was answered 200 with a task error
     * in place of its LLM call.
     */
    private static void assertClean(Run run, Report r) {
        assertTrue(run.llmCallsSeen > 0, "the mock LLM saw no call of this run, so the checks would be vacuous: " + run.facts);
        assertEquals(List.of(), run.taskErrors, "turns answered 200 with a task error instead of an LLM reply");
        assertTrue(r.clean(), "history violations: " + r.summary());
    }

    private static long count(Run run, Outcome outcome) {
        return run.turns.stream().filter(t -> t.outcome() == outcome).count();
    }

    // ------------------------------------------------------------------ scenarios

    @Test
    @Order(1)
    @DisplayName("ordering: concurrent turns of one conversation on three nodes are serialized, none lost or duplicated")
    void orderingAcrossNodes(TestInfo info) throws Exception {
        Run run = new Run(scenario(info));
        String slow = run.conversation(0);
        String fast = run.conversation(1);
        ExecutorService pool = Executors.newFixedThreadPool(12);
        try {
            List<Future<Turn>> futures = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                futures.add(run.sayAsync(pool, i % 3, slow, "nap"));
            }
            for (Future<Turn> f : futures) {
                f.get(5, TimeUnit.MINUTES);
            }
            futures.clear();
            ExecutorService six = Executors.newFixedThreadPool(6);
            try {
                for (int i = 0; i < 60; i++) {
                    futures.add(run.sayAsync(six, i % 3, fast, "fast"));
                }
                for (Future<Turn> f : futures) {
                    f.get(5, TimeUnit.MINUTES);
                }
            } finally {
                six.shutdownNow();
            }
        } finally {
            pool.shutdownNow();
        }
        Report r = run.verify();
        assertClean(run, r);
        assertTrue(count(run, Outcome.OK) >= 54, "without faults at least 75% of 72 turns must succeed: " + r.summary());
        assertEquals(0, count(run, Outcome.INDETERMINATE), "no turn may fail without a 409/503: " + r.summary());
    }

    @Test
    @Order(2)
    @DisplayName("latency: 150 ms (±50) on every EDDI-to-NATS link keeps the history clean")
    void latencyOnEveryNatsLink(TestInfo info) throws Exception {
        Run run = new Run(scenario(info));
        String concurrent = run.conversation(0);
        String sequential = run.conversation(1);
        for (int n = 0; n < ClusterHarness.NODES; n++) {
            cluster.latency(n, 150, 50);
        }
        ExecutorService pool = Executors.newFixedThreadPool(7);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                futures.add(run.sayAsync(pool, i % 3, concurrent, "nap"));
            }
            futures.add(pool.submit(() -> {
                for (int i = 0; i < 10; i++) {
                    run.sayWithRetry(i % 3, sequential, "fast", 3);
                }
            }));
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.MINUTES);
            }
        } finally {
            pool.shutdownNow();
        }
        Report r = run.verify();
        assertClean(run, r);
        assertTrue(count(run, Outcome.OK) >= 12, "most turns must still succeed under latency: " + r.summary());
    }

    @Test
    @Order(3)
    @DisplayName("partition (C2/C5 context): a node cut off from NATS refuses turns (degraded.turns=reject), the others serve; it rejoins")
    void partitionedNodeRefusesThenRejoins(TestInfo info) {
        Run run = new Run(scenario(info));
        String conv = run.conversation(0);
        run.say(0, conv, "fast");
        cluster.partition(2);
        // degraded.grace (5 s) plus detection
        ClusterHarness.sleep(Duration.ofSeconds(10));
        run.facts.put("readinessWhilePartitioned", cluster.readiness(2));
        List<Turn> cutOff = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            Turn t = run.say(i % 3, conv, "fast");
            if (i % 3 == 2) {
                cutOff.add(t);
            }
        }
        cluster.healAll();
        boolean rejoined = cluster.allConnected(Duration.ofSeconds(60));
        Turn after = run.say(2, conv, "fast");
        run.facts.put("rejoined", rejoined);
        Report r = run.verify();
        assertClean(run, r);
        assertTrue(cutOff.stream().allMatch(t -> t.outcome() == Outcome.REFUSED),
                "a partitioned node in reject mode answers 409/503, never runs the turn unfenced: " + cutOff);
        assertTrue(run.turns.stream().filter(t -> !t.node().equals("eddi-3")).allMatch(t -> t.outcome() == Outcome.OK),
                "the connected nodes keep serving: " + r.summary());
        assertTrue(rejoined, "the partitioned node reconnects after the partition heals");
        assertEquals(Outcome.OK, after.outcome(), "the rejoined node serves again");
    }

    @Test
    @Order(4)
    @DisplayName("A2: lease renewals applied by NATS whose replies are lost do not cancel a healthy turn or block the conversation for a TTL")
    void repliesLostAfterCommit(TestInfo info) throws Exception {
        Run run = new Run(scenario(info));
        String conv = run.conversation(0);
        run.say(0, conv, "fast");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<Turn> lingering = run.sayAsync(pool, 0, conv, "linger"); // 15 s at the LLM
            ClusterHarness.sleep(Duration.ofSeconds(3));
            // 7 s covers at least one heartbeat (5 s) and stays below ttl - 2 x heartbeat
            // (10 s)
            cluster.dropReplies(0);
            ClusterHarness.sleep(Duration.ofSeconds(7));
            cluster.healAll();
            Turn holder = lingering.get(3, TimeUnit.MINUTES);
            long t0 = System.nanoTime();
            Turn next = run.say(1, conv, "fast");
            long waitedMs = (System.nanoTime() - t0) / 1_000_000;
            run.facts.put("holderStatus", holder.status());
            run.facts.put("nextTurnWaitedMs", waitedMs);
            Report r = run.verify();
            assertClean(run, r);
            assertEquals(Outcome.OK, holder.outcome(), "the holder's turn was healthy and must not be cancelled (A2): " + holder);
            assertEquals(Outcome.OK, next.outcome(), "the next turn runs: " + next);
            assertTrue(waitedMs < 10_000, "the next turn must not wait out a lease TTL (A2), waited " + waitedMs + " ms");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Order(5)
    @DisplayName("A1: a holder on a half-open NATS link stops itself; its client never gets 200 for a turn the takeover made it unable to commit")
    void halfOpenHolderStopsItself(TestInfo info) throws Exception {
        Run run = new Run(scenario(info));
        String conv = run.conversation(0);
        run.say(0, conv, "fast");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<Turn> stalled = run.sayAsync(pool, 0, conv, "stall"); // 35 s at the LLM
            ClusterHarness.sleep(Duration.ofSeconds(2));
            cluster.halfOpen(0);
            ClusterHarness.sleep(Duration.ofSeconds(2));
            long t0 = System.nanoTime();
            Turn takeover = run.say(1, conv, "fast");
            run.facts.put("takeoverWaitedMs", (System.nanoTime() - t0) / 1_000_000);
            // keep node 1 cut off until its LLM call returned and it tried to commit
            ClusterHarness.waitUntil(Duration.ofSeconds(45), stalled::isDone);
            cluster.healAll();
            Turn holder = stalled.get(3, TimeUnit.MINUTES);
            run.facts.put("holderStatus", holder.status());
            Report r = run.verify();
            assertEquals(Outcome.OK, takeover.outcome(), "another node takes the conversation once the lease expires: " + takeover);
            assertClean(run, r);
            assertTrue(holder.outcome() != Outcome.OK || cluster.committedInputs(conv).contains(holder.input()),
                    "A1: the cut-off holder answered 200 for a turn that was not committed: " + holder);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Order(6)
    @DisplayName("A1/A6: a holder frozen (SIGSTOP) past lease.ttl is taken over, and its late write is refused, never answered 200")
    void frozenHolderPastTtl(TestInfo info) throws Exception {
        Run run = new Run(scenario(info));
        String conv = run.conversation(0);
        run.say(0, conv, "fast");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<Turn> lingering = run.sayAsync(pool, 0, conv, "linger");
            ClusterHarness.sleep(Duration.ofSeconds(3));
            cluster.freeze(0);
            long t0 = System.nanoTime();
            Turn takeover = run.say(1, conv, "fast");
            long waitedMs = (System.nanoTime() - t0) / 1_000_000;
            // stay frozen past the TTL even if the takeover was quick
            long frozenFor = ClusterHarness.LEASE_TTL.toMillis() + 8_000 - waitedMs;
            if (frozenFor > 0) {
                ClusterHarness.sleep(Duration.ofMillis(frozenFor));
            }
            cluster.thaw(0);
            Turn holder = lingering.get(3, TimeUnit.MINUTES);
            Turn after = run.say(2, conv, "fast");
            run.facts.put("takeoverWaitedMs", waitedMs);
            run.facts.put("holderStatus", holder.status());
            Report r = run.verify();
            assertEquals(Outcome.OK, takeover.outcome(), "the takeover turn runs once the frozen holder's lease expires: " + takeover);
            assertTrue(waitedMs <= ClusterHarness.LEASE_TTL.toMillis() + 15_000, "takeover within lease.ttl, waited " + waitedMs + " ms");
            assertClean(run, r);
            assertEquals(Outcome.OK, after.outcome(), "the conversation keeps working after the zombie: " + after);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Order(7)
    @DisplayName("A2/D3: repeated NATS stream-leader failovers under load lose, duplicate or reorder no turn")
    void streamLeaderFailoverUnderLoad(TestInfo info) throws Exception {
        Run run = new Run(scenario(info));
        List<String> convs = List.of(run.conversation(0), run.conversation(1), run.conversation(2));
        AtomicBoolean stop = new AtomicBoolean();
        List<String> stepDowns = new CopyOnWriteArrayList<>();
        Thread chaos = new Thread(() -> {
            while (!stop.get()) {
                try {
                    stepDowns.add(System.currentTimeMillis() + " " + cluster.stepDownStreamLeaders());
                } catch (RuntimeException e) {
                    stepDowns.add(System.currentTimeMillis() + " failed: " + e.getMessage());
                }
                ClusterHarness.sleep(Duration.ofSeconds(4));
            }
        }, "stream-leader-chaos");
        ExecutorService pool = Executors.newFixedThreadPool(convs.size());
        List<Future<List<Turn>>> clients = new ArrayList<>();
        chaos.start();
        try {
            for (int c = 0; c < convs.size(); c++) {
                int first = c;
                String conv = convs.get(c);
                clients.add(pool.submit(() -> {
                    List<Turn> last = new ArrayList<>();
                    // at least 12 turns each, and keep going until the leaders moved 4 times
                    // (instant
                    // turns finish 36 turns in a few seconds, inside the first step-down interval)
                    for (int i = 0; i < 200 && (i < 12 || stepDowns.size() < 4); i++) {
                        last.add(run.sayWithRetry((first + i) % 3, conv, "fast", 5));
                    }
                    return last;
                }));
            }
            List<Turn> finals = new ArrayList<>();
            for (Future<List<Turn>> f : clients) {
                finals.addAll(f.get(15, TimeUnit.MINUTES));
            }
            stop.set(true);
            chaos.join(10_000);
            run.facts.put("stepDowns", stepDowns);
            Report r = run.verify();
            assertTrue(stepDowns.size() >= 4, "the leaders must actually have moved: " + stepDowns);
            assertClean(run, r);
            assertTrue(finals.stream().allMatch(t -> t.outcome() == Outcome.OK),
                    "every turn succeeds within 5 attempts across leader elections: "
                            + finals.stream().filter(t -> t.outcome() != Outcome.OK).toList());
        } finally {
            stop.set(true);
            pool.shutdownNow();
        }
    }

    @Test
    @Order(8)
    @DisplayName("kill -9 of the holder mid-turn: the next turn runs on another node within lease.ttl, nothing of the dead turn is stored")
    void kill9HolderMidTurn(TestInfo info) throws Exception {
        Run run = new Run(scenario(info));
        String conv = run.conversation(0);
        run.say(0, conv, "fast");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<Turn> lingering = run.sayAsync(pool, 0, conv, "linger");
            ClusterHarness.sleep(Duration.ofSeconds(3));
            cluster.kill9(0);
            long t0 = System.nanoTime();
            Turn next = run.say(1, conv, "fast");
            long waitedMs = (System.nanoTime() - t0) / 1_000_000;
            Turn killed = lingering.get(3, TimeUnit.MINUTES);
            run.facts.put("nextTurnWaitedMs", waitedMs);
            run.facts.put("killedClientStatus", killed.status());
            String state = cluster.conversationState(1, conv);
            run.facts.put("state", state);
            Report r = run.verify();
            assertEquals(Outcome.OK, next.outcome(), "the next turn runs on another node: " + next);
            assertTrue(waitedMs <= ClusterHarness.LEASE_TTL.toMillis() + 15_000, "within lease.ttl, waited " + waitedMs + " ms");
            assertEquals("READY", state);
            assertClean(run, r);
            assertTrue(!cluster.committedInputs(conv).contains(killed.input()), "the killed turn must not be stored");
        } finally {
            pool.shutdownNow();
        }
    }

    @BeforeAll
    static void checkPrerequisites() {
        assertTrue(Files.isDirectory(Path.of("target/quarkus-app")),
                "run ./mvnw package -DskipTests -DskipUi=true first: the suite builds its image from target/quarkus-app");
        assertTrue(Files.isRegularFile(Path.of("scripts/cluster-demo/mock_llm.py")), "the mock LLM of scripts/cluster-demo is missing");
    }
}
