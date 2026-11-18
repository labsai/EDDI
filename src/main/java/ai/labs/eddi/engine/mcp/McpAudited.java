/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import jakarta.enterprise.util.Nonbinding;
import jakarta.interceptor.InterceptorBinding;

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
 * Every {@code @Tool} method is either annotated with this, or classified in
 * {@code McpToolAuditCoverageTest} as read-only, as already audited by its own
 * mechanism, or as a REST-backed tool the filter records — so a new mutating
 * tool cannot be added without a decision about its audit trail.
 */
@InterceptorBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface McpAudited {

    /**
     * Index of the tool argument that names a person (a user id), which the ledger
     * records as its pseudonym; {@code -1} when the tool acts on no person. The
     * other arguments are never recorded.
     */
    @Nonbinding
    int subject() default -1;
}
