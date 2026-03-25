package ai.labs.eddi.engine.schedule.model;

import java.time.Instant;
import java.util.Map;

/**
 * Configuration for a scheduled Agent trigger.
 * <p>
 * Supports cron schedules, heartbeat intervals, and one-shot triggers:
 * <ul>
 * <li>{@code CRON} — wall-clock aligned, e.g. "0 9 * * MON-FRI"</li>
 * <li>{@code HEARTBEAT} — interval-based, drift-proof, persistent
 * conversation</li>
 * </ul>
 * <p>
 * State machine: PENDING → CLAIMED → EXECUTING → COMPLETED | FAILED →
 * DEAD_LETTERED
 *
 * @author ginccc
 * @since 6.0.0
 */
public class ScheduleConfiguration {

    // --- Enums ---

    public enum FireStatus {
        PENDING, CLAIMED, EXECUTING, COMPLETED, FAILED, DEAD_LETTERED
    }

    public enum TriggerType {
        /** Wall-clock aligned cron expression. Default conversation strategy: "new". */
        CRON,
        /**
         * Fixed-interval heartbeat. Default conversation strategy: "persistent".
         * After fire, nextFire = lastFired + interval (drift-proof).
         */
        HEARTBEAT
    }

    // -- Identity --
    private String id;
    private String name;

    // -- Type --
    private TriggerType triggerType = TriggerType.CRON;

    // -- Target --
    private String agentId;
    private int agentVersion; // 0 = latest deployed
    private String environment; // "production" | "production" | "test"
    private String tenantId;

    // -- Timing --
    private String cronExpression; // 5-field standard cron (CRON type)
    private Long heartbeatIntervalSeconds; // heartbeat interval in seconds (HEARTBEAT type)
    private String oneTimeAt; // ISO-8601 instant (null for recurring)
    private String timeZone; // IANA, e.g. "Europe/Vienna" (default: "UTC")

    // -- Trigger --
    private String message; // message text sent to the agent
    private String userId; // user identity (default: "system:scheduler")
    private String conversationStrategy; // "new" | "persistent"
    private String persistentConversationId; // used when strategy = "persistent"

    // -- State --
    private boolean enabled = true;
    private Instant nextFire;
    private Instant lastFired;
    private FireStatus fireStatus = FireStatus.PENDING;
    private String claimedBy;
    private Instant claimedAt;
    private String fireId; // idempotency key: scheduleId + fireTime
    private int failCount;
    private Instant nextRetryAt;

    // -- Security --
    private double maxCostPerFire = -1.0; // -1 = unlimited
    private boolean allowSelfScheduling;
    private String createdBy;

    // -- Metadata --
    private Map<String, Object> metadata;
    private Instant createdAt;
    private Instant updatedAt;

    // -- Computed (read-only, not persisted) --
    private transient String cronDescription;

    public ScheduleConfiguration() {
    }

    // --- Getters & Setters ---

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public TriggerType getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(TriggerType triggerType) {
        this.triggerType = triggerType;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public int getAgentVersion() {
        return agentVersion;
    }

    public void setAgentVersion(int agentVersion) {
        this.agentVersion = agentVersion;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getCronExpression() {
        return cronExpression;
    }

    public void setCronExpression(String cronExpression) {
        this.cronExpression = cronExpression;
    }

    public Long getHeartbeatIntervalSeconds() {
        return heartbeatIntervalSeconds;
    }

    public void setHeartbeatIntervalSeconds(Long heartbeatIntervalSeconds) {
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
    }

    public String getOneTimeAt() {
        return oneTimeAt;
    }

    public void setOneTimeAt(String oneTimeAt) {
        this.oneTimeAt = oneTimeAt;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getConversationStrategy() {
        return conversationStrategy;
    }

    public void setConversationStrategy(String conversationStrategy) {
        this.conversationStrategy = conversationStrategy;
    }

    public String getPersistentConversationId() {
        return persistentConversationId;
    }

    public void setPersistentConversationId(String persistentConversationId) {
        this.persistentConversationId = persistentConversationId;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getNextFire() {
        return nextFire;
    }

    public void setNextFire(Instant nextFire) {
        this.nextFire = nextFire;
    }

    public Instant getLastFired() {
        return lastFired;
    }

    public void setLastFired(Instant lastFired) {
        this.lastFired = lastFired;
    }

    public FireStatus getFireStatus() {
        return fireStatus;
    }

    public void setFireStatus(FireStatus fireStatus) {
        this.fireStatus = fireStatus;
    }

    public String getClaimedBy() {
        return claimedBy;
    }

    public void setClaimedBy(String claimedBy) {
        this.claimedBy = claimedBy;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public void setClaimedAt(Instant claimedAt) {
        this.claimedAt = claimedAt;
    }

    public String getFireId() {
        return fireId;
    }

    public void setFireId(String fireId) {
        this.fireId = fireId;
    }

    public int getFailCount() {
        return failCount;
    }

    public void setFailCount(int failCount) {
        this.failCount = failCount;
    }

    public Instant getNextRetryAt() {
        return nextRetryAt;
    }

    public void setNextRetryAt(Instant nextRetryAt) {
        this.nextRetryAt = nextRetryAt;
    }

    public double getMaxCostPerFire() {
        return maxCostPerFire;
    }

    public void setMaxCostPerFire(double maxCostPerFire) {
        this.maxCostPerFire = maxCostPerFire;
    }

    public boolean isAllowSelfScheduling() {
        return allowSelfScheduling;
    }

    public void setAllowSelfScheduling(boolean allowSelfScheduling) {
        this.allowSelfScheduling = allowSelfScheduling;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getCronDescription() {
        return cronDescription;
    }

    public void setCronDescription(String cronDescription) {
        this.cronDescription = cronDescription;
    }
}
