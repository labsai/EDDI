/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.audit;

import ai.labs.eddi.engine.hitl.audit.HitlDecisionUnit.UnitKind;
import ai.labs.eddi.engine.hitl.review.ApprovalCategories;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a decision writer hands {@link HitlApprovalAudit#write}: the decision's
 * context, its legacy fields and its units. Built fluently by the four writers
 * (regular decision and cancel, group decision and cancel) — never persisted as
 * such; the stored form is the audit entry's {@code output} map.
 *
 * @since 6.7.0
 */
public final class HitlDecisionRecord {

    /** {@code surface} of a regular conversation's decision. */
    public static final String SURFACE_REGULAR = "regular";
    /** {@code surface} of a group conversation's decision. */
    public static final String SURFACE_GROUP = "group";
    /** {@code verdict} of a cancellation. */
    public static final String VERDICT_CANCELLED = "CANCELLED";

    /** One unit as a writer knows it; the context is added on write. */
    public record Unit(UnitKind kind, String unitId, DecisionOutcome outcome, ApprovalCategories.ApprovalCategory category, String reasonCode,
            String toolName, String argsDigest, EditSummary edit) {
    }

    String surface = SURFACE_REGULAR;
    String pauseType;
    String conversationId;
    String agentId;
    Integer agentVersion;
    String groupId;
    String userId;
    String environment;
    Instant pausedAt;
    Instant decidedAt = Instant.now();
    String verdict;
    String decidedBy;
    HitlChannel declaredChannel;
    String timeoutPolicy;
    ApprovalCategories.ApprovalCategory category;
    String reasonCode;
    String note;
    final Map<String, Object> legacy = new LinkedHashMap<>();
    final List<Unit> units = new ArrayList<>();

    public HitlDecisionRecord surface(String surface) {
        this.surface = surface;
        return this;
    }

    public HitlDecisionRecord pauseType(String pauseType) {
        this.pauseType = pauseType;
        return this;
    }

    public HitlDecisionRecord conversation(String conversationId, String userId, String environment) {
        this.conversationId = conversationId;
        this.userId = userId;
        this.environment = environment;
        return this;
    }

    public HitlDecisionRecord agent(String agentId, Integer agentVersion) {
        this.agentId = agentId;
        this.agentVersion = agentVersion;
        return this;
    }

    public HitlDecisionRecord group(String groupId) {
        this.groupId = groupId;
        return this;
    }

    public HitlDecisionRecord pausedAt(Instant pausedAt) {
        this.pausedAt = pausedAt;
        return this;
    }

    public HitlDecisionRecord decidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt != null ? decidedAt : Instant.now();
        return this;
    }

    /**
     * The legacy verdict string ({@code APPROVED}, {@code REJECTED},
     * {@code CANCELLED}).
     */
    public HitlDecisionRecord verdict(String verdict) {
        this.verdict = verdict;
        return this;
    }

    public HitlDecisionRecord decidedBy(String decidedBy) {
        this.decidedBy = decidedBy;
        return this;
    }

    /** The channel the REST layer declared; prefixes of decidedBy win over it. */
    public HitlDecisionRecord channel(HitlChannel declaredChannel) {
        this.declaredChannel = declaredChannel;
        return this;
    }

    public HitlDecisionRecord timeoutPolicy(String timeoutPolicy) {
        this.timeoutPolicy = timeoutPolicy;
        return this;
    }

    /** The category of the pause as a whole. */
    public HitlDecisionRecord category(ApprovalCategories.ApprovalCategory category) {
        this.category = category;
        return this;
    }

    public HitlDecisionRecord reasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
        return this;
    }

    public HitlDecisionRecord note(String note) {
        this.note = note;
        return this;
    }

    /** A field of the pre-6.7 entry shape, kept verbatim for existing readers. */
    public HitlDecisionRecord legacy(String key, Object value) {
        if (value != null) {
            legacy.put(key, value);
        }
        return this;
    }

    public HitlDecisionRecord unit(Unit unit) {
        if (unit != null) {
            units.add(unit);
        }
        return this;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public List<Unit> getUnits() {
        return List.copyOf(units);
    }
}
