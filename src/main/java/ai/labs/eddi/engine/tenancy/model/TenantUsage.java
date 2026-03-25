package ai.labs.eddi.engine.tenancy.model;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Mutable usage counters for a tenant. Thread-safe — uses atomic types for
 * concurrent access.
 * <p>
 * Note: {@link #incrementApiCalls()} and {@link #incrementConversations()} are
 * individually atomic but NOT synchronized with the reset methods. Under high
 * concurrency, an increment may race with a window reset and be lost. This is
 * acceptable for approximate rate limiting in the stub phase.
 */
public class TenantUsage {

    private final String tenantId;
    private final AtomicInteger conversationsToday = new AtomicInteger(0);
    private final AtomicInteger apiCallsThisMinute = new AtomicInteger(0);
    private final DoubleAdder monthlyCostUsd = new DoubleAdder();
    private volatile Instant minuteWindowStart;
    private volatile Instant dayStart;

    public TenantUsage(String tenantId) {
        this.tenantId = tenantId;
        this.minuteWindowStart = Instant.now();
        this.dayStart = Instant.now();
    }

    public String getTenantId() {
        return tenantId;
    }

    public int getConversationsToday() {
        return conversationsToday.get();
    }

    public int getApiCallsThisMinute() {
        return apiCallsThisMinute.get();
    }

    public double getMonthlyCostUsd() {
        return monthlyCostUsd.sum();
    }

    public Instant getMinuteWindowStart() {
        return minuteWindowStart;
    }

    public Instant getDayStart() {
        return dayStart;
    }

    public void incrementConversations() {
        conversationsToday.incrementAndGet();
    }

    public void incrementApiCalls() {
        apiCallsThisMinute.incrementAndGet();
    }

    public void addCost(double cost) {
        monthlyCostUsd.add(cost);
    }

    /**
     * Reset the per-minute sliding window counter.
     */
    public synchronized void resetMinuteWindow() {
        apiCallsThisMinute.set(0);
        minuteWindowStart = Instant.now();
    }

    /**
     * Reset the daily conversation counter.
     */
    public synchronized void resetDailyCounters() {
        conversationsToday.set(0);
        dayStart = Instant.now();
    }

    /**
     * Reset all counters (admin reset).
     */
    public synchronized void resetAll() {
        conversationsToday.set(0);
        apiCallsThisMinute.set(0);
        monthlyCostUsd.reset();
        minuteWindowStart = Instant.now();
        dayStart = Instant.now();
    }

    /**
     * Snapshot of current usage for REST API responses.
     */
    public UsageSnapshot toSnapshot() {
        return new UsageSnapshot(tenantId, conversationsToday.get(), apiCallsThisMinute.get(), monthlyCostUsd.sum(), minuteWindowStart, dayStart);
    }

    /**
     * Immutable snapshot of usage at a point in time.
     */
    public record UsageSnapshot(String tenantId, int conversationsToday, int apiCallsThisMinute, double monthlyCostUsd, Instant minuteWindowStart,
            Instant dayStart) {
    }
}
