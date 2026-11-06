/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit.rest;

import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Writes one administrative-action record to the audit ledger: the caller's
 * principal (or {@code anonymous}), the method, the path, the endpoint that
 * served it and the outcome. {@link AdminActionAuditFilter} records REST
 * requests through it; the MCP tools that act on a person without going through
 * REST — {@code delete_user_data}, {@code export_user_data} — record their
 * calls through it too, so an erasure has an actor on record whichever surface
 * it came through.
 * <p>
 * Never the request body and never the query string. A value that names a
 * person is replaced by the ledger's keyed pseudonym for them, so erasing user
 * X does not leave X in an immutable record. Records carry no conversation, so
 * they take no chain position; they are HMAC-signed like every other entry,
 * retrieved by agent-less queries such as the GDPR export of the actor, and
 * kept — pseudonymised, not redacted — when the actor is erased.
 * <p>
 * Recording never throws: an action's outcome must not depend on whether it
 * could be written down. Switched by {@code eddi.audit.admin-actions.enabled}
 * (default {@code true}), for both surfaces.
 */
@ApplicationScoped
public class AdminActionRecorder {

    private static final Logger LOGGER = Logger.getLogger(AdminActionRecorder.class);

    /** Task type of an administrative-action record. */
    static final String TASK_TYPE = "admin";

    /** The method recorded for an MCP tool call, which has no HTTP method. */
    public static final String MCP_METHOD = "MCP";

    private final Instance<AuditLedgerService> ledger;
    private final Instance<SecurityIdentity> identity;
    private final boolean enabled;

    @Inject
    public AdminActionRecorder(Instance<AuditLedgerService> ledger, Instance<SecurityIdentity> identity,
            @ConfigProperty(name = "eddi.audit.admin-actions.enabled", defaultValue = "true") boolean enabled) {
        this.ledger = ledger;
        this.identity = identity;
        this.enabled = enabled;
    }

    /** Whether administrative actions are recorded at all. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Records a REST request. A path parameter that {@linkplain #namesAPerson names
     * a person} is replaced in the path by its pseudonym.
     *
     * @param method
     *            the HTTP method
     * @param path
     *            the request path
     * @param pathParameters
     *            the matched path parameters, by name
     * @param resource
     *            the endpoint that served it, as {@code Class#method}
     * @param status
     *            the HTTP status of the response
     */
    public void record(String method, String path, Map<String, List<String>> pathParameters, String resource, int status) {
        submit(method, resource, status, auditLedger -> pseudonymise(auditLedger, path, pathParameters));
    }

    /**
     * Records an MCP tool call that acts on a person, as {@value #MCP_METHOD}
     * {@code /mcp/<tool>/<pseudonym of the subject>}. The subject is pseudonymised
     * whole rather than matched against path segments, so an id containing a
     * {@code /} cannot leave part of itself in the record.
     *
     * @param tool
     *            the MCP tool name, such as {@code delete_user_data}
     * @param subjectUserId
     *            the user the call is about; {@code null} or blank when the call
     *            was refused before naming one
     * @param resource
     *            the tool method, as {@code Class#method}
     * @param status
     *            the outcome as the HTTP status the REST surface answers for it
     */
    public void recordToolCall(String tool, String subjectUserId, String resource, int status) {
        submit(MCP_METHOD, resource, status, auditLedger -> "/mcp/" + tool
                + (subjectUserId == null || subjectUserId.isBlank() ? "" : "/" + auditLedger.pseudonymOf(subjectUserId)));
    }

    private void submit(String method, String resource, int status, Function<AuditLedgerService, String> recordedPath) {
        if (!enabled) {
            return;
        }
        try {
            if (!ledger.isResolvable()) {
                return;
            }
            AuditLedgerService auditLedger = ledger.get();
            if (!auditLedger.isEnabled()) {
                return;
            }
            auditLedger.submit(buildEntry(method, recordedPath.apply(auditLedger), resource, status, actor()));
        } catch (Exception e) {
            // Recording an action must never change its outcome. Neither the path
            // (it can hold the raw id this record pseudonymises) nor the caller's
            // text is logged raw: line breaks are stripped so a request cannot forge
            // log records (CWE-117).
            LOGGER.warnf("Could not record administrative action %s %s in the audit ledger: %s", sanitize(method), sanitize(resource),
                    sanitize(e.toString()));
        }
    }

    /**
     * Whether a path parameter names a person, so its value must be pseudonymised
     * before it is written into the ledger.
     */
    static boolean namesAPerson(String parameterName) {
        String name = parameterName.toLowerCase(Locale.ROOT);
        return name.contains("user") || name.contains("principal") || name.contains("subject") || name.contains("email");
    }

    static String pseudonymise(AuditLedgerService auditLedger, String path, Map<String, List<String>> pathParameters) {
        if (pathParameters == null) {
            return path;
        }
        Set<String> people = new HashSet<>();
        for (var parameter : pathParameters.entrySet()) {
            if (namesAPerson(parameter.getKey())) {
                parameter.getValue().stream().filter(v -> v != null && !v.isEmpty()).forEach(people::add);
            }
        }
        if (people.isEmpty()) {
            return path;
        }
        // An id that contains a "/" (arriving as %2F) spans several segments of the
        // decoded path, so no single segment equals it. Replace it whole, on segment
        // boundaries and after the first segment, before the per-segment pass.
        for (String person : people) {
            if (person.indexOf('/') >= 0) {
                path = replaceSegmentSpan(auditLedger, path, person);
            }
        }
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
        return String.join("/", segments);
    }

    /**
     * Replaces each occurrence of a slash-containing {@code person} that starts at
     * a segment boundary after the first segment and ends at one.
     */
    private static String replaceSegmentSpan(AuditLedgerService auditLedger, String path, String person) {
        int firstSegmentEnd = path.indexOf('/', path.startsWith("/") ? 1 : 0);
        if (firstSegmentEnd < 0) {
            return path;
        }
        StringBuilder out = new StringBuilder();
        int from = 0;
        int at;
        while ((at = path.indexOf(person, from)) >= 0) {
            int end = at + person.length();
            boolean startsOnBoundary = at > firstSegmentEnd && path.charAt(at - 1) == '/';
            boolean endsOnBoundary = end == path.length() || path.charAt(end) == '/';
            if (startsOnBoundary && endsOnBoundary) {
                out.append(path, from, at).append(auditLedger.pseudonymOf(person));
                from = end;
            } else {
                out.append(path, from, at + 1);
                from = at + 1;
            }
        }
        return out.append(path, from, path.length()).toString();
    }

    static AuditEntry buildEntry(String method, String recordedPath, String resource, int status, String actor) {
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
            // No request-scoped identity here (e.g. a call outside a request).
        }
        return "anonymous";
    }
}
