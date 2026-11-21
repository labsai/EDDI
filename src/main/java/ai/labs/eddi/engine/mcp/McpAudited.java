/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an MCP tool that changes state without going through the REST API, so
 * that {@code AdminActionAuditFilter} never sees it:
 * {@link McpAuditInterceptor} writes the admin-action ledger entry (actor,
 * tool, target, outcome) that the filter writes for the REST equivalent.
 * <p>
 * This is a <em>marker</em>, not the interceptor binding: the binding is the
 * class-level {@link McpAuditBinding}, exactly as {@link McpErrorResults} binds
 * {@link McpErrorResultInterceptor} — the mechanism already in use for every
 * tool class — and the interceptor audits the methods that carry this marker.
 * {@code McpToolAuditCoverageTest} fails for a class that has a marked method
 * but not the binding.
 * <p>
 * Every {@code @Tool} method is either marked with this, or classified in
 * {@code McpToolAuditCoverageTest} as read-only, as already audited by its own
 * mechanism, or as a REST-backed tool the filter records — so a new mutating
 * tool cannot be added without a decision about its audit trail.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface McpAudited {

    /**
     * Index of the tool argument that names a person (a user id), which the ledger
     * records as its pseudonym; {@code -1} when the tool acts on no person. The
     * other arguments are never recorded.
     */
    int subject() default -1;

    /**
     * Indices of the tool arguments that identify the target resource — an agent,
     * group, schedule, conversation or rating id. They are recorded as they are
     * (resource ids, not personal data), after {@code /mcp/<tool>}, the way the
     * REST filter records the request path. A user id never belongs here: that is
     * {@link #subject()}, which is pseudonymised.
     */
    int[] target() default {};
}
