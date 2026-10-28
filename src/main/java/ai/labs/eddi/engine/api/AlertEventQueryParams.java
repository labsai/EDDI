/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api;

import jakarta.ws.rs.QueryParam;

/**
 * Filters of the alert event list and the bulk acknowledgement. All optional.
 */
public class AlertEventQueryParams {

    /**
     * Events about this agent; required of non-administrators, who must hold EDIT
     * on it.
     */
    @QueryParam("agentId")
    public String agentId;

    @QueryParam("ruleId")
    public String ruleId;

    /** {@code RATING}, {@code CONVERSATION_ERROR} or {@code OUTPUT_MATCH}. */
    @QueryParam("source")
    public String source;

    /**
     * {@code false}: the inbox (not yet acknowledged); {@code true}: acknowledged
     * ones.
     */
    @QueryParam("acknowledged")
    public Boolean acknowledged;

    /**
     * {@code PENDING}, {@code DELIVERED}, {@code FAILED}, {@code INBOX_ONLY},
     * {@code DIGEST_PENDING} or {@code DIGESTED}.
     */
    @QueryParam("state")
    public String state;

    @QueryParam("conversationId")
    public String conversationId;

    /** Fired at or after this ISO-8601 instant or date (UTC). */
    @QueryParam("from")
    public String from;

    /** Fired before this ISO-8601 instant, or on or before this date (UTC). */
    @QueryParam("to")
    public String to;

    public static AlertEventQueryParams ofAgent(String agentId) {
        AlertEventQueryParams params = new AlertEventQueryParams();
        params.agentId = agentId;
        return params;
    }
}
