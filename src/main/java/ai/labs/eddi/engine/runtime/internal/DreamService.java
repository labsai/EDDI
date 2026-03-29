package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

/**
 * Background dream consolidation service for persistent user memories. Handles
 * three maintenance tasks:
 * <ol>
 * <li><b>Prune stale</b> — remove entries not accessed in N days (zero LLM
 * cost)</li>
 * <li><b>Detect contradictions</b> — find conflicting entries (future:
 * LLM-driven)</li>
 * <li><b>Summarize interactions</b> — compress related entries (future:
 * LLM-driven)</li>
 * </ol>
 *
 * <p>
 * This service operates per-user and is invoked by the schedule system when an
 * agent has {@code dream.enabled=true} in its
 * {@link AgentConfiguration.UserMemoryConfig}.
 *
 * <p>
 * Cost ceiling: {@code maxCostPerRun} checked between batches. Round-robin:
 * processes users ordered by oldest {@code updatedAt}.
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
public class DreamService {

    private static final Logger LOGGER = Logger.getLogger(DreamService.class);

    private final IUserMemoryStore userMemoryStore;
    private final MeterRegistry meterRegistry;

    private Counter usersProcessedCounter;
    private Counter entriesPrunedCounter;
    private Counter contradictionsFoundCounter;
    private Timer dreamDurationTimer;

    @Inject
    public DreamService(IUserMemoryStore userMemoryStore, MeterRegistry meterRegistry) {
        this.userMemoryStore = userMemoryStore;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void initMetrics() {
        usersProcessedCounter = meterRegistry.counter("dream.users.processed");
        entriesPrunedCounter = meterRegistry.counter("dream.entries.pruned");
        contradictionsFoundCounter = meterRegistry.counter("dream.contradictions.found");
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

            // 2. Detect contradictions (future: LLM-driven)
            if (dreamConfig.isDetectContradictions()) {
                // Use remaining entries after pruning for contradiction detection
                List<UserMemoryEntry> remainingEntries = pruned > 0 ? userMemoryStore.getAllEntries(userId) : allEntries;
                contradictions = detectContradictions(userId, remainingEntries);
            }

            // 3. Summarize interactions (future: LLM-driven)
            if (dreamConfig.isSummarizeInteractions()) {
                summarized = summarizeInteractions(userId);
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
     * Summarize related interactions. V1: No-op placeholder. V2 (future): Use
     * SummarizationService to compress multiple related facts.
     */
    private int summarizeInteractions(String userId) {
        // Future: LLM-driven summarization of related entries
        return 0;
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
