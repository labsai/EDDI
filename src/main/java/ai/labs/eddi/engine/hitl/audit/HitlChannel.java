/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.audit;

/**
 * Where a HITL decision was made — the {@code channel} of the
 * {@code hitl.approval} audit contract. Values are only ever added.
 * <p>
 * Derived from the {@code decidedBy} prefix where the server knows it
 * ({@code slack:}, {@code mcp:}, {@code system:*}); otherwise from the channel
 * the REST layer declared. {@link #MANAGER} comes from the
 * {@code X-EDDI-Client: manager} request header, which is a client's claim: an
 * analytics hint, never used for authorization.
 *
 * @since 6.7.0
 */
public enum HitlChannel {
    REST, MANAGER, MCP, SLACK, TIMEOUT, SYSTEM;

    /** Request header a client sends to say which client it is. */
    public static final String CLIENT_HEADER = "X-EDDI-Client";

    /**
     * The channel of a decision.
     *
     * @param decidedBy
     *            the recorded decider
     * @param declared
     *            the channel the REST layer declared, or null
     */
    public static HitlChannel of(String decidedBy, HitlChannel declared) {
        if (decidedBy != null) {
            if (decidedBy.startsWith("slack:")) {
                return SLACK;
            }
            if (decidedBy.startsWith("mcp:")) {
                return MCP;
            }
            if (decidedBy.equals("system:timeout") || decidedBy.startsWith("system:timeout")) {
                return TIMEOUT;
            }
            if (decidedBy.startsWith("system:")) {
                return SYSTEM;
            }
        }
        return declared != null ? declared : REST;
    }

    /** The channel a REST request declared through {@link #CLIENT_HEADER}. */
    public static HitlChannel fromClientHeader(String headerValue) {
        return headerValue != null && "manager".equalsIgnoreCase(headerValue.trim()) ? MANAGER : REST;
    }
}
