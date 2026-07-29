/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.modules.llm.impl.SummarizationService;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.Collection;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Background dream consolidation service for persistent user memories. Handles
 * three maintenance tasks:
 * <ol>
 * <li><b>Prune stale</b> — remove entries not accessed in N days (zero LLM
 * cost)</li>
 * <li><b>Detect contradictions</b> — find conflicting entries (future:
 * LLM-driven)</li>
 * <li><b>Summarize interactions</b> — compress related entries via LLM
 * consolidation</li>
 * </ol>
 *
 * <p>
 * This service operates per-user and is invoked by the schedule system when an
 * agent has {@code dream.enabled=true} in its
 * {@link AgentConfiguration.UserMemoryConfig}. Wiring is the cluster-aware
 * schedule machinery AGENTS.md prescribes — never a private scheduler: a
 * {@code ScheduleConfiguration} carrying {@link #METADATA_TYPE_KEY} =
 * {@link #METADATA_TYPE_CONSOLIDATION} in its metadata, plus the target
 * {@code agentId} and {@code userId}, is claimed by
 * {@code SchedulePollerService} and dispatched by {@code ScheduleFireExecutor}
 * to {@link #processScheduledFire}.
 *
 * <p>
 * Cost ceiling: {@code maxCostPerRun} (US dollars, estimated from token usage)
 * bounds the spend per user per cycle. The former {@code maxSummarizationCalls}
 * count is deprecated — different consolidations cost vastly different amounts,
 * so a call count is not a budget — but it is still honoured as a secondary
 * backstop for stored configurations that set it explicitly
 * ({@link AgentConfiguration.DreamConfig#isMaxSummarizationCallsSet()}),
 * because silently dropping a bound an operator wrote is worse than enforcing a
 * redundant one.
 *
 * <p>
 * Ownership: a cycle is configured by exactly one agent, so by default it only
 * acts on memories that agent wrote ({@code sourceAgentId}) — see
 * {@link AgentConfiguration.DreamConfig#isCrossAgentMaintenance()}. Otherwise
 * agent A's retention value would delete agent B's memories and A's model
 * endpoint would see B's private text.
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
public class DreamService {

    private static final Logger LOGGER = Logger.getLogger(DreamService.class);

    /**
     * Metadata key marking a schedule as Dream-managed. Single source of truth for
     * the contract between schedule authors (REST/MCP {@code create_schedule}) and
     * the dispatcher ({@code ScheduleFireExecutor}).
     */
    public static final String METADATA_TYPE_KEY = "dreamType";
    /** Metadata value for memory-consolidation schedules. */
    public static final String METADATA_TYPE_CONSOLIDATION = "dream_consolidation";

    /**
     * Placeholder identity the schedule surface assigns when no {@code userId} is
     * supplied. Dream must never run under it: it is not a real user, so every
     * cycle would silently consolidate an empty memory set.
     */
    static final String SCHEDULER_PLACEHOLDER_USER_ID = "system:scheduler";

    /**
     * Max key length for consolidated entries (matches UserMemoryConfig.Guardrails
     * default).
     */
    static final int MAX_KEY_LENGTH = 100;
    /**
     * Max value length for consolidated entries (matches
     * UserMemoryConfig.Guardrails default).
     */
    static final int MAX_VALUE_LENGTH = 1000;
    private final IUserMemoryStore userMemoryStore;
    private final IAgentStore agentStore;
    private final SummarizationService summarizationService;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    private Counter usersProcessedCounter;
    private Counter entriesPrunedCounter;
    private Counter contradictionsFoundCounter;
    private Counter entriesSummarizedCounter;
    private Counter cyclesFailedCounter;
    private Counter summarizationFailedCounter;
    private Timer dreamDurationTimer;

    @Inject
    public DreamService(IUserMemoryStore userMemoryStore,
            IAgentStore agentStore,
            SummarizationService summarizationService,
            MeterRegistry meterRegistry,
            ObjectMapper objectMapper) {
        this.userMemoryStore = userMemoryStore;
        this.agentStore = agentStore;
        this.summarizationService = summarizationService;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void initMetrics() {
        usersProcessedCounter = meterRegistry.counter("dream.users.processed");
        entriesPrunedCounter = meterRegistry.counter("dream.entries.pruned");
        contradictionsFoundCounter = meterRegistry.counter("dream.contradictions.found");
        entriesSummarizedCounter = meterRegistry.counter("dream.entries.summarized");
        cyclesFailedCounter = meterRegistry.counter("dream.cycles.failed");
        summarizationFailedCounter = meterRegistry.counter("dream.summarization.failed");
        dreamDurationTimer = meterRegistry.timer("dream.duration");
    }

    /**
     * True if the given schedule metadata marks a Dream consolidation schedule.
     */
    public static boolean isDreamSchedule(Map<String, Object> metadata) {
        return metadata != null && METADATA_TYPE_CONSOLIDATION.equals(metadata.get(METADATA_TYPE_KEY));
    }

    /**
     * Entry point for a fired Dream schedule. Resolves the agent's
     * {@link AgentConfiguration.DreamConfig} and runs one consolidation cycle for
     * the scheduled user.
     * <p>
     * Every rejection here is logged at ERROR and returned as a failed
     * {@link DreamResult} so the caller marks the fire FAILED — a misconfigured
     * Dream schedule must be visible in the fire log and dead-letter after retries,
     * never degrade into a silent no-op.
     *
     * @param agentId
     *            the agent whose {@code userMemoryConfig.dream} block configures
     *            this cycle
     * @param agentVersion
     *            the pinned agent version, or {@code null}/{@code <= 0} for latest
     * @param userId
     *            the user whose memories to consolidate — required
     */
    public DreamResult processScheduledFire(String agentId, Integer agentVersion, String userId) {
        Instant start = Instant.now();

        if (agentId == null || agentId.isBlank()) {
            return rejected(userId, start, "Dream schedule has no agentId — cannot resolve a dream configuration.");
        }
        if (userId == null || userId.isBlank() || SCHEDULER_PLACEHOLDER_USER_ID.equals(userId)) {
            return rejected(userId, start, "Dream schedule for agent '" + agentId + "' has no real userId (got '" + userId
                    + "'). Set 'userId' on the schedule to the user whose memories should be consolidated.");
        }

        AgentConfiguration agentConfiguration;
        try {
            int version = agentVersion != null && agentVersion > 0
                    ? agentVersion
                    : currentVersionOf(agentId);
            if (version <= 0) {
                // getCurrentResourceId returns null on PostgreSQL for an agent with no
                // deployed version (GroupConversationService documents the same). Left
                // implicit it became an NPE whose message is null, so the operator-facing
                // reason read "Could not read agent 'x': null" — the one string that has
                // to be actionable, since every rejection here explains a misconfigured
                // Dream schedule.
                return rejected(userId, start, "Agent '" + agentId + "' has no current version — deploy it before scheduling a dream cycle, "
                        + "or pin an explicit agentVersion on the schedule.");
            }
            agentConfiguration = agentStore.read(agentId, version);
        } catch (Exception e) {
            LOGGER.errorf(e, "[DREAM] Could not read agent '%s' (version=%s) for a scheduled dream cycle", agentId, agentVersion);
            return rejected(userId, start, "Could not read agent '" + agentId + "': " + describe(e));
        }

        if (agentConfiguration == null) {
            return rejected(userId, start, "Agent '" + agentId + "' not found — cannot run dream consolidation.");
        }

        var memoryConfig = agentConfiguration.getUserMemoryConfig();
        var dreamConfig = memoryConfig != null ? memoryConfig.getDream() : null;
        if (dreamConfig == null || !dreamConfig.isEnabled()) {
            return rejected(userId, start, "Agent '" + agentId + "' has dream consolidation disabled "
                    + "(userMemoryConfig.dream.enabled=false or absent), but a dream schedule fired for it.");
        }

        return process(userId, agentId, dreamConfig);
    }

    private DreamResult rejected(String userId, Instant start, String reason) {
        LOGGER.errorf("[DREAM] %s", reason);
        cyclesFailedCounter.increment();
        return new DreamResult(userId, 0, 0, 0, Duration.between(start, Instant.now()).toMillis(), 0.0, reason);
    }

    /**
     * Restrict a user's memory set to what the firing agent is entitled to
     * maintain.
     * <p>
     * {@code getAllEntries(userId)} is deliberately agent-unscoped (its documented
     * use case is admin/export), but every knob this cycle obeys —
     * {@code pruneStaleAfterDays}, the grouping strategy, the consolidation model
     * and its endpoint — comes from one agent's {@code userMemoryConfig.dream}.
     * Acting on the whole set would let agent A delete agent B's memories under a
     * retention value B's owner never configured, and hand B's {@code self}-scoped
     * text to A's provider. So the default keeps the same ownership rule
     * {@code UserMemoryTool.evictableEntries()} applies before it evicts: an entry
     * belongs to the agent whose id is its {@code sourceAgentId}, and nothing else
     * is touched. Entries without a {@code sourceAgentId} have no owner and are
     * therefore left alone as well.
     * <p>
     * {@code crossAgentMaintenance=true} opts back into whole-set maintenance for a
     * dedicated housekeeping agent — the cross-agent consolidation
     * {@code preserveAgentProvenance=false} describes.
     */
    private static List<UserMemoryEntry> scopeToOwningAgent(List<UserMemoryEntry> entries, String agentId,
                                                            AgentConfiguration.DreamConfig dreamConfig) {
        if (dreamConfig.isCrossAgentMaintenance()) {
            return entries;
        }

        List<UserMemoryEntry> owned = entries.stream()
                .filter(entry -> agentId != null && agentId.equals(entry.sourceAgentId()))
                .toList();

        int foreign = entries.size() - owned.size();
        if (foreign > 0) {
            LOGGER.infof("[DREAM] Skipping %d of %d memory entries not owned by agent '%s' — set "
                    + "userMemoryConfig.dream.crossAgentMaintenance=true if this agent is meant to maintain "
                    + "the user's memories across agents.", foreign, entries.size(), agentId);
        }
        return owned;
    }

    /**
     * Process dream consolidation for a specific user's memories. Called by
     * {@link #processScheduledFire} when a Dream schedule fires.
     *
     * @param userId
     *            the user whose memories to consolidate
     * @param agentId
     *            the agent whose {@code dreamConfig} governs this cycle — also the
     *            ownership boundary: unless
     *            {@link AgentConfiguration.DreamConfig#isCrossAgentMaintenance()},
     *            only memories this agent wrote are touched
     * @param dreamConfig
     *            the dream configuration from the agent
     * @return a summary of what was done
     */
    public DreamResult process(String userId, String agentId, AgentConfiguration.DreamConfig dreamConfig) {
        Instant start = Instant.now();
        int pruned = 0;
        int contradictions = 0;
        int summarized = 0;
        double estimatedCost = 0.0;
        String summarizationError = null;

        try {
            LOGGER.infof("[DREAM] Starting dream cycle for user='%s', agent='%s'", userId, agentId);

            // Load entries once — shared across pruning and contradiction detection
            List<UserMemoryEntry> allEntries = scopeToOwningAgent(userMemoryStore.getAllEntries(userId), agentId, dreamConfig);

            // 1. Prune stale entries (deterministic, zero LLM cost)
            if (dreamConfig.getPruneStaleAfterDays() > 0) {
                pruned = pruneStaleEntries(userId, allEntries, dreamConfig.getPruneStaleAfterDays());
            }

            // After pruning, reload once — shared by contradiction detection and
            // summarization
            List<UserMemoryEntry> currentEntries = pruned > 0
                    ? scopeToOwningAgent(userMemoryStore.getAllEntries(userId), agentId, dreamConfig)
                    : allEntries;

            // 2. Detect contradictions (read-only — does not modify entries)
            if (dreamConfig.isDetectContradictions()) {
                contradictions = detectContradictions(userId, currentEntries);
            }

            // 3. Summarize interactions (LLM-driven consolidation)
            if (dreamConfig.isSummarizeInteractions()) {
                var outcome = summarizeInteractions(userId, currentEntries, dreamConfig);
                summarized = outcome.entriesReduced();
                estimatedCost = outcome.estimatedCostUsd();
                summarizationError = outcome.error();
            }

            usersProcessedCounter.increment();
            var duration = Duration.between(start, Instant.now());
            dreamDurationTimer.record(duration);

            if (summarizationError != null) {
                cyclesFailedCounter.increment();
                LOGGER.errorf("[DREAM] Completed WITH ERRORS for user='%s': pruned=%d, contradictions=%d, summarized=%d, "
                        + "estimatedCost=$%.4f, duration=%dms, error=%s", userId, pruned, contradictions, summarized, estimatedCost,
                        duration.toMillis(), summarizationError);
            } else {
                LOGGER.infof("[DREAM] Completed for user='%s': pruned=%d, contradictions=%d, summarized=%d, "
                        + "estimatedCost=$%.4f, duration=%dms", userId, pruned, contradictions, summarized, estimatedCost, duration.toMillis());
            }

            return new DreamResult(userId, pruned, contradictions, summarized, duration.toMillis(), estimatedCost, summarizationError);

        } catch (Exception e) {
            cyclesFailedCounter.increment();
            LOGGER.errorf(e, "[DREAM] Failed for user='%s'", userId);
            return new DreamResult(userId, pruned, contradictions, summarized, Duration.between(start, Instant.now()).toMillis(), estimatedCost,
                    describe(e));
        }
    }

    /**
     * Remove entries that haven't been accessed in the specified number of days.
     * This is a deterministic operation with zero LLM cost.
     */
    private int pruneStaleEntries(String userId, List<UserMemoryEntry> allEntries, int staleAfterDays) {
        Instant cutoff = Instant.now().minus(Duration.ofDays(staleAfterDays));

        int pruned = 0;
        for (UserMemoryEntry entry : allEntries) {
            if (entry.updatedAt() != null && entry.updatedAt().isBefore(cutoff)) {
                try {
                    userMemoryStore.deleteEntry(entry.id());
                    pruned++;
                    entriesPrunedCounter.increment();
                } catch (Exception e) {
                    LOGGER.warnf("[DREAM] Failed to prune entry '%s' for user '%s': %s", entry.key(), userId, e.getMessage());
                }
            }
        }

        if (pruned > 0) {
            LOGGER.infof("[DREAM] Pruned %d stale entries (>%d days) for user='%s'", pruned, staleAfterDays, userId);
        }

        return pruned;
    }

    /**
     * Detect contradictory entries. V1: Simple key-based duplicate detection (same
     * key, different values). V2 (future): LLM-driven semantic contradiction
     * detection.
     */
    private int detectContradictions(String userId, List<UserMemoryEntry> allEntries) {
        var keyValues = new HashMap<String, UserMemoryEntry>();
        int contradictions = 0;

        for (UserMemoryEntry entry : allEntries) {
            if (keyValues.containsKey(entry.key())) {
                UserMemoryEntry existing = keyValues.get(entry.key());
                if (!Objects.equals(existing.value(), entry.value())) {
                    contradictions++;
                    contradictionsFoundCounter.increment();
                    LOGGER.infof("[DREAM] Contradiction found for user='%s', key='%s': '%s' vs '%s'", userId, entry.key(), existing.value(),
                            entry.value());
                }
            }
            keyValues.put(entry.key(), entry);
        }

        return contradictions;
    }

    /**
     * Outcome of the summarization phase.
     *
     * @param entriesReduced
     *            net number of entries removed by consolidation
     * @param estimatedCostUsd
     *            estimated dollar spend of this phase
     * @param error
     *            {@code null} on success; a human-readable cause when the phase was
     *            aborted, so the caller can fail the schedule fire instead of
     *            reporting a silent no-op
     */
    record SummarizationOutcome(int entriesReduced, double estimatedCostUsd, String error) {
    }

    /**
     * Summarize related interactions using LLM-driven consolidation. Groups entries
     * by the configured strategy, calls the LLM to distill each group, and
     * atomically replaces originals with consolidated entries.
     *
     * <p>
     * Safety guarantees:
     * <ul>
     * <li>New entries are inserted BEFORE originals are deleted</li>
     * <li>If insert fails, originals are preserved</li>
     * <li>If LLM returns empty/garbage, the group is skipped</li>
     * <li>If LLM returns more entries than input, the group is skipped</li>
     * <li>Cost bounded by {@code maxCostPerRun} (estimated from token usage), plus
     * the deprecated {@code maxSummarizationCalls} when a config sets it</li>
     * <li>A <em>permanent</em> LLM failure (auth, endpoint, unknown model) aborts
     * the phase and is reported, never swallowed</li>
     * <li>A <em>transient</em> LLM failure (rate limit, timeout, 5xx) skips its
     * group and is logged, but does not fail the cycle — see
     * {@link #isTransientLlmFailure}</li>
     * </ul>
     */
    private SummarizationOutcome summarizeInteractions(String userId,
                                                       List<UserMemoryEntry> entries,
                                                       AgentConfiguration.DreamConfig config) {
        int totalConsolidated = 0;
        int llmCallsMade = 0;
        int transientFailures = 0;
        double estimatedCostAccumulated = 0.0;

        // 1. Build groups
        Map<String, List<UserMemoryEntry>> groups = buildGroups(entries, config);

        for (var group : groups.entrySet()) {
            List<UserMemoryEntry> groupEntries = group.getValue();

            // Skip groups below threshold
            if (groupEntries.size() < config.getSummarizeMinEntries()) {
                continue;
            }

            // Respect cost ceiling (soft cap: checked before each call, so the
            // last call may push total slightly over — this is by design, since
            // we cannot know output cost before the call). This dollar budget is
            // the primary ceiling, because a call count says nothing about spend.
            if (estimatedCostAccumulated >= config.getMaxCostPerRun()) {
                LOGGER.infof("[DREAM] Cost ceiling ($%.4f >= $%.2f) reached for user='%s' " +
                        "after %d calls", estimatedCostAccumulated, config.getMaxCostPerRun(), userId, llmCallsMade);
                break;
            }

            // Deprecated secondary backstop: a stored configuration that explicitly
            // sets maxSummarizationCalls asked for a hard call ceiling, and silently
            // dropping it would let "at most 3 calls" turn into hundreds under the
            // dollar budget alone. Configs that never set it are bounded by
            // maxCostPerRun only — the field's default value caps nothing.
            if (config.isMaxSummarizationCallsSet() && llmCallsMade >= config.getMaxSummarizationCalls()) {
                LOGGER.warnf("[DREAM] Legacy call ceiling maxSummarizationCalls=%d reached for user='%s' "
                        + "after $%.4f of an allowed $%.2f. This field is deprecated — configure maxCostPerRun "
                        + "instead, which bounds actual spend.",
                        config.getMaxSummarizationCalls(), userId, estimatedCostAccumulated, config.getMaxCostPerRun());
                break;
            }

            // 2. Build content: JSON array of entries
            String content = buildEntriesJson(groupEntries);

            // 3. Call LLM. Dream is a background job with no parent LLM task to
            // inherit credentials from, so the model parameters come from the
            // agent's dream config (finding I1/F13). A PERMANENT failure is almost
            // always a configuration fault that would repeat for every remaining
            // group — abort the phase, log at ERROR and report it upward so the
            // schedule fire is marked FAILED, instead of leaving Dream to look
            // like it ran and simply found nothing to do. A TRANSIENT failure
            // (rate limit, timeout, 5xx) is not a fault of the configuration and
            // must not be reported as a failed fire: three of them in a row would
            // exhaust the schedule's retry budget and dead-letter the user's dream
            // schedule for good over what is typically a minutes-long provider blip.
            SummarizationService.SummarizationResult llmResult;
            try {
                llmResult = summarizationService.summarizeWithUsage(
                        content, config.getSummarizationPrompt(),
                        config.getLlmProvider(), config.getLlmModel(),
                        config.getParameters());
            } catch (Exception e) {
                summarizationFailedCounter.increment();
                if (isTransientLlmFailure(e)) {
                    transientFailures++;
                    LOGGER.warnf(e, "[DREAM] Memory consolidation LLM call failed transiently for user='%s', group='%s' "
                            + "(provider=%s, model=%s). Original entries are preserved; skipping this group and continuing. "
                            + "The cycle is NOT marked failed, so a provider blip cannot dead-letter the dream schedule.",
                            userId, group.getKey(), config.getLlmProvider(), config.getLlmModel());
                    continue;
                }
                LOGGER.errorf(e, "[DREAM] Memory consolidation LLM call failed for user='%s', group='%s' "
                        + "(provider=%s, model=%s, configured parameter keys=%s). Original entries are preserved and "
                        + "consolidation is ABORTED for this cycle. If this is an authentication or endpoint error, set the "
                        + "credentials on the agent under userMemoryConfig.dream.parameters (e.g. \"apiKey\": \"${vault:my-key}\") "
                        + "— a background dream cycle has no parent LLM task to inherit them from.",
                        userId, group.getKey(), config.getLlmProvider(), config.getLlmModel(), parameterKeys(config));
                return new SummarizationOutcome(totalConsolidated, estimatedCostAccumulated,
                        "Memory consolidation LLM call failed (" + config.getLlmProvider() + "/" + config.getLlmModel() + "): "
                                + e.getMessage());
            }
            llmCallsMade++;
            estimatedCostAccumulated += estimateCost(llmResult, content.length());

            // 4. Parse response (handles markdown fences, validates output)
            List<ConsolidatedEntry> consolidated = parseConsolidatedEntries(llmResult.summary());

            if (consolidated.isEmpty()) {
                LOGGER.warnf("[DREAM] Summarization returned empty/invalid result for " +
                        "user='%s', group='%s'. Preserving original entries.", userId, group.getKey());
                continue;
            }

            // 5. Validate: consolidated must be fewer than originals
            if (consolidated.size() >= groupEntries.size()) {
                LOGGER.warnf("[DREAM] LLM returned %d entries (>= %d originals). " +
                        "Skipping group '%s'.", consolidated.size(), groupEntries.size(), group.getKey());
                continue;
            }

            // 6. Cap at target (guaranteed >= 1 by DreamConfig validation)
            int target = Math.max(1, config.getSummarizeTargetEntries());
            if (consolidated.size() > target) {
                consolidated = consolidated.subList(0, target);
            }

            // 7. SAFETY: Insert new entries FIRST
            // Derive provenance from the group. Visibility is the most restrictive
            // of the originals and is NEVER widened (finding G8): a self-scoped
            // memory belongs to exactly one agent, so it may only ever be merged
            // with entries of that same agent — buildGroups guarantees that by
            // splitting self-scoped groups per sourceAgentId.
            List<String> insertedIds = new ArrayList<>();
            try {
                Visibility mergedVisibility = mostRestrictiveVisibility(groupEntries);
                Set<String> distinctAgents = groupEntries.stream()
                        .map(UserMemoryEntry::sourceAgentId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
                String sourceAgent = distinctAgents.size() == 1
                        ? distinctAgents.iterator().next()
                        : groupEntries.getFirst().sourceAgentId();
                // Defence in depth: should a future grouping change ever hand us a
                // self-scoped group spanning several agents, skip it rather than
                // widen it — a privacy boundary must fail closed.
                if (distinctAgents.size() > 1 && mergedVisibility == Visibility.self) {
                    LOGGER.errorf("[DREAM] Refusing to merge self-scoped entries from %d agents for user='%s', "
                            + "group='%s' — that would expose one agent's private memories to the others.",
                            distinctAgents.size(), userId, group.getKey());
                    continue;
                }
                Instant earliestCreated = groupEntries.stream()
                        .map(UserMemoryEntry::createdAt)
                        .filter(Objects::nonNull)
                        .min(Instant::compareTo).orElse(Instant.now());
                // Merge groupIds from all originals to preserve group-scoped reachability
                List<String> mergedGroupIds = groupEntries.stream()
                        .map(UserMemoryEntry::groupIds)
                        .filter(Objects::nonNull)
                        .flatMap(Collection::stream)
                        .distinct()
                        .collect(Collectors.toList());

                for (var entry : consolidated) {
                    String id = userMemoryStore.upsert(new UserMemoryEntry(
                            null, userId, entry.key(), entry.value(),
                            groupEntries.getFirst().category(),
                            mergedVisibility, sourceAgent, mergedGroupIds,
                            "dream-consolidation", false, 0,
                            earliestCreated, Instant.now()));
                    insertedIds.add(id);
                }
            } catch (Exception e) {
                LOGGER.warnf("[DREAM] Failed to insert consolidated entries for " +
                        "user='%s', group='%s': %s. Originals preserved, rolling back %d inserts.",
                        userId, group.getKey(), e.getMessage(), insertedIds.size());
                // Rollback: delete any partially-inserted consolidated entries
                for (String insertedId : insertedIds) {
                    try {
                        userMemoryStore.deleteEntry(insertedId);
                    } catch (Exception rollbackEx) {
                        LOGGER.warnf("[DREAM] Rollback delete failed for '%s': %s",
                                insertedId, rollbackEx.getMessage());
                    }
                }
                continue; // Insert failed → don't delete anything
            }

            // 8. Delete originals (only after ALL inserts succeeded)
            int actualDeleted = 0;
            for (var original : groupEntries) {
                try {
                    userMemoryStore.deleteEntry(original.id());
                    actualDeleted++;
                } catch (Exception e) {
                    LOGGER.warnf("[DREAM] Failed to delete original entry '%s': %s. " +
                            "Duplicate may remain until next dream cycle.",
                            original.id(), e.getMessage());
                }
            }

            // Track actual reduction (not intent) for accurate metrics
            int reduced = actualDeleted - consolidated.size();
            if (reduced > 0) {
                totalConsolidated += reduced;
                entriesSummarizedCounter.increment(reduced);
            }
        }

        if (transientFailures > 0) {
            LOGGER.warnf("[DREAM] %d of %d groups were skipped for user='%s' after transient LLM failures — "
                    + "they are retried on the next dream cycle.", transientFailures, groups.size(), userId);
        }

        return new SummarizationOutcome(totalConsolidated, estimatedCostAccumulated, null);
    }

    /**
     * Transient-failure signatures in an exception message — throttling, timeouts
     * and server-side 5xx, which resolve on their own. Mirrors
     * {@code CascadingModelExecutor.isRetryableError} /
     * {@code AgentExecutionHelper} (both private to their modules).
     */
    private static final Pattern TRANSIENT_LLM_FAILURE = Pattern.compile(
            "timeout|timed out|rate limit|too many requests|429|50[234]|529|overloaded|temporarily unavailable",
            Pattern.CASE_INSENSITIVE);

    /**
     * Whether an LLM failure is transient (retry later succeeds) rather than a
     * permanent configuration fault (bad credentials, wrong endpoint, unknown
     * model). Only the latter should fail the schedule fire, because a FAILED fire
     * consumes the schedule's dead-letter budget.
     */
    static boolean isTransientLlmFailure(Throwable throwable) {
        Throwable current = throwable;
        // Bounded walk — a self-referential cause chain must not spin forever.
        for (int depth = 0; current != null && depth < 10; depth++, current = current.getCause()) {
            if (current instanceof SocketTimeoutException || current instanceof TimeoutException
                    || current instanceof ConnectException || current instanceof UnknownHostException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && TRANSIENT_LLM_FAILURE.matcher(message).find()) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    /**
     * The configured model parameter <i>keys</i> — never the values, which hold
     * credentials. Used to make a failure diagnosable without leaking secrets.
     */
    private static String parameterKeys(AgentConfiguration.DreamConfig config) {
        var parameters = config.getParameters();
        return parameters == null || parameters.isEmpty() ? "<none configured>" : parameters.keySet().toString();
    }

    /**
     * Estimate cost from an LLM summarization result using token usage. Uses a
     * conservative upper-bound rate of $0.01 per 1,000 tokens when the provider
     * doesn't expose real pricing. Falls back to character-based heuristic (~4
     * chars per token) when token counts are unavailable.
     *
     * @param result
     *            the LLM result with token usage
     * @param inputContentLength
     *            length of the input text sent to the LLM
     */
    static double estimateCost(SummarizationService.SummarizationResult result,
                               int inputContentLength) {
        // Conservative upper-bound: $0.01 per 1K tokens
        double ratePerToken = 0.01 / 1000.0;

        if (result.totalTokens() > 0) {
            return result.totalTokens() * ratePerToken;
        }

        // Fallback: estimate from input + output character length (~4 chars per token)
        int outputLength = result.summary() != null ? result.summary().length() : 0;
        int estimatedTokens = (inputContentLength + outputLength) / 4;
        return estimatedTokens * ratePerToken;
    }

    /**
     * Build entry groups according to the configured grouping strategy.
     * <p>
     * Whatever the strategy, the result is post-processed by
     * {@link #splitSelfScopedGroupsByAgent} so a {@code self}-scoped memory is
     * never merged with another agent's memories.
     */
    private Map<String, List<UserMemoryEntry>> buildGroups(
                                                           List<UserMemoryEntry> entries,
                                                           AgentConfiguration.DreamConfig config) {

        if ("all".equals(config.getSummarizeGroupBy())) {
            // Single group
            return splitSelfScopedGroupsByAgent(Map.of("all", new ArrayList<>(entries)));
        }

        // Default: group by category (null-safe — legacy entries may lack category)
        Map<String, List<UserMemoryEntry>> byCategory = entries.stream()
                .collect(Collectors.groupingBy(
                        e -> e.category() != null ? e.category() : "fact"));

        if (!config.isPreserveAgentProvenance()) {
            return splitSelfScopedGroupsByAgent(byCategory);
        }

        // Sub-group by agent within each category
        Map<String, List<UserMemoryEntry>> result = new LinkedHashMap<>();
        for (var catGroup : byCategory.entrySet()) {
            catGroup.getValue().stream()
                    .collect(Collectors.groupingBy(e -> e.sourceAgentId() != null ? e.sourceAgentId() : "unknown"))
                    .forEach((agentId, agentEntries) -> result.put(catGroup.getKey() + ":" + agentId, agentEntries));
        }
        return splitSelfScopedGroupsByAgent(result);
    }

    /**
     * Privacy boundary (finding G8): a {@code self}-scoped memory is readable only
     * by the agent that wrote it. Consolidating one into a shared entry would
     * either lose it for its owner or — as the previous implementation did by
     * upgrading the merged visibility to {@code global} — expose it to every other
     * agent. Since {@code summarizeGroupBy} defaults to {@code "category"} and
     * {@code preserveAgentProvenance} defaults to {@code false}, cross-agent
     * grouping was the default path, so that widening was the default behaviour.
     * <p>
     * Any group whose most restrictive visibility is {@code self} is therefore
     * split per {@code sourceAgentId}, producing one consolidated entry per
     * contributing agent, each keeping {@code self} and its own provenance. Groups
     * that are already {@code group}- or {@code global}-scoped are left untouched —
     * they were shared to begin with. Entries without a {@code sourceAgentId} are
     * kept in their own bucket rather than folded into an arbitrary agent's.
     */
    private static Map<String, List<UserMemoryEntry>> splitSelfScopedGroupsByAgent(
                                                                                   Map<String, List<UserMemoryEntry>> groups) {
        Map<String, List<UserMemoryEntry>> result = new LinkedHashMap<>();
        for (var group : groups.entrySet()) {
            List<UserMemoryEntry> groupEntries = group.getValue();
            if (groupEntries.isEmpty() || mostRestrictiveVisibility(groupEntries) != Visibility.self) {
                result.put(group.getKey(), groupEntries);
                continue;
            }

            Map<String, List<UserMemoryEntry>> byAgent = groupEntries.stream()
                    .collect(Collectors.groupingBy(e -> e.sourceAgentId() != null ? e.sourceAgentId() : "unknown",
                            LinkedHashMap::new, Collectors.toList()));
            if (byAgent.size() <= 1) {
                result.put(group.getKey(), groupEntries);
                continue;
            }

            LOGGER.infof("[DREAM] Group '%s' holds self-scoped memories from %d agents — consolidating each agent "
                    + "separately so no private memory is widened.", group.getKey(), byAgent.size());
            byAgent.forEach((agentId, agentEntries) -> result.put(group.getKey() + ":" + agentId, agentEntries));
        }
        return result;
    }

    /**
     * Parse the LLM consolidation response into structured entries. Handles
     * markdown fences ({@code ```json ... ```}) and extracts the JSON array.
     */
    record ConsolidatedEntry(String key, String value) {
    }

    List<ConsolidatedEntry> parseConsolidatedEntries(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) {
            return List.of();
        }

        // Strip markdown fences: ```json ... ``` or ``` ... ```
        String cleaned = llmResponse.strip();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            int lastFence = cleaned.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                cleaned = cleaned.substring(firstNewline + 1, lastFence).strip();
            }
        }

        // Extract JSON array if surrounded by other text
        int start = cleaned.indexOf('[');
        int end = cleaned.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return List.of();
        }
        cleaned = cleaned.substring(start, end + 1);

        try {
            var entries = objectMapper.readValue(cleaned,
                    new TypeReference<List<Map<String, String>>>() {
                    });
            return entries.stream()
                    .filter(m -> m.containsKey("key") && m.containsKey("value"))
                    .filter(m -> m.get("key") != null && !m.get("key").isBlank())
                    .filter(m -> m.get("value") != null && !m.get("value").isBlank())
                    .map(m -> new ConsolidatedEntry(
                            truncate(m.get("key").strip(), MAX_KEY_LENGTH),
                            truncate(m.get("value").strip(), MAX_VALUE_LENGTH)))
                    .toList();
        } catch (Exception e) {
            LOGGER.warnf("[DREAM] Failed to parse LLM consolidation response: %s",
                    e.getMessage());
            return List.of();
        }
    }

    /**
     * Determine the most restrictive visibility from a group of entries. Order:
     * self (most restrictive) > group > global (least restrictive).
     */
    static Visibility mostRestrictiveVisibility(List<UserMemoryEntry> entries) {
        boolean hasSelf = entries.stream().anyMatch(e -> e.visibility() == Visibility.self);
        if (hasSelf)
            return Visibility.self;
        boolean hasGroup = entries.stream().anyMatch(e -> e.visibility() == Visibility.group);
        if (hasGroup)
            return Visibility.group;
        return Visibility.global;
    }

    /**
     * Build a JSON array string from memory entries for the LLM prompt. Uses the
     * injected {@link ObjectMapper} for proper serialization.
     */
    private String buildEntriesJson(List<UserMemoryEntry> entries) {
        var list = entries.stream()
                .map(e -> Map.of("key", e.key(), "value", String.valueOf(e.value())))
                .toList();
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(list);
        } catch (Exception e) {
            LOGGER.warnf("[DREAM] Failed to serialize entries to JSON: %s", e.getMessage());
            // Fallback: manual construction for resilience
            var sb = new StringBuilder("[");
            for (int i = 0; i < entries.size(); i++) {
                var entry = entries.get(i);
                sb.append("{\"key\": \"").append(escapeJson(entry.key()))
                        .append("\", \"value\": \"").append(escapeJson(String.valueOf(entry.value())))
                        .append("\"}");
                if (i < entries.size() - 1)
                    sb.append(",");
            }
            sb.append("]");
            return sb.toString();
        }
    }

    /**
     * Escape a string for safe inclusion in a JSON value. Uses Jackson's
     * {@link JsonStringEncoder} for complete RFC 8259 compliance (handles all
     * control characters, unicode, etc.).
     */
    static String escapeJson(String text) {
        if (text == null)
            return "";
        return new String(JsonStringEncoder.getInstance().quoteAsString(text));
    }

    /** Truncate a string to maxLength, appending "…" if truncated. */
    static String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 1) + "…";
    }

    /**
     * Result of a dream consolidation cycle.
     *
     * @param estimatedCostUsd
     *            estimated LLM spend of this cycle — reported on the schedule fire
     *            log so the dollar budget is observable
     * @param error
     *            {@code null} on success; otherwise the cause, which the schedule
     *            dispatcher turns into a FAILED fire
     */
    /**
     * A never-null, always-informative description of a failure.
     * <p>
     * {@code DreamResult.isSuccess()} is {@code error == null}, and
     * {@link Throwable#getMessage()} is null for plenty of real exceptions
     * (NullPointerException among them) — so passing the raw message through made a
     * crashed cycle report itself as a SUCCESSFUL one, and the schedule's failure
     * bookkeeping never ran.
     */
    private static String describe(Throwable e) {
        String message = e.getMessage();
        return (message == null || message.isBlank()) ? e.getClass().getSimpleName() : e.getClass().getSimpleName() + ": " + message;
    }

    /**
     * Current version of an agent, or {@code -1} when it has none. Isolated so the
     * null {@code getCurrentResourceId} contract is handled in one place rather
     * than surfacing as an NPE at the call site.
     */
    private int currentVersionOf(String agentId) throws Exception {
        var currentId = agentStore.getCurrentResourceId(agentId);
        return currentId == null || currentId.getVersion() == null ? -1 : currentId.getVersion();
    }

    public record DreamResult(String userId, int entriesPruned, int contradictionsFound, int entriesSummarized, long durationMs,
            double estimatedCostUsd, String error) {

        public boolean isSuccess() {
            return error == null;
        }
    }
}
