/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

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

import java.time.Duration;
import java.time.Instant;
import java.util.*;
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
 * {@link AgentConfiguration.UserMemoryConfig}.
 *
 * <p>
 * Cost ceiling: {@code maxSummarizationCalls} bounds LLM calls per user per
 * cycle. Round-robin: processes users ordered by oldest {@code updatedAt}.
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
public class DreamService {

    private static final Logger LOGGER = Logger.getLogger(DreamService.class);

    private final IUserMemoryStore userMemoryStore;
    private final SummarizationService summarizationService;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    private Counter usersProcessedCounter;
    private Counter entriesPrunedCounter;
    private Counter contradictionsFoundCounter;
    private Counter entriesSummarizedCounter;
    private Timer dreamDurationTimer;

    @Inject
    public DreamService(IUserMemoryStore userMemoryStore,
            SummarizationService summarizationService,
            MeterRegistry meterRegistry,
            ObjectMapper objectMapper) {
        this.userMemoryStore = userMemoryStore;
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
        dreamDurationTimer = meterRegistry.timer("dream.duration");
    }

    /**
     * Process dream consolidation for a specific user's memories. Called by the
     * schedule system when a SERVICE-type schedule fires.
     *
     * @param userId
     *            the user whose memories to consolidate
     * @param dreamConfig
     *            the dream configuration from the agent
     * @return a summary of what was done
     */
    public DreamResult process(String userId, AgentConfiguration.DreamConfig dreamConfig) {
        Instant start = Instant.now();
        int pruned = 0;
        int contradictions = 0;
        int summarized = 0;

        try {
            LOGGER.infof("[DREAM] Starting dream cycle for user='%s'", userId);

            // Load entries once — shared across pruning and contradiction detection
            List<UserMemoryEntry> allEntries = userMemoryStore.getAllEntries(userId);

            // 1. Prune stale entries (deterministic, zero LLM cost)
            if (dreamConfig.getPruneStaleAfterDays() > 0) {
                pruned = pruneStaleEntries(userId, allEntries, dreamConfig.getPruneStaleAfterDays());
            }

            // After pruning, reload once — shared by contradiction detection and
            // summarization
            List<UserMemoryEntry> currentEntries = pruned > 0
                    ? userMemoryStore.getAllEntries(userId)
                    : allEntries;

            // 2. Detect contradictions (read-only — does not modify entries)
            if (dreamConfig.isDetectContradictions()) {
                contradictions = detectContradictions(userId, currentEntries);
            }

            // 3. Summarize interactions (LLM-driven consolidation)
            if (dreamConfig.isSummarizeInteractions()) {
                summarized = summarizeInteractions(userId, currentEntries, dreamConfig);
            }

            usersProcessedCounter.increment();
            var duration = Duration.between(start, Instant.now());
            dreamDurationTimer.record(duration);

            LOGGER.infof("[DREAM] Completed for user='%s': pruned=%d, contradictions=%d, summarized=%d, duration=%dms", userId, pruned,
                    contradictions, summarized, duration.toMillis());

            return new DreamResult(userId, pruned, contradictions, summarized, duration.toMillis(), null);

        } catch (Exception e) {
            LOGGER.errorf(e, "[DREAM] Failed for user='%s'", userId);
            return new DreamResult(userId, pruned, contradictions, summarized, Duration.between(start, Instant.now()).toMillis(), e.getMessage());
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
     * <li>Call count bounded by {@code maxSummarizationCalls}</li>
     * <li>Cost bounded by {@code maxCostPerRun} (estimated from token usage)</li>
     * </ul>
     */
    private int summarizeInteractions(String userId,
                                      List<UserMemoryEntry> entries,
                                      AgentConfiguration.DreamConfig config) {
        int totalConsolidated = 0;
        int llmCallsMade = 0;
        double estimatedCostAccumulated = 0.0;

        // 1. Build groups
        Map<String, List<UserMemoryEntry>> groups = buildGroups(entries, config);

        for (var group : groups.entrySet()) {
            List<UserMemoryEntry> groupEntries = group.getValue();

            // Skip groups below threshold
            if (groupEntries.size() < config.getSummarizeMinEntries()) {
                continue;
            }

            // Respect call limit
            if (llmCallsMade >= config.getMaxSummarizationCalls()) {
                LOGGER.infof("[DREAM] Summarization call limit (%d) reached for user='%s'",
                        config.getMaxSummarizationCalls(), userId);
                break;
            }

            // Respect cost ceiling
            if (estimatedCostAccumulated >= config.getMaxCostPerRun()) {
                LOGGER.infof("[DREAM] Cost ceiling ($%.4f >= $%.2f) reached for user='%s' " +
                        "after %d calls", estimatedCostAccumulated, config.getMaxCostPerRun(), userId, llmCallsMade);
                break;
            }

            // 2. Build content: JSON array of entries
            String content = buildEntriesJson(groupEntries);

            // 3. Call LLM (isolated — failure skips this group only)
            SummarizationService.SummarizationResult llmResult;
            try {
                llmResult = summarizationService.summarizeWithUsage(
                        content, config.getSummarizationPrompt(),
                        config.getLlmProvider(), config.getLlmModel());
            } catch (Exception e) {
                LOGGER.warnf("[DREAM] LLM call failed for user='%s', group='%s': %s. " +
                        "Preserving original entries.", userId, group.getKey(), e.getMessage());
                llmCallsMade++;
                continue;
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

            // 6. Cap at target
            if (consolidated.size() > config.getSummarizeTargetEntries()) {
                consolidated = consolidated.subList(0, config.getSummarizeTargetEntries());
            }

            // 7. SAFETY: Insert new entries FIRST
            try {
                Visibility mergedVisibility = mostRestrictiveVisibility(groupEntries);
                String sourceAgent = groupEntries.getFirst().sourceAgentId();
                Instant earliestCreated = groupEntries.stream()
                        .map(UserMemoryEntry::createdAt)
                        .filter(Objects::nonNull)
                        .min(Instant::compareTo).orElse(Instant.now());

                for (var entry : consolidated) {
                    userMemoryStore.upsert(new UserMemoryEntry(
                            null, userId, entry.key(), entry.value(),
                            groupEntries.getFirst().category(),
                            mergedVisibility, sourceAgent, List.of(),
                            "dream-consolidation", false, 0,
                            earliestCreated, Instant.now()));
                }
            } catch (Exception e) {
                LOGGER.warnf("[DREAM] Failed to insert consolidated entries for " +
                        "user='%s', group='%s': %s. Originals preserved.",
                        userId, group.getKey(), e.getMessage());
                continue; // Insert failed → don't delete anything
            }

            // 8. Delete originals (only after successful insert)
            for (var original : groupEntries) {
                try {
                    userMemoryStore.deleteEntry(original.id());
                } catch (Exception e) {
                    LOGGER.warnf("[DREAM] Failed to delete original entry '%s': %s. " +
                            "Duplicate may remain until next dream cycle.",
                            original.id(), e.getMessage());
                }
            }

            int reduced = groupEntries.size() - consolidated.size();
            totalConsolidated += reduced;
            entriesSummarizedCounter.increment(reduced);
        }

        return totalConsolidated;
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
     */
    private Map<String, List<UserMemoryEntry>> buildGroups(
                                                           List<UserMemoryEntry> entries,
                                                           AgentConfiguration.DreamConfig config) {

        if ("all".equals(config.getSummarizeGroupBy())) {
            // Single group
            return Map.of("all", new ArrayList<>(entries));
        }

        // Default: group by category
        Map<String, List<UserMemoryEntry>> byCategory = entries.stream()
                .collect(Collectors.groupingBy(UserMemoryEntry::category));

        if (!config.isPreserveAgentProvenance()) {
            return byCategory;
        }

        // Sub-group by agent within each category
        Map<String, List<UserMemoryEntry>> result = new LinkedHashMap<>();
        for (var catGroup : byCategory.entrySet()) {
            catGroup.getValue().stream()
                    .collect(Collectors.groupingBy(e -> e.sourceAgentId() != null ? e.sourceAgentId() : "unknown"))
                    .forEach((agentId, agentEntries) -> result.put(catGroup.getKey() + ":" + agentId, agentEntries));
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
                    .map(m -> new ConsolidatedEntry(m.get("key"), m.get("value")))
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

    /**
     * Result of a dream consolidation cycle.
     */
    public record DreamResult(String userId, int entriesPruned, int contradictionsFound, int entriesSummarized, long durationMs, String error) {

        public boolean isSuccess() {
            return error == null;
        }
    }
}
