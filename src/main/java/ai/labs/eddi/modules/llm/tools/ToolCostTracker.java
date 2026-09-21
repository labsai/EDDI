/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Tracks API costs for tool executions. Phase 4: Monitors and limits costs per
 * tool and per conversation with metrics.
 */
@ApplicationScoped
public class ToolCostTracker {
    private static final Logger LOGGER = Logger.getLogger(ToolCostTracker.class);

    /** Maximum number of conversation cost entries before eviction */
    static final int MAX_CONVERSATION_ENTRIES = 10_000;

    @Inject
    MeterRegistry meterRegistry;

    /**
     * Default price in USD per call, keyed on the <em>canonical</em> tool slug —
     * the same tokens as {@code builtInToolsWhitelist}. Looked up with
     * {@link ToolInvocation#canonicalName()}, never with the dispatch name: no
     * built-in declares {@code @Tool(name = …)}, so the dispatch name is a method
     * name ({@code searchWeb}) that shares no key with this table and would price
     * every built-in at $0.00.
     */
    static final Map<String, Double> DEFAULT_TOOL_PRICES = Map.of("websearch", 0.001, // $0.001 per search
            "weather", 0.0005, // $0.0005 per weather call
            "calculator", 0.0, // Free
            "datetime", 0.0, // Free
            "dataformatter", 0.0, // Free
            "webscraper", 0.002, // $0.002 per scrape
            "textsummarizer", 0.0, // Free (local)
            "pdfreader", 0.001 // $0.001 per PDF
    );

    /**
     * Gauge of the USD accrued by every priced tool call since start.
     * <p>
     * It was {@code eddi.tool.costs.total}, which Prometheus renders as
     * {@code eddi_tool_costs_total} — exactly the name it gives the tagged counter
     * {@code eddi.tool.costs{tool}} below, since counters gain a {@code _total}
     * suffix. Two meters under one name with different tag keys is illegal, so the
     * first priced call threw "Prometheus requires that all meters with the same
     * name have the same set of tag keys" — and because that happened inside the
     * tool wrapper, every priced tool (websearch, weather, webscraper, pdfreader,
     * anything given a {@code toolPricing} override) returned the exception text to
     * the model instead of its result, and budget enforcement never saw a cost.
     */
    static final String ACCRUED_COST_GAUGE = "eddi.tool.costs.accrued";

    private final Map<String, ToolCostMetrics> toolCosts = new ConcurrentHashMap<>();
    /**
     * The same costs keyed by configuration slug ({@code websearch}), aggregating
     * every dispatch name priced under it. The REST lookup accepts either name:
     * {@code /cache/ttl} and {@code toolPricing} speak slugs, so a slug lookup that
     * found nothing looked like a tool that had never run.
     */
    private final Map<String, ToolCostMetrics> canonicalToolCosts = new ConcurrentHashMap<>();
    private final Map<String, ConversationCostMetrics> conversationCosts = new ConcurrentHashMap<>();
    private final DoubleAdder totalCost = new DoubleAdder();

    /**
     * Warns once per process, not once per call, when a meter cannot be recorded.
     */
    private final AtomicBoolean meterFailureWarned = new AtomicBoolean();

    @PostConstruct
    public void init() {
        meterRegistry.gauge(ACCRUED_COST_GAUGE, totalCost, DoubleAdder::sum);

        LOGGER.info("Tool cost tracker initialized with metrics");
    }

    /**
     * Records a meter without letting a metrics-backend failure escape.
     * <p>
     * Cost accounting is the part budget enforcement depends on and is done before
     * any meter is touched; a meter that cannot be registered or incremented must
     * cost observability, never the tool result or the accounting.
     */
    private void recordMeter(Runnable recording) {
        try {
            recording.run();
        } catch (RuntimeException e) {
            if (meterFailureWarned.compareAndSet(false, true)) {
                LOGGER.warnf("Tool cost metric could not be recorded (further failures are not logged): %s", sanitize(e.getMessage()));
            }
        }
    }

    /**
     * Cost metrics for a specific tool
     */
    public static class ToolCostMetrics {
        private final String toolName;
        private final AtomicInteger callCount = new AtomicInteger(0);
        private final DoubleAdder totalCost = new DoubleAdder();

        public ToolCostMetrics(String toolName) {
            this.toolName = toolName;
        }

        public void addCost(double cost) {
            callCount.incrementAndGet();
            totalCost.add(cost);
        }

        public int getCallCount() {
            return callCount.get();
        }

        public double getTotalCost() {
            return totalCost.sum();
        }

        public double getAverageCost() {
            int count = callCount.get();
            return count > 0 ? totalCost.sum() / count : 0;
        }
    }

    /**
     * Cost metrics for a conversation
     */
    public static class ConversationCostMetrics {
        private final AtomicInteger toolCallCount = new AtomicInteger(0);
        private final DoubleAdder totalCost = new DoubleAdder();
        private final Map<String, Integer> toolUsage = new ConcurrentHashMap<>();

        /**
         * When this conversation was last written to or read. Drives eviction order —
         * see {@link ToolCostTracker#evictIfNeeded()}. {@code volatile} rather than
         * atomic because it is only ever overwritten with "now", so a lost update
         * between two racing touches costs nanoseconds of accuracy.
         */
        private volatile long lastTouchedNanos = System.nanoTime();

        public ConversationCostMetrics(String conversationId) {
            // conversationId is the map key in the parent; no need to store it here
        }

        void touch() {
            lastTouchedNanos = System.nanoTime();
        }

        long lastTouchedNanos() {
            return lastTouchedNanos;
        }

        public void addToolCost(String toolName, double cost) {
            touch();
            toolCallCount.incrementAndGet();
            totalCost.add(cost);
            toolUsage.merge(toolName, 1, (a, b) -> a + b);
        }

        public int getToolCallCount() {
            return toolCallCount.get();
        }

        public double getTotalCost() {
            return totalCost.sum();
        }

        public Map<String, Integer> getToolUsage() {
            return Map.copyOf(toolUsage);
        }
    }

    /**
     * Track cost for a tool call whose configured slug is unknown — the tool is
     * priced under its own name.
     *
     * <p>
     * Retained for callers outside the agent dispatch loop (and for tools where
     * dispatch name and slug are genuinely the same string). The live path uses
     * {@link #trackToolCall(ToolInvocation, String)}.
     * </p>
     */
    public double trackToolCall(String toolName, String conversationId) {
        return trackToolCall(ToolInvocation.of(toolName), conversationId);
    }

    /**
     * Track cost for a tool call.
     *
     * <p>
     * The price is resolved from {@link ToolInvocation#canonicalName()} (or the
     * per-call override), while every accounting key — the per-tool map, the
     * per-conversation usage breakdown and the {@code tool} metric tag — stays on
     * {@link ToolInvocation#dispatchName()}. Splitting them this way keeps the
     * {@code eddi.tool.calls}/{@code eddi.tool.costs} tag vocabulary identical to
     * every other {@code tool}-tagged meter in this package (which all report the
     * dispatched method name), so no dashboard or alert has to be rewritten, while
     * still charging the right amount.
     * </p>
     *
     * @param invocation
     *            the call being priced
     * @param conversationId
     *            conversation to bill
     * @return the cost charged, in USD
     */
    public double trackToolCall(ToolInvocation invocation, String conversationId) {
        String toolName = invocation.dispatchName();
        Double priceOverride = invocation.priceOverride();

        // An operator-supplied price is clamped at zero: a negative double would
        // *credit* the conversation and let maxBudgetPerConversation be evaded
        // outright.
        double cost = priceOverride != null
                ? Math.max(0.0, priceOverride)
                : DEFAULT_TOOL_PRICES.getOrDefault(invocation.canonicalName(), 0.0);

        // Track per-tool costs
        toolCosts.computeIfAbsent(toolName, ToolCostMetrics::new).addCost(cost);
        if (!toolName.equals(invocation.canonicalName())) {
            canonicalToolCosts.computeIfAbsent(invocation.canonicalName(), ToolCostMetrics::new).addCost(cost);
        }

        // Track per-conversation costs
        conversationCosts.computeIfAbsent(conversationId, ConversationCostMetrics::new).addToolCost(toolName, cost);

        // Evict oldest entries if map exceeds max size
        evictIfNeeded();

        // Track total cost
        totalCost.add(cost);

        // Record per-tool metrics (aggregate available via PromQL sum)
        recordMeter(() -> meterRegistry.counter("eddi.tool.calls", "tool", toolName).increment());

        if (cost > 0) {
            recordMeter(() -> meterRegistry.counter("eddi.tool.costs", "tool", toolName).increment(cost));
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debugf("Tool '%s' cost: $%.4f", sanitize(toolName), cost);
            }
        }

        return cost;
    }

    /**
     * Get cost for a specific tool, by dispatch name ({@code searchWeb}) or by
     * configuration slug ({@code websearch}). A slug aggregates every dispatch name
     * priced under it; a dispatch name wins when a string is both.
     */
    public ToolCostMetrics getToolCosts(String toolName) {
        ToolCostMetrics byDispatchName = toolCosts.get(toolName);
        return byDispatchName != null ? byDispatchName : canonicalToolCosts.get(toolName);
    }

    /**
     * Get cost for a conversation. Counts as activity: a conversation whose spend
     * is still being inspected must not be picked as an eviction victim.
     */
    public ConversationCostMetrics getConversationCosts(String conversationId) {
        ConversationCostMetrics metrics = conversationCosts.get(conversationId);
        if (metrics != null) {
            metrics.touch();
        }
        return metrics;
    }

    /**
     * Get total cost across all tools and conversations
     */
    public double getTotalCost() {
        return totalCost.sum();
    }

    /**
     * Check if conversation is within budget
     */
    public boolean isWithinBudget(String conversationId, double maxBudget) {
        ConversationCostMetrics metrics = conversationCosts.get(conversationId);
        if (metrics == null) {
            return true;
        }
        metrics.touch();

        boolean withinBudget = metrics.getTotalCost() <= maxBudget;

        if (!withinBudget) {
            // Record budget exceeded event
            recordMeter(() -> meterRegistry.counter("eddi.tool.budget.exceeded").increment());

            LOGGER.warn(String.format("Conversation %s exceeded budget: $%.4f > $%.4f", sanitize(conversationId), metrics.getTotalCost(), maxBudget));
        }

        return withinBudget;
    }

    /**
     * Evict the <em>least recently touched</em> conversation cost entries once the
     * map exceeds {@link #MAX_CONVERSATION_ENTRIES}, down to 90% of the cap.
     * <p>
     * Order matters, and the previous implementation had none: it iterated
     * {@code ConcurrentHashMap.keySet()}, whose order is a function of hash bucket
     * layout, and removed whatever came first. Above the cap it could therefore
     * drop a conversation that was mid-turn — and dropping an entry here is not
     * merely a lost metric, it resets that conversation's accumulated spend to $0,
     * so {@link #isWithinBudget} would let it start spending its whole budget
     * again. Ordering by last touch makes the victims the conversations that have
     * actually gone quiet.
     * <p>
     * {@link #getConversationCosts} and {@link #isWithinBudget} count as touches,
     * so a conversation that is only being budget-checked still looks alive.
     */
    void evictIfNeeded() {
        if (conversationCosts.size() <= MAX_CONVERSATION_ENTRIES) {
            return;
        }
        int toRemove = conversationCosts.size() - (int) (MAX_CONVERSATION_ENTRIES * 0.9);
        var oldestFirst = conversationCosts.entrySet().stream()
                .sorted(Comparator.comparingLong(
                        (Map.Entry<String, ConversationCostMetrics> entry) -> entry.getValue().lastTouchedNanos()))
                .limit(toRemove)
                .map(Map.Entry::getKey)
                .toList();

        int removed = 0;
        for (String conversationId : oldestFirst) {
            if (conversationCosts.remove(conversationId) != null) {
                removed++;
            }
        }
        LOGGER.info("Evicted " + removed + " least-recently-used conversation cost entries (size is now " + conversationCosts.size() + ")");
    }

    /**
     * Reset costs for a conversation
     */
    public void resetConversation(String conversationId) {
        conversationCosts.remove(conversationId);
    }

    /**
     * Reset all cost tracking
     */
    public void resetAll() {
        toolCosts.clear();
        canonicalToolCosts.clear();
        conversationCosts.clear();
        totalCost.reset();
        LOGGER.info("Reset all tool cost tracking");
    }

    /**
     * Get cost summary
     */
    public String getCostSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Tool Cost Summary:\n");
        sb.append(String.format("Total Cost: $%.4f\n", totalCost.sum()));
        sb.append("Per-Tool Costs:\n");

        toolCosts.values().forEach(metrics -> {
            sb.append(String.format("  - %s: %d calls, $%.4f total, $%.4f avg\n", metrics.toolName, metrics.getCallCount(), metrics.getTotalCost(),
                    metrics.getAverageCost()));
        });

        return sb.toString();
    }
}
