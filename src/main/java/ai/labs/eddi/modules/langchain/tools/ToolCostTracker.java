package ai.labs.eddi.modules.langchain.tools;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Tracks API costs for tool executions.
 * Phase 4: Monitors and limits costs per tool and per conversation with metrics.
 */
@ApplicationScoped
public class ToolCostTracker {
    private static final Logger LOGGER = Logger.getLogger(ToolCostTracker.class);

    /** Maximum number of conversation cost entries before eviction */
    static final int MAX_CONVERSATION_ENTRIES = 10_000;

    @Inject
    MeterRegistry meterRegistry;

    // Cost per tool call (in cents or credits)
    private static final Map<String, Double> TOOL_COSTS = Map.of(
        "websearch", 0.001,      // $0.001 per search
        "weather", 0.0005,       // $0.0005 per weather call
        "calculator", 0.0,       // Free
        "datetime", 0.0,         // Free
        "dataformatter", 0.0,    // Free
        "webscraper", 0.002,     // $0.002 per scrape
        "textsummarizer", 0.0,   // Free (local)
        "pdfreader", 0.001       // $0.001 per PDF
    );

    private final Map<String, ToolCostMetrics> toolCosts = new ConcurrentHashMap<>();
    private final Map<String, ConversationCostMetrics> conversationCosts = new ConcurrentHashMap<>();
    private final DoubleAdder totalCost = new DoubleAdder();

    // Metrics
    private Counter toolCallCounter;

    @PostConstruct
    public void init() {
        this.toolCallCounter = meterRegistry.counter("eddi.tool.calls.total");

        // Register gauge for total cost
        meterRegistry.gauge("eddi.tool.costs.total", totalCost, DoubleAdder::sum);

        LOGGER.info("Tool cost tracker initialized with metrics");
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
        private final String conversationId;
        private final AtomicInteger toolCallCount = new AtomicInteger(0);
        private final DoubleAdder totalCost = new DoubleAdder();
        private final Map<String, Integer> toolUsage = new ConcurrentHashMap<>();

        public ConversationCostMetrics(String conversationId) {
            this.conversationId = conversationId;
        }

        public void addToolCost(String toolName, double cost) {
            toolCallCount.incrementAndGet();
            totalCost.add(cost);
            toolUsage.merge(toolName, 1, Integer::sum);
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
     * Track cost for a tool call
     */
    public double trackToolCall(String toolName, String conversationId) {
        double cost = TOOL_COSTS.getOrDefault(toolName, 0.0);

        // Track per-tool costs
        toolCosts.computeIfAbsent(toolName, ToolCostMetrics::new)
                 .addCost(cost);

        // Track per-conversation costs
        conversationCosts.computeIfAbsent(conversationId, ConversationCostMetrics::new)
                         .addToolCost(toolName, cost);

        // Evict oldest entries if map exceeds max size
        evictIfNeeded();

        // Track total cost
        totalCost.add(cost);

        // Record metrics
        toolCallCounter.increment();
        meterRegistry.counter("eddi.tool.calls", "tool", toolName).increment();

        if (cost > 0) {
            meterRegistry.counter("eddi.tool.costs", "tool", toolName).increment(cost);
            LOGGER.debug(String.format("Tool '%s' cost: $%.4f", toolName, cost));
        }

        return cost;
    }

    /**
     * Get cost for a specific tool
     */
    public ToolCostMetrics getToolCosts(String toolName) {
        return toolCosts.get(toolName);
    }

    /**
     * Get cost for a conversation
     */
    public ConversationCostMetrics getConversationCosts(String conversationId) {
        return conversationCosts.get(conversationId);
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

        boolean withinBudget = metrics.getTotalCost() <= maxBudget;

        if (!withinBudget) {
            // Record budget exceeded event
            meterRegistry.counter("eddi.tool.budget.exceeded").increment();

            LOGGER.warn(String.format(
                "Conversation %s exceeded budget: $%.4f > $%.4f",
                conversationId, metrics.getTotalCost(), maxBudget
            ));
        }

        return withinBudget;
    }

    /**
     * Evict oldest conversation cost entries if the map exceeds the maximum size.
     * Removes approximately 10% of entries when the limit is hit.
     */
    void evictIfNeeded() {
        if (conversationCosts.size() > MAX_CONVERSATION_ENTRIES) {
            int toRemove = conversationCosts.size() - (int) (MAX_CONVERSATION_ENTRIES * 0.9);
            var iterator = conversationCosts.keySet().iterator();
            int removed = 0;
            while (iterator.hasNext() && removed < toRemove) {
                iterator.next();
                iterator.remove();
                removed++;
            }
            LOGGER.info("Evicted " + removed + " conversation cost entries (size was " +
                    (conversationCosts.size() + removed) + ")");
        }
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
            sb.append(String.format("  - %s: %d calls, $%.4f total, $%.4f avg\n",
                metrics.toolName,
                metrics.getCallCount(),
                metrics.getTotalCost(),
                metrics.getAverageCost()
            ));
        });

        return sb.toString();
    }
}

