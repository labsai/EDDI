/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationGenerationCandidate;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.GenerationUpgradeOutcome;
import ai.labs.eddi.engine.model.ConversationUpgradeReport;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IDiscardableTask;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Moves the running conversations of an agent that sit on an older
 * compatibility generation (or on none) onto the generation of the agent's
 * current version — the operator's declaration that "these conversations are
 * compatible with the current version", for the saves that could not say so
 * themselves (a ZIP import and an agent sync always write a breaking version).
 *
 * <h2>What it writes</h2> Exactly one conversation field,
 * {@code compatibilityGeneration} (plus the revision markers that keep a stale
 * writer from putting it back — see
 * {@link IConversationMemoryStore#upgradeCompatibilityGeneration}). It does not
 * touch {@code agentVersion}, the steps or the descriptor: the conversation's
 * next turn finds itself on a generation with a {@code READY} version, runs on
 * the newest one and records the move itself
 * ({@code ConversationService.resolveConversationAgent} and
 * {@code recordAgentVersionMove}) — the one place that already knows how.
 *
 * <h2>Why it is safe next to running turns</h2>
 * <ol>
 * <li><b>Each write is a task in the conversation's own queue.</b> It goes
 * through {@link IConversationCoordinator#submitInOrder}, which runs one task
 * per conversation at a time — cluster-wide, under the conversation's lease, in
 * cluster mode. A turn is such a task, and a say turn does not persist
 * {@code IN_PROGRESS}, so the store cannot tell a running turn from an idle
 * conversation: the queue is the only thing that can, and so the upgrade waits
 * its turn behind it. No turn is in flight while the write happens.</li>
 * <li><b>A turn that loaded the conversation before the write cannot undo
 * it.</b> The write moves the revision and the history revision. A turn still
 * queued behind it is rebuilt over the new document when it starts (the
 * stale-snapshot guard, {@code reloadIfSuperseded}); a write path outside the
 * queue — an undo, a redo, a leaked turn — is refused by the store's revision
 * guard and reported as a conflict instead of silently reverting the
 * generation.</li>
 * <li><b>The write itself is one conditional update</b> on state, agent and
 * generation: a generation is never lowered and a conversation that ended,
 * paused or already moved between listing and writing is reported, not
 * overwritten.</li>
 * </ol>
 *
 * <h2>Which conversations</h2> Candidates are listed from the store by a
 * keyset-paged projection (id, state, version, generation) — never loaded in
 * full, never all at once. Moved: {@code READY}, {@code ERROR} (a failed turn
 * does not block the next one — {@code say} refuses only {@code ENDED} and
 * paused conversations — and the new version is often what fixes it) and
 * {@code EXECUTION_INTERRUPTED} (the recoverable marker). Skipped and counted:
 * {@code ENDED}; {@code AWAITING_HUMAN} (a resume must finish on the version
 * that paused it); {@code IN_PROGRESS} (a resume is running; {@code say} skips
 * such a conversation too).
 *
 * @since 6.5.0
 */
@ApplicationScoped
public class ConversationUpgradeService {

    private static final Logger LOGGER = Logger.getLogger(ConversationUpgradeService.class);

    /** Candidates read, and tasks queued, per round. */
    static final int PAGE_SIZE = 100;

    /**
     * The most candidates a single call looks at unless the caller asks for more.
     */
    public static final int DEFAULT_LIMIT = 50_000;

    /** The largest {@code limit} a caller may ask for. */
    public static final int MAX_LIMIT = 1_000_000;

    /**
     * Why a conversation was not moved — the keys of the report's {@code skipped}.
     */
    enum SkipReason {
        ENDED("ended"), AWAITING_HUMAN("awaitingHuman"), IN_PROGRESS("inProgress"), UNKNOWN_STATE("unknownState"), BUSY("busy"), CHANGED_CONCURRENTLY(
                "changedConcurrently"), ALREADY_CURRENT("alreadyCurrent"), NOT_FOUND("notFound"), FAILED("failed");

        private final String key;

        SkipReason(String key) {
            this.key = key;
        }

        String key() {
            return key;
        }
    }

    /** A request the service refuses, and which HTTP answer it maps to. */
    public static class UpgradeRefusedException extends RuntimeException {
        public enum Kind {
            /** The request is malformed: 400. */
            INVALID,
            /** The agent does not exist: 404. */
            NOT_FOUND,
            /** The agent's state forbids it: 409. */
            CONFLICT
        }

        private final Kind kind;

        public UpgradeRefusedException(Kind kind, String message) {
            super(message);
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }
    }

    private final IConversationMemoryStore conversationMemoryStore;
    private final IConversationCoordinator conversationCoordinator;
    private final IAgentStore agentStore;
    private final IAgentFactory agentFactory;
    private final Counter counterUpgraded;
    private final long turnWaitMillis;

    @Inject
    public ConversationUpgradeService(IConversationMemoryStore conversationMemoryStore, IConversationCoordinator conversationCoordinator,
            IAgentStore agentStore, IAgentFactory agentFactory, MeterRegistry meterRegistry,
            @ConfigProperty(name = "eddi.conversation-upgrade.turn-wait-ms", defaultValue = "30000") long turnWaitMillis) {
        this.conversationMemoryStore = conversationMemoryStore;
        this.conversationCoordinator = conversationCoordinator;
        this.agentStore = agentStore;
        this.agentFactory = agentFactory;
        this.counterUpgraded = meterRegistry.counter("eddi_conversation_generation_upgrade_count");
        this.turnWaitMillis = turnWaitMillis;
    }

    /**
     * @param fromVersions
     *            only conversations stored on these versions, or {@code null}/empty
     *            for every version whose conversations are on an older generation
     * @param dryRun
     *            report what would happen, write nothing
     * @param limit
     *            the most candidates to look at, {@link #DEFAULT_LIMIT} when
     *            {@code null}
     */
    public ConversationUpgradeReport upgrade(Deployment.Environment environment, String agentId, Collection<Integer> fromVersions, boolean dryRun,
                                             Integer limit) {
        int maxCandidates = limit == null ? DEFAULT_LIMIT : limit;
        if (maxCandidates < 1 || maxCandidates > MAX_LIMIT) {
            throw new UpgradeRefusedException(UpgradeRefusedException.Kind.INVALID, "limit must be between 1 and " + MAX_LIMIT);
        }

        int currentVersion;
        Integer targetGeneration;
        try {
            currentVersion = agentStore.getCurrentResourceId(agentId).getVersion();
            targetGeneration = agentStore.read(agentId, currentVersion).getCompatibilityGeneration();
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new UpgradeRefusedException(UpgradeRefusedException.Kind.NOT_FOUND, "Agent not found: " + sanitize(agentId));
        } catch (IResourceStore.ResourceStoreException | IllegalArgumentException e) {
            throw new IllegalStateException("Could not read agent " + sanitize(agentId) + ": " + e.getMessage(), e);
        }
        if (targetGeneration == null) {
            throw new UpgradeRefusedException(UpgradeRefusedException.Kind.CONFLICT,
                    "The current version (" + currentVersion + ") of agent " + sanitize(agentId) + " has no compatibility generation "
                            + "(it was stored before generations existed), so there is nothing to move conversations onto. "
                            + "Save the agent once to assign one.");
        }

        TreeSet<Integer> requested = new TreeSet<>();
        if (fromVersions != null) {
            for (Integer version : fromVersions) {
                if (version == null || version < 1 || version > currentVersion) {
                    throw new UpgradeRefusedException(UpgradeRefusedException.Kind.INVALID,
                            "fromVersion must be between 1 and the agent's current version (" + currentVersion + "), got " + version);
                }
                requested.add(version);
            }
        }

        IAgent serving;
        try {
            serving = agentFactory.getLatestReadyAgentOfGeneration(environment, agentId, targetGeneration);
        } catch (ServiceException e) {
            throw new IllegalStateException("Could not look up the deployed versions of agent " + sanitize(agentId) + ": " + e.getMessage(), e);
        }
        if (serving == null) {
            throw new UpgradeRefusedException(UpgradeRefusedException.Kind.CONFLICT,
                    "No version of compatibility generation " + targetGeneration + " of agent " + sanitize(agentId) + " is READY in "
                            + environment + " on this node. Deploy the current version (" + currentVersion + ") first and wait for it to be "
                            + "READY: a conversation moved onto a generation nothing serves would fall back to its old version or "
                            + "deploy one on demand.");
        }

        Map<Integer, Tally> tallies = new TreeMap<>(Comparator.nullsFirst(Comparator.naturalOrder()));
        for (Integer version : requested) {
            tallies.put(version, new Tally());
        }
        boolean truncated = scan(environment, agentId, requested, targetGeneration, dryRun, maxCandidates, tallies);

        List<ConversationUpgradeReport.VersionCounts> versions = new ArrayList<>();
        Tally totals = new Tally();
        tallies.forEach((version, tally) -> {
            versions.add(tally.toCounts(version));
            totals.add(tally);
        });
        var report = new ConversationUpgradeReport(agentId, environment.name(), dryRun, currentVersion, targetGeneration, serving.getAgentVersion(),
                truncated, versions, totals.toCounts(null));

        if (!dryRun) {
            counterUpgraded.increment(totals.upgraded);
        }
        LOGGER.infof(
                "Conversation generation upgrade of agent %s in %s (%s): target generation %d (serving version %d), matched %d, eligible %d, "
                        + "upgraded %d, skipped %s%s",
                sanitize(agentId), environment, dryRun ? "dry run" : "applied", targetGeneration, serving.getAgentVersion(), totals.matched,
                totals.eligible, totals.upgraded, totals.skipped, truncated ? ", stopped at the limit" : "");
        return report;
    }

    /**
     * Pages through the candidates and fills {@code tallies}.
     *
     * @return whether the limit stopped the scan with candidates left
     */
    private boolean scan(Deployment.Environment environment, String agentId, TreeSet<Integer> requested, int targetGeneration, boolean dryRun,
                         int maxCandidates, Map<Integer, Tally> tallies) {
        String after = null;
        int seen = 0;
        while (seen < maxCandidates) {
            List<ConversationGenerationCandidate> page;
            try {
                page = conversationMemoryStore.loadGenerationUpgradeCandidates(agentId, requested, targetGeneration, after,
                        Math.min(PAGE_SIZE, maxCandidates - seen));
            } catch (IResourceStore.ResourceStoreException e) {
                throw new IllegalStateException("Could not list the conversations of agent " + sanitize(agentId) + ": " + e.getMessage(), e);
            }
            if (page.isEmpty()) {
                return false;
            }
            seen += page.size();
            after = page.getLast().conversationId();
            processPage(agentId, targetGeneration, dryRun, page, tallies);
        }
        // The limit was reached: is there anything left?
        try {
            return !conversationMemoryStore.loadGenerationUpgradeCandidates(agentId, requested, targetGeneration, after, 1).isEmpty();
        } catch (IResourceStore.ResourceStoreException e) {
            return true;
        }
    }

    private void processPage(String agentId, int targetGeneration, boolean dryRun, List<ConversationGenerationCandidate> page,
                             Map<Integer, Tally> tallies) {
        List<PendingUpgrade> submitted = new ArrayList<>();
        for (ConversationGenerationCandidate candidate : page) {
            Tally tally = tallies.computeIfAbsent(candidate.agentVersion(), version -> new Tally());
            tally.matched++;
            SkipReason skip = skipBeforeWriting(candidate.conversationState());
            if (skip != null) {
                tally.skip(skip);
                continue;
            }
            tally.eligible++;
            if (!dryRun) {
                submitted.add(submit(agentId, targetGeneration, candidate, tally));
            }
        }
        // One shared deadline per page: a conversation whose turn runs long costs the
        // page at most the wait, not the wait per conversation.
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(turnWaitMillis);
        for (PendingUpgrade pending : submitted) {
            pending.settle(pending.task == null ? pending.immediate : await(pending.task, deadline));
        }
    }

    /**
     * Why a conversation in {@code state} is not even attempted, or {@code null}.
     */
    static SkipReason skipBeforeWriting(ConversationState state) {
        if (state == null) {
            return SkipReason.UNKNOWN_STATE;
        }
        return switch (state) {
            case ENDED -> SkipReason.ENDED;
            case AWAITING_HUMAN -> SkipReason.AWAITING_HUMAN;
            case IN_PROGRESS -> SkipReason.IN_PROGRESS;
            default -> GenerationUpgradeOutcome.UPGRADABLE_STATES.contains(state) ? null : SkipReason.UNKNOWN_STATE;
        };
    }

    private PendingUpgrade submit(String agentId, int targetGeneration, ConversationGenerationCandidate candidate, Tally tally) {
        var task = new UpgradeTask(agentId, targetGeneration, candidate.conversationId());
        try {
            conversationCoordinator.submitInOrder(candidate.conversationId(), task);
            return new PendingUpgrade(tally, task, null);
        } catch (RejectedExecutionException e) {
            // The coordinator is at capacity or the runtime refused it: the task is not
            // queued, so nothing can run later.
            return new PendingUpgrade(tally, null, Attempt.skipped(SkipReason.BUSY));
        } catch (RuntimeException e) {
            LOGGER.warnf("Could not queue the generation upgrade of conversation %s: %s", sanitize(candidate.conversationId()),
                    sanitize(e.getMessage()));
            return new PendingUpgrade(tally, null, Attempt.skipped(SkipReason.FAILED));
        }
    }

    /**
     * Waits for the queued write. On a timeout the task is withdrawn if it has not
     * started — so it cannot run later and move a conversation the report counted
     * as skipped — and, if it has, awaited briefly for its real answer.
     */
    private Attempt await(UpgradeTask task, long deadlineNanos) {
        try {
            return task.result.get(Math.max(0L, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            if (task.phase.compareAndSet(UpgradeTask.PENDING, UpgradeTask.WITHDRAWN)) {
                return Attempt.skipped(SkipReason.BUSY);
            }
            try {
                return task.result.get(5, TimeUnit.SECONDS);
            } catch (TimeoutException | ExecutionException | InterruptedException late) {
                if (late instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return Attempt.skipped(SkipReason.FAILED);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            task.phase.compareAndSet(UpgradeTask.PENDING, UpgradeTask.WITHDRAWN);
            return Attempt.skipped(SkipReason.BUSY);
        } catch (ExecutionException e) {
            return Attempt.skipped(SkipReason.FAILED);
        }
    }

    /** The result of one queued write: moved, or the reason it was not. */
    private record Attempt(boolean upgraded, SkipReason reason) {
        static final Attempt UPGRADED = new Attempt(true, null);

        static Attempt skipped(SkipReason reason) {
            return new Attempt(false, reason);
        }

        static Attempt of(GenerationUpgradeOutcome outcome) {
            return switch (outcome) {
                case UPGRADED -> UPGRADED;
                case NOT_FOUND -> skipped(SkipReason.NOT_FOUND);
                case ALREADY_CURRENT -> skipped(SkipReason.ALREADY_CURRENT);
                case ENDED -> skipped(SkipReason.ENDED);
                case AWAITING_HUMAN -> skipped(SkipReason.AWAITING_HUMAN);
                case IN_PROGRESS -> skipped(SkipReason.IN_PROGRESS);
                case WRONG_AGENT, CHANGED_CONCURRENTLY -> skipped(SkipReason.CHANGED_CONCURRENTLY);
            };
        }
    }

    private record PendingUpgrade(Tally tally, UpgradeTask task, Attempt immediate) {
        void settle(Attempt attempt) {
            if (attempt.upgraded()) {
                tally.upgraded++;
            } else {
                tally.skip(attempt.reason());
            }
        }
    }

    /**
     * The write, as a task of the conversation's own queue. It never throws: a task
     * that fails after it started is dead-lettered by the coordinator, and an
     * upgrade is not a turn to be replayed.
     */
    private final class UpgradeTask implements IDiscardableTask {
        static final int PENDING = 0;
        static final int RUNNING = 1;
        static final int WITHDRAWN = 2;

        final AtomicInteger phase = new AtomicInteger(PENDING);
        final CompletableFuture<Attempt> result = new CompletableFuture<>();
        private final String agentId;
        private final int targetGeneration;
        private final String conversationId;

        UpgradeTask(String agentId, int targetGeneration, String conversationId) {
            this.agentId = agentId;
            this.targetGeneration = targetGeneration;
            this.conversationId = conversationId;
        }

        @Override
        public Void call() {
            if (!phase.compareAndSet(PENDING, RUNNING)) {
                return null; // withdrawn: the caller gave up on it and counted it as skipped
            }
            try {
                result.complete(Attempt.of(conversationMemoryStore.upgradeCompatibilityGeneration(conversationId, agentId, targetGeneration)));
            } catch (Exception e) {
                LOGGER.warnf("Could not upgrade the compatibility generation of conversation %s: %s", sanitize(conversationId),
                        sanitize(e.getMessage()));
                result.complete(Attempt.skipped(SkipReason.FAILED));
            }
            return null;
        }

        @Override
        public void onDiscarded(Throwable cause) {
            if (phase.compareAndSet(PENDING, WITHDRAWN)) {
                result.complete(Attempt.skipped(SkipReason.BUSY));
            }
        }
    }

    /** Mutable counters of one source version. */
    private static final class Tally {
        long matched;
        long eligible;
        long upgraded;
        final Map<String, Long> skipped = new LinkedHashMap<>();

        void skip(SkipReason reason) {
            skipped.merge(reason.key(), 1L, Long::sum);
        }

        void add(Tally other) {
            matched += other.matched;
            eligible += other.eligible;
            upgraded += other.upgraded;
            other.skipped.forEach((reason, count) -> skipped.merge(reason, count, Long::sum));
        }

        ConversationUpgradeReport.VersionCounts toCounts(Integer version) {
            return new ConversationUpgradeReport.VersionCounts(version, matched, eligible, upgraded, new LinkedHashMap<>(skipped));
        }
    }
}
