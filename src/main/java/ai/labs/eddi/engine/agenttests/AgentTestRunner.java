/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.agenttests;

import ai.labs.eddi.configs.agenttests.model.AgentTestSuite;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite.TestCase;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite.Turn;
import ai.labs.eddi.engine.agenttests.model.AgentTestRun;
import ai.labs.eddi.engine.agenttests.model.AgentTestRun.AttemptResult;
import ai.labs.eddi.engine.agenttests.model.AgentTestRun.CaseResult;
import ai.labs.eddi.engine.agenttests.model.AgentTestRun.ExpectationFailure;
import ai.labs.eddi.engine.agenttests.model.AgentTestRun.TurnResult;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.memory.ConversationPurgeService;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.DoubleAdder;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Executes an {@link AgentTestSuite} against an agent version that is deployed
 * to the {@code test} environment, and records an {@link AgentTestRun}.
 * <p>
 * A server-side service, not a lifecycle task: it drives
 * {@link IConversationService} directly, one conversation per case repetition,
 * as user {@link AgentTestConversations#userIdFor}. The caller —
 * {@code AgentTestService} — has already checked the starter's rights, deployed
 * the version and created the {@code RUNNING} record.
 * <p>
 * Bounded on every axis that can grow: case repetitions run in parallel on
 * virtual threads, at most {@code eddi.agent-tests.max-parallel-cases} at a
 * time; each turn waits at most its timeout; the summed {@code audit:cost} is
 * checked against {@code maxCostPerRun} before every turn; and the runs kept
 * per agent are pruned to {@code eddi.agent-tests.max-runs-per-agent}.
 *
 * @since 6.5.0
 */
@ApplicationScoped
public class AgentTestRunner {

    private static final Logger LOGGER = Logger.getLogger(AgentTestRunner.class);

    /** Task id of the per-run audit ledger entry. */
    public static final String AUDIT_TASK_ID = "ai.labs.agenttest";
    static final String AUDIT_TASK_TYPE = "agent_test_run";
    static final int MAX_OUTPUT_CHARS = 2_000;
    static final int STORED_STEP_WAIT_SECONDS = 5;

    private final IConversationService conversationService;
    private final IAgentTestRunStore runStore;
    private final ConversationPurgeService purgeService;
    private final AuditLedgerService auditLedgerService;
    private final MeterRegistry meterRegistry;
    private final int maxParallelCases;
    private final int maxRunsPerAgent;

    @Inject
    public AgentTestRunner(IConversationService conversationService, IAgentTestRunStore runStore, ConversationPurgeService purgeService,
            AuditLedgerService auditLedgerService, MeterRegistry meterRegistry,
            @ConfigProperty(name = "eddi.agent-tests.max-parallel-cases", defaultValue = "4") int maxParallelCases,
            @ConfigProperty(name = "eddi.agent-tests.max-runs-per-agent", defaultValue = "50") int maxRunsPerAgent) {
        this.conversationService = conversationService;
        this.runStore = runStore;
        this.purgeService = purgeService;
        this.auditLedgerService = auditLedgerService;
        this.meterRegistry = meterRegistry;
        this.maxParallelCases = Math.max(1, maxParallelCases);
        this.maxRunsPerAgent = Math.max(1, maxRunsPerAgent);
    }

    /** Everything one run shares across its parallel attempts. */
    record RunContext(AgentTestRun run, AgentTestSuite suite, AgentTestSuite.Defaults defaults, double maxCost, DoubleAdder cost,
            AtomicBoolean costCeilingReached, Set<String> models) {

        boolean overBudget() {
            if (cost.sum() >= maxCost) {
                costCeilingReached.set(true);
                return true;
            }
            return false;
        }
    }

    /**
     * Runs the suite to completion on the calling thread (its attempts on their own
     * virtual threads), stores the final record, writes the audit entry and prunes
     * old runs. Never throws: a failure becomes an {@code ERROR} run.
     */
    public AgentTestRun run(AgentTestRun started, AgentTestSuite suite) {
        long startNanos = System.nanoTime();
        var ctx = new RunContext(started, suite, suite.effectiveDefaults(), suite.effectiveMaxCostPerRun(), new DoubleAdder(),
                new AtomicBoolean(), Collections.synchronizedSet(new LinkedHashSet<>()));
        AgentTestRun finished;
        try {
            List<CaseResult> cases = runCases(ctx);
            int passed = (int) cases.stream().filter(CaseResult::passed).count();
            boolean allPassed = passed == cases.size() && !ctx.costCeilingReached().get();
            finished = finish(ctx, startNanos, allPassed ? AgentTestRun.Status.PASSED : AgentTestRun.Status.FAILED, cases, passed, null);
        } catch (Exception e) {
            LOGGER.errorf(e, "Agent test run %s failed", sanitize(started.id()));
            finished = finish(ctx, startNanos, AgentTestRun.Status.ERROR, List.of(), 0,
                    "The run could not be completed: " + e.getClass().getSimpleName());
        }

        try {
            runStore.update(finished);
        } catch (Exception e) {
            LOGGER.errorf(e, "Could not store the result of agent test run %s", sanitize(started.id()));
            // Never leave a polling client waiting on RUNNING: store a terminal record
            // without the per-case detail (an oversized document is the likely cause).
            finished = finish(ctx, startNanos, AgentTestRun.Status.ERROR, List.of(), 0,
                    "The run finished but its result could not be stored: " + e.getClass().getSimpleName());
            try {
                runStore.update(finished);
            } catch (Exception fallback) {
                LOGGER.errorf(fallback, "Could not store the fallback result of agent test run %s", sanitize(started.id()));
            }
        }
        audit(finished);
        try {
            runStore.pruneOlderRuns(finished.agentId(), maxRunsPerAgent);
        } catch (Exception e) {
            LOGGER.warnf("Could not prune old test runs of agent %s: %s", sanitize(finished.agentId()), e.getMessage());
        }
        meterRegistry.counter("eddi.agent_tests.runs", "status", finished.status().name()).increment();
        meterRegistry.timer("eddi.agent_tests.run.duration").record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
        return finished;
    }

    private AgentTestRun finish(RunContext ctx, long startNanos, AgentTestRun.Status status, List<CaseResult> cases, int passed, String error) {
        AgentTestRun s = ctx.run();
        long now = System.currentTimeMillis();
        return new AgentTestRun(s.id(), s.suiteId(), s.suiteVersion(), s.suiteName(), s.agentId(), s.agentVersion(), status, s.startedBy(),
                s.startedAt(), now, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos), round(ctx.cost().sum()), ctx.maxCost(),
                ctx.costCeilingReached().get(), List.copyOf(ctx.models()), cases.size(), passed, cases.size() - passed, cases,
                s.keepConversations(), error);
    }

    private List<CaseResult> runCases(RunContext ctx) throws InterruptedException {
        List<TestCase> cases = ctx.suite().cases() != null ? ctx.suite().cases() : List.of();
        var limiter = new Semaphore(maxParallelCases);
        // One future per (case, repetition), submitted up front; the semaphore keeps at
        // most maxParallelCases of them talking to the engine at once.
        List<List<Future<AttemptResult>>> futures = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int c = 0; c < cases.size(); c++) {
                TestCase testCase = cases.get(c);
                int repetitions = testCase.repetitions() != null ? testCase.repetitions() : ctx.defaults().repetitions();
                var perCase = new ArrayList<Future<AttemptResult>>();
                for (int r = 1; r <= repetitions; r++) {
                    final int caseIndex = c;
                    final int repetition = r;
                    perCase.add(executor.submit(() -> {
                        limiter.acquire();
                        try {
                            return runAttempt(ctx, caseIndex, testCase, repetition);
                        } finally {
                            limiter.release();
                        }
                    }));
                }
                futures.add(perCase);
            }

            var results = new ArrayList<CaseResult>();
            for (int c = 0; c < cases.size(); c++) {
                TestCase testCase = cases.get(c);
                var attempts = new ArrayList<AttemptResult>();
                for (int r = 0; r < futures.get(c).size(); r++) {
                    attempts.add(awaitAttempt(futures.get(c).get(r), r + 1));
                }
                double passRate = testCase.passRate() != null ? testCase.passRate() : ctx.defaults().passRate();
                results.add(caseResult(caseName(testCase, c), attempts, passRate));
            }
            return results;
        }
    }

    private static AttemptResult awaitAttempt(Future<AttemptResult> future, int repetition) throws InterruptedException {
        try {
            return future.get();
        } catch (ExecutionException e) {
            // runAttempt catches everything it can; this is a bug or an Error.
            LOGGER.error("Agent test attempt failed unexpectedly", e.getCause());
            return new AttemptResult(repetition, null, false, 0.0, 0, null, List.of(), List.of(),
                    "Attempt failed unexpectedly: " + e.getCause().getClass().getSimpleName());
        }
    }

    static CaseResult caseResult(String name, List<AttemptResult> attempts, double passRate) {
        int passed = (int) attempts.stream().filter(AttemptResult::passed).count();
        int repetitions = attempts.size();
        // Compare counts, not a ratio: 0.8 × 5 is 4.000000000000001 in floating point,
        // which a ratio comparison would make unreachable with 4 of 5.
        int required = (int) Math.ceil(passRate * repetitions - 1e-9);
        boolean casePassed = repetitions > 0 && passed >= required;
        return new CaseResult(name, repetitions, passRate, passed, casePassed, attempts);
    }

    private static String caseName(TestCase testCase, int index) {
        return testCase.name() != null && !testCase.name().isBlank() ? testCase.name() : "case " + (index + 1);
    }

    /**
     * One conversation: start it, play every turn, judge each, end it, and delete
     * it unless the run keeps its conversations. Never throws.
     */
    AttemptResult runAttempt(RunContext ctx, int caseIndex, TestCase testCase, int repetition) {
        long startNanos = System.nanoTime();
        var turnResults = new ArrayList<TurnResult>();
        double attemptCost = 0.0;
        String conversationId = null;
        String finalState = null;
        String error = null;
        AgentTestRun run = ctx.run();
        String userId = AgentTestConversations.userIdFor(run.id(), caseIndex, repetition);
        // Armed before the conversation exists: the start turn can already call tools.
        var mocks = new ToolMockRegistry.Session(ctx.suite());
        ToolMockRegistry.register(userId, mocks);

        try {
            if (ctx.overBudget()) {
                return new AttemptResult(repetition, null, false, 0.0, 0, null, List.of(), List.of(),
                        "Not run: the run's cost ceiling of $" + ctx.maxCost() + " was reached.");
            }

            var start = conversationService.startConversation(Environment.test, run.agentId(), userId, toContext(testCase.context(), null));
            conversationId = start.conversationId();

            // The start turn (CONVERSATION_START) is paid for like any other.
            ConversationMemorySnapshot afterStart = conversationService.getConversationMemorySnapshot(conversationId);
            TurnObservation startObservation = TurnObservation.fromSnapshot(afterStart, 0);
            attemptCost += charge(ctx, startObservation);
            finalState = startObservation.conversationState();

            List<Turn> turns = testCase.turns();
            for (int t = 0; t < turns.size(); t++) {
                Turn turn = turns.get(t);
                if (ctx.overBudget()) {
                    error = "Stopped before turn " + (t + 1) + ": the run's cost ceiling of $" + ctx.maxCost() + " was reached.";
                    break;
                }
                if (!ConversationState.READY.name().equals(finalState)) {
                    // A paused, ended or failed conversation takes no more input; saying
                    // so is the honest result, not waiting for a human who is not coming.
                    error = "Stopped before turn " + (t + 1) + ": the conversation is " + finalState + ".";
                    break;
                }
                mocks.startTurn(t + 1);
                TurnResult result = runTurn(ctx, conversationId, testCase, turn, t + 1, mocks);
                turnResults.add(result);
                attemptCost += result.costUsd();
                finalState = result.conversationState();
                if (result.conversationState() == null) {
                    break; // the turn itself failed to run; its result says why
                }
            }
        } catch (Exception e) {
            LOGGER.warnf("Agent test run %s, case %d, repetition %d failed: %s", sanitize(run.id()), caseIndex, repetition, e.getMessage());
            error = "Could not run the conversation: " + safeMessage(e);
        } finally {
            if (conversationId != null) {
                cleanUp(conversationId, run.id(), Boolean.TRUE.equals(run.keepConversations()));
            }
            // Only after the conversation is ended: from here on any late call of it is
            // refused (no session, fail closed), never executed.
            ToolMockRegistry.unregister(userId);
        }

        // Every call that had no mock while unmocked calls fail is a failure of its
        // own, wherever it happened — the start turn included.
        List<String> unmocked = mocks.calls().stream().filter(c -> c.outcome() == ToolMockRegistry.Outcome.NOT_EXECUTED)
                .map(ToolMockRegistry.RecordedCall::tool).toList();
        boolean passed = error == null && unmocked.isEmpty() && turnResults.size() == testCase.turns().size()
                && turnResults.stream().allMatch(TurnResult::passed);
        return new AttemptResult(repetition, Boolean.TRUE.equals(run.keepConversations()) ? conversationId : null, passed, round(attemptCost),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos), finalState, unmocked, turnResults, error);
    }

    private TurnResult runTurn(RunContext ctx, String conversationId, TestCase testCase, Turn turn, int index, ToolMockRegistry.Session mocks) {
        long startNanos = System.nanoTime();
        int timeoutSeconds = ctx.defaults().turnTimeoutSeconds();
        var inputData = new InputData(turn.input(), toContext(testCase.context(), turn.context()));
        var outcome = new CompletableFuture<SimpleConversationMemorySnapshot>();
        SimpleConversationMemorySnapshot turnSnapshot;
        try {
            // Detailed and current step only: the record of the turn itself, with the
            // non-public audit:* keys. Not a re-read of the store, which the completion
            // can precede.
            conversationService.say(Environment.test, ctx.run().agentId(), conversationId, true, true, null, inputData, false,
                    new IConversationService.ConversationResponseHandler() {
                        @Override
                        public void onComplete(SimpleConversationMemorySnapshot snapshot) {
                            outcome.complete(snapshot);
                        }

                        @Override
                        public void onSkipped(SimpleConversationMemorySnapshot snapshot) {
                            outcome.complete(null);
                        }
                    });
            turnSnapshot = outcome.get(timeoutSeconds, TimeUnit.SECONDS);
            if (turnSnapshot == null) {
                return failedTurn(turn, index, startNanos, "turn", "executed", "skipped: the conversation was busy or paused");
            }
        } catch (TimeoutException e) {
            return failedTurn(turn, index, startNanos, "turn", "completed within " + timeoutSeconds + "s", "timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failedTurn(turn, index, startNanos, "turn", "executed", "interrupted");
        } catch (Exception e) {
            return failedTurn(turn, index, startNanos, "turn", "executed", safeMessage(e));
        }

        TurnObservation observed = withStoredCost(conversationId, index, TurnObservation.fromTurnSnapshot(turnSnapshot));
        // The registry saw every call of the turn on all three paths (LLM tools,
        // rule-triggered httpcalls and MCP calls), mocked or not; the LLM trace in
        // memory only has the first kind.
        var calls = mocks.callsOfTurn(index);
        observed = observed.withToolCalls(calls.stream().map(c -> new TurnObservation.ObservedToolCall(c.tool(), c.arguments())).toList());
        charge(ctx, observed);
        meterRegistry.counter("eddi.agent_tests.turns").increment();

        List<ExpectationFailure> failures = new ArrayList<>(ExpectationEvaluator.evaluate(turn.expect(), observed));
        calls.stream().filter(c -> c.outcome() == ToolMockRegistry.Outcome.NOT_EXECUTED)
                .forEach(c -> failures.add(new ExpectationFailure("unmockedTool", "a mock for '" + c.tool() + "'", "NOT_EXECUTED")));
        return new TurnResult(index, turn.input(), failures.isEmpty(), round(observed.costUsd()),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos), observed.actions(), truncate(observed.output()),
                observed.toolCalls().stream().map(TurnObservation.ObservedToolCall::tool).toList(), observed.conversationState(),
                failures.isEmpty() ? null : failures);
    }

    /**
     * The turn's {@code audit:cost} and model names. They are internal keys, which
     * the turn's response snapshot never carries (they are stripped from every
     * client projection), so they are read from the stored step — waiting briefly,
     * because the completion callback can run before the turn is written back.
     */
    private TurnObservation withStoredCost(String conversationId, int stepIndex, TurnObservation observed) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STORED_STEP_WAIT_SECONDS);
        try {
            while (true) {
                ConversationMemorySnapshot stored = conversationService.getConversationMemorySnapshot(conversationId);
                if (stored.getConversationSteps().size() > stepIndex) {
                    TurnObservation fromStore = TurnObservation.fromSnapshot(stored, stepIndex);
                    return observed.withCost(fromStore.costUsd(), fromStore.modelNames());
                }
                if (System.nanoTime() > deadline) {
                    LOGGER.warnf("Turn %d of test conversation %s was not stored within %ds; its cost is not counted", stepIndex,
                            sanitize(conversationId), STORED_STEP_WAIT_SECONDS);
                    return observed;
                }
                Thread.sleep(50);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return observed;
        } catch (Exception e) {
            LOGGER.warnf("Could not read the cost of test conversation %s: %s", sanitize(conversationId), e.getMessage());
            return observed;
        }
    }

    private static TurnResult failedTurn(Turn turn, int index, long startNanos, String expectation, Object expected, Object actual) {
        return new TurnResult(index, turn.input(), false, 0.0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos), null, null, null,
                null, List.of(new ExpectationFailure(expectation, expected, actual)));
    }

    private static double charge(RunContext ctx, TurnObservation observed) {
        ctx.cost().add(observed.costUsd());
        ctx.models().addAll(observed.modelNames());
        return observed.costUsd();
    }

    /**
     * Ends the conversation — which resolves a pending approval and tells an
     * in-flight turn not to write back — and deletes it unless it is to be kept.
     * Best effort: a test result is never lost to a cleanup failure.
     */
    private void cleanUp(String conversationId, String runId, boolean keep) {
        try {
            if (conversationService.getConversationState(conversationId) != ConversationState.ENDED) {
                conversationService.endConversation(conversationId, AgentTestConversations.SYSTEM_ACTOR);
            }
        } catch (Exception e) {
            LOGGER.warnf("Could not end test conversation %s: %s", sanitize(conversationId), e.getMessage());
        }
        if (keep) {
            return;
        }
        try {
            purgeService.purgeAgentTestConversation(conversationId, runId);
        } catch (Exception e) {
            LOGGER.warnf("Could not delete test conversation %s: %s", sanitize(conversationId), e.getMessage());
        }
    }

    private void audit(AgentTestRun run) {
        try {
            var input = new LinkedHashMap<String, Object>();
            input.put("runId", run.id());
            input.put("suiteId", run.suiteId());
            input.put("suiteVersion", run.suiteVersion());
            var output = new LinkedHashMap<String, Object>();
            output.put("status", run.status().name());
            output.put("casesTotal", run.casesTotal());
            output.put("casesPassed", run.casesPassed());
            output.put("casesFailed", run.casesFailed());
            output.put("costCeilingReached", run.costCeilingReached());
            output.put("models", run.models());
            auditLedgerService.submit(new AuditEntry(UUID.randomUUID().toString(), null, run.agentId(), run.agentVersion(), run.startedBy(),
                    Environment.test.name(), 0, AUDIT_TASK_ID, AUDIT_TASK_TYPE, 0, run.durationMs() != null ? run.durationMs() : 0, input, output,
                    null, null, List.of("AGENT_TEST_" + run.status().name()), run.totalCostUsd() != null ? run.totalCostUsd() : 0.0,
                    Instant.now(), null, null));
        } catch (Exception e) {
            LOGGER.warnf("Could not write the audit entry of agent test run %s: %s", sanitize(run.id()), e.getMessage());
        }
    }

    /**
     * Context values as the engine takes them: a string becomes a {@code string}
     * context, a map an {@code object}, a list an {@code array}. The turn's values
     * are applied over the case's.
     */
    static Map<String, Context> toContext(Map<String, Object> caseContext, Map<String, Object> turnContext) {
        var merged = new LinkedHashMap<String, Object>();
        if (caseContext != null) {
            merged.putAll(caseContext);
        }
        if (turnContext != null) {
            merged.putAll(turnContext);
        }
        var context = new LinkedHashMap<String, Context>();
        merged.forEach((key, value) -> {
            if (value instanceof Map<?, ?>) {
                context.put(key, new Context(Context.ContextType.object, value));
            } else if (value instanceof List<?>) {
                context.put(key, new Context(Context.ContextType.array, value));
            } else if (value != null) {
                context.put(key, new Context(Context.ContextType.string, String.valueOf(value)));
            }
        });
        return context;
    }

    private static String truncate(String text) {
        if (text == null || text.length() <= MAX_OUTPUT_CHARS) {
            return text;
        }
        return text.substring(0, MAX_OUTPUT_CHARS) + "…";
    }

    private static double round(double usd) {
        return Math.round(usd * 1_000_000d) / 1_000_000d;
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message != null ? ": " + sanitize(message) : "");
    }
}
