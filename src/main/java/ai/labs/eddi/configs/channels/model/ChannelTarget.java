/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.ArrayList;
import java.util.List;

/**
 * A single addressable target within a channel integration. Maps trigger
 * keywords to an agent or group.
 * <p>
 * Users address a target by typing {@code triggerKeyword: message} in the
 * channel. If no trigger matches, the channel's default target handles the
 * message.
 *
 * @since 6.1.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChannelTarget {

    /**
     * Target type — either a single agent or a multi-agent group discussion.
     */
    public enum TargetType {
        AGENT, GROUP
    }

    private String name;
    private List<String> triggers;
    private TargetType type;
    private String targetId;
    /**
     * The email approval gate of an email target (email plan v3 §8.1). Ordered by
     * strictness: each value does everything the one before it does.
     */
    public enum ApprovalPolicy {
        /** Only the agent's own {@code toolApprovals} (the default). */
        NONE("none"),
        /** Every tool call not in {@code readOnlyTools} waits for approval. */
        ACTIONS("actions"),
        /** As ACTIONS, and a sender's first mail to the target waits before it runs. */
        FIRST_CONTACT("firstContact"),
        /** As ACTIONS, and every mail waits before the agent runs. */
        EVERY_TURN("everyTurn");

        private final String json;

        ApprovalPolicy(String json) {
            this.json = json;
        }

        @JsonValue
        public String json() {
            return json;
        }

        /**
         * Lenient read: the JSON name or the constant name, case-insensitive; null for
         * null. Anything else is refused, so a typo cannot silently mean "none".
         */
        @JsonCreator
        public static ApprovalPolicy parse(String value) {
            if (value == null) {
                return null;
            }
            String trimmed = value.trim();
            for (ApprovalPolicy policy : values()) {
                if (policy.json.equalsIgnoreCase(trimmed) || policy.name().equalsIgnoreCase(trimmed)) {
                    return policy;
                }
            }
            throw new IllegalArgumentException("Unknown approvalPolicy '" + value + "'; use none, actions, firstContact or everyTurn");
        }

        /** Whether tool calls of a mail turn go through the email gate. */
        public boolean gatesTools() {
            return this != NONE;
        }

        /** Whether this policy may pause a mail before the agent runs. */
        public boolean pausesBeforeRun() {
            return this == FIRST_CONTACT || this == EVERY_TURN;
        }
    }

    private boolean observeMode;
    private ObserveConfig observeConfig;
    private ApprovalPolicy approvalPolicy;
    private List<String> readOnlyTools;
    private String rejectedReply;

    public ChannelTarget() {
        this.triggers = new ArrayList<>();
        this.type = TargetType.AGENT;
    }

    /**
     * Display name shown in help messages (e.g., "Architect", "Review Panel").
     */
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /**
     * Exact trigger keywords (case-insensitive). The user types one of these
     * followed by a colon to address this target: {@code architect: question}.
     */
    public List<String> getTriggers() {
        return triggers != null ? new ArrayList<>(triggers) : null;
    }

    public void setTriggers(List<String> triggers) {
        this.triggers = triggers;
    }

    /**
     * Whether this target addresses a single agent or a group discussion.
     */
    public TargetType getType() {
        return type;
    }

    public void setType(TargetType type) {
        this.type = type;
    }

    /**
     * The agent ID or group ID this target routes to, depending on {@link #type}.
     */
    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    /**
     * If {@code true}, this target passively observes all channel messages and
     * selectively responds based on {@link #observeConfig} filters. Saving is
     * refused unless the target is of type {@code AGENT} (a group cannot be an
     * observer), is not the integration's default target, and the integration is
     * not an {@code email} or {@code webhook} one; a missing {@code observeConfig}
     * is defaulted.
     * <p>
     * An observer is never addressable: {@code ChannelTargetRouter} excludes it
     * both from trigger matching and from the default target, so the only route to
     * it is the observe path, under the cooldown and daily caps {@code ObserveGate}
     * enforces.
     */
    public boolean isObserveMode() {
        return observeMode;
    }

    public void setObserveMode(boolean observeMode) {
        this.observeMode = observeMode;
    }

    /**
     * Configuration for passive observation — only meaningful when
     * {@link #observeMode} is {@code true}, where a {@code null} value is replaced
     * by the {@link ObserveConfig} defaults on save. Leave {@code null} otherwise.
     */
    public ObserveConfig getObserveConfig() {
        return observeConfig;
    }

    public void setObserveConfig(ObserveConfig observeConfig) {
        this.observeConfig = observeConfig;
    }

    /**
     * Email integrations only: the email approval gate of this target (email plan
     * v3 §8.1). {@code null} means {@link ApprovalPolicy#NONE} — a configuration
     * stored before the field existed behaves as it did. Refused on other channel
     * types and on GROUP targets (a group turn auto-rejects tool approvals).
     */
    public ApprovalPolicy getApprovalPolicy() {
        return approvalPolicy;
    }

    public void setApprovalPolicy(ApprovalPolicy approvalPolicy) {
        this.approvalPolicy = approvalPolicy;
    }

    /** {@link #getApprovalPolicy()}, with {@code null} read as NONE. */
    @JsonIgnore
    public ApprovalPolicy effectiveApprovalPolicy() {
        return approvalPolicy == null ? ApprovalPolicy.NONE : approvalPolicy;
    }

    /**
     * Email integrations only: tool-approval patterns (the {@code toolApprovals}
     * pattern language) for the calls a mail turn of this target may make without
     * the email gate pausing it. Read-only means nothing changes when it runs; EDDI
     * cannot tell, so the admin says so. Never un-gates a call the agent's own
     * {@code toolApprovals} gate.
     */
    public List<String> getReadOnlyTools() {
        return readOnlyTools != null ? new ArrayList<>(readOnlyTools) : null;
    }

    public void setReadOnlyTools(List<String> readOnlyTools) {
        this.readOnlyTools = readOnlyTools;
    }

    /**
     * Email integrations only: what the sender is told when an approver rejects a
     * mail that waited before the agent ran. {@code null}: "Your request was not
     * approved." in conversation mode, nothing in trigger mode (which never
     * replies).
     */
    public String getRejectedReply() {
        return rejectedReply;
    }

    public void setRejectedReply(String rejectedReply) {
        this.rejectedReply = rejectedReply;
    }
}
