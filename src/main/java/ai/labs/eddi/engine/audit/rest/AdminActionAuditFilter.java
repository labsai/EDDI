/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit.rest;

import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Writes one audit-ledger record per <em>administrative</em> REST request:
 * every {@code POST}, {@code PUT}, {@code PATCH} and {@code DELETE} outside the
 * conversational data plane, whatever its outcome.
 * <p>
 * <b>Why.</b> The compliance pages said "every API call is audited". The ledger
 * recorded conversation turns, HITL decisions and the four GDPR operations —
 * and nothing else: who changed an agent's prompt, deployed it, replaced a
 * vault secret, imported a backup or erased a user (the GDPR record carried no
 * actor) was not on any record. This filter is the record of those actions.
 * <p>
 * <b>What is audited</b> is decided by exclusion, so a new administrative
 * endpoint is covered without anyone remembering to add it: every mutating
 * request is recorded except those under {@link #DATA_PLANE_SEGMENTS} — the
 * chat and conversation APIs (already audited per turn), the OpenAI-compatible
 * and A2A surfaces, OAuth and channel callbacks, and the UI shells. Reads are
 * not recorded.
 * <p>
 * <b>What a record holds</b>: the caller's principal (or {@code anonymous}),
 * the HTTP method, the request path, the resource method that served it, and
 * the response status — so a call the endpoint refused (a {@code @RolesAllowed}
 * 403, a 404, a 409) is on record as well as a successful one. A request an
 * HTTP path policy rejects (401/403) never reaches resource matching, so it is
 * not recorded here. Never the request body, which carries prompts, secrets and
 * configuration, and never the query string. A path parameter that names a
 * person ({@code userId} and the like) is replaced by the ledger's pseudonym
 * for them, so erasing user X does not leave "DELETE /admin/gdpr/X" in an
 * immutable record. Records carry no conversation, so they take no chain
 * position; they are HMAC-signed like every other entry, retrieved by
 * agent-less queries such as the GDPR export of the actor, and kept —
 * pseudonymised, not redacted — when the actor is erased.
 * <p>
 * <b>MCP.</b> The MCP tools that act through the REST API (agent, resource,
 * group, schedule and channel administration) reach this filter on the loopback
 * call, carrying the MCP caller's identity. {@code delete_user_data} and
 * {@code export_user_data} call the GDPR service directly and are recorded by
 * its compliance event; the MCP user-memory tools are not recorded.
 * <p>
 * Switched by {@code eddi.audit.admin-actions.enabled} (default {@code true}).
 */
@Provider
public class AdminActionAuditFilter implements ContainerResponseFilter {

    private static final Logger LOGGER = Logger.getLogger(AdminActionAuditFilter.class);

    /** Task type of an administrative-action record. */
    static final String TASK_TYPE = "admin";

    /** Methods that change state. */
    static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    /**
     * First path segments of the conversational data plane, which is not
     * administrative and is not recorded here: the conversation and chat APIs
     * ({@code agents}, {@code userconversationstore}, {@code chat}), the
     * OpenAI-compatible {@code v1} and {@code a2a} surfaces, OAuth and channel
     * callbacks ({@code connections}, {@code integrations}), the MCP transport and
     * the UI shells.
     */
    static final Set<String> DATA_PLANE_SEGMENTS = Set.of("agents", "userconversationstore", "chat", "v1", "a2a", "connections", "integrations",
            "mcp", "manage", "welcome", "workforce", "q");

    private final Instance<AuditLedgerService> ledger;
    private final Instance<SecurityIdentity> identity;
    private final boolean enabled;

    @Context
    ResourceInfo resourceInfo;

    @Inject
    public AdminActionAuditFilter(Instance<AuditLedgerService> ledger, Instance<SecurityIdentity> identity,
            @ConfigProperty(name = "eddi.audit.admin-actions.enabled", defaultValue = "true") boolean enabled) {
        this.ledger = ledger;
        this.identity = identity;
        this.enabled = enabled;
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        if (!enabled || !MUTATING_METHODS.contains(request.getMethod())) {
            return;
        }
        try {
            String path = request.getUriInfo().getPath();
            if (!isAdministrative(path)) {
                return;
            }
            Method resourceMethod = resourceInfo != null ? resourceInfo.getResourceMethod() : null;
            if (resourceMethod == null) {
                return; // no endpoint matched — a scanner's 404, not an action
            }
            if (!ledger.isResolvable()) {
                return;
            }
            AuditLedgerService auditLedger = ledger.get();
            if (!auditLedger.isEnabled()) {
                return;
            }
            auditLedger.submit(buildEntry(auditLedger, request.getMethod(), path, request.getUriInfo().getPathParameters(),
                    resourceMethod.getDeclaringClass().getSimpleName() + "#" + resourceMethod.getName(), response.getStatus(), actor()));
        } catch (Exception e) {
            // Recording an action must never change its outcome.
            LOGGER.warnf("Could not record administrative action %s %s in the audit ledger: %s", request.getMethod(),
                    request.getUriInfo().getPath(), e.toString());
        }
    }

    /**
     * Whether a request path is administrative — anything whose first segment is
     * not in {@link #DATA_PLANE_SEGMENTS}.
     */
    static boolean isAdministrative(String path) {
        if (path == null) {
            return false;
        }
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        if (trimmed.isEmpty()) {
            return false;
        }
        int slash = trimmed.indexOf('/');
        String first = (slash < 0 ? trimmed : trimmed.substring(0, slash)).toLowerCase(Locale.ROOT);
        return !DATA_PLANE_SEGMENTS.contains(first);
    }

    /**
     * Whether a path parameter names a person, so its value must be pseudonymised
     * before it is written into the ledger.
     */
    static boolean namesAPerson(String parameterName) {
        String name = parameterName.toLowerCase(Locale.ROOT);
        return name.contains("user") || name.contains("principal") || name.contains("subject") || name.contains("email");
    }

    static AuditEntry buildEntry(AuditLedgerService auditLedger, String method, String path, MultivaluedMap<String, String> pathParameters,
                                 String resource, int status, String actor) {
        String recordedPath = path;
        if (pathParameters != null) {
            Set<String> people = new HashSet<>();
            for (var parameter : pathParameters.entrySet()) {
                if (namesAPerson(parameter.getKey())) {
                    parameter.getValue().stream().filter(v -> v != null && !v.isEmpty()).forEach(people::add);
                }
            }
            if (!people.isEmpty()) {
                // Whole path segments only, and never the first one (the resource root,
                // never a parameter): a user called "admin" must not rewrite the "/admin/"
                // prefix, nor a one-letter id every letter of the path.
                String[] segments = path.split("/", -1);
                int firstSegment = path.startsWith("/") ? 1 : 0;
                for (int i = firstSegment + 1; i < segments.length; i++) {
                    if (people.contains(segments[i])) {
                        segments[i] = auditLedger.pseudonymOf(segments[i]);
                    }
                }
                recordedPath = String.join("/", segments);
            }
        }
        var input = new LinkedHashMap<String, Object>();
        input.put("method", method);
        input.put("path", recordedPath);
        input.put("resource", resource);
        Map<String, Object> output = Map.of("status", status);
        return new AuditEntry(UUID.randomUUID().toString(), null, null, null, actor, null, 0, AuditLedgerService.ADMIN_ACTION_TASK_ID, TASK_TYPE,
                0, 0, input, output, null, null, List.of("ADMIN_" + method), 0.0, Instant.now(), null, null);
    }

    private String actor() {
        try {
            if (identity.isResolvable()) {
                SecurityIdentity current = identity.get();
                if (current != null && !current.isAnonymous() && current.getPrincipal() != null
                        && current.getPrincipal().getName() != null && !current.getPrincipal().getName().isBlank()) {
                    return current.getPrincipal().getName();
                }
            }
        } catch (RuntimeException e) {
            // No request-scoped identity here (e.g. a filter invoked outside a request).
        }
        return "anonymous";
    }
}
