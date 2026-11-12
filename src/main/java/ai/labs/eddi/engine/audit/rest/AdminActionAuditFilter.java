/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit.rest;

import io.quarkus.security.Authenticated;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

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
 * and A2A surfaces, OAuth and channel callbacks, and the UI shells. An
 * {@linkplain #adminOnly admin-only} endpoint under those roots — one whose
 * {@code @RolesAllowed} does not admit {@code eddi-user}, such as the
 * conversation state reset and the managed-conversation mappings — is recorded
 * all the same: the path says where an endpoint lives, its roles say whether it
 * is administrative. Reads are not recorded.
 * <p>
 * <b>What a record holds</b> — written by {@link AdminActionRecorder}: the
 * caller's principal (or {@code anonymous}), the HTTP method, the request path,
 * the resource method that served it, and the response status — so a call the
 * endpoint refused (a {@code @RolesAllowed} 403, a 404, a 409) is on record as
 * well as a successful one. A request an HTTP path policy rejects (401/403)
 * never reaches resource matching, so it is not recorded here. Never the
 * request body, which carries prompts, secrets and configuration, and never the
 * query string. A path parameter that names a person ({@code userId} and the
 * like) is replaced by the ledger's pseudonym for them, so erasing user X does
 * not leave "DELETE /admin/gdpr/X" in an immutable record.
 * <p>
 * <b>MCP.</b> The MCP tools that act through the REST API (agent, resource,
 * group, schedule and channel administration) reach this filter on the loopback
 * call, carrying the MCP caller's identity. {@code delete_user_data} and
 * {@code export_user_data} call the GDPR service directly and record their
 * calls through {@link AdminActionRecorder} themselves; the MCP user-memory
 * tools are not recorded.
 * <p>
 * Switched by {@code eddi.audit.admin-actions.enabled} (default {@code true}).
 */
@Provider
public class AdminActionAuditFilter implements ContainerResponseFilter {

    private static final Logger LOGGER = Logger.getLogger(AdminActionAuditFilter.class);

    /** Methods that change state. */
    static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    /**
     * First path segments of the conversational data plane, which is not
     * administrative and is not recorded here: the conversation and chat APIs
     * ({@code agents}, {@code userconversationstore}, {@code chat}), the
     * OpenAI-compatible {@code v1} and {@code a2a} surfaces, OAuth and channel
     * callbacks ({@code connections}, {@code integrations}), the MCP transport and
     * the UI shells. An {@linkplain #adminOnly admin-only} endpoint under one of
     * them is recorded regardless.
     */
    static final Set<String> DATA_PLANE_SEGMENTS = Set.of("agents", "userconversationstore", "chat", "v1", "a2a", "connections", "integrations",
            "mcp", "manage", "welcome", "workforce", "q");

    /**
     * The conversing role; an endpoint that does not admit it is administrative.
     */
    static final String USER_ROLE = "eddi-user";

    /**
     * {@link #adminOnly} per resource method, which never changes at runtime:
     * bounded by the number of endpoints, and it keeps the reflection off the chat
     * path, which passes through here on every turn.
     */
    private static final Map<Method, Boolean> ADMIN_ONLY = new ConcurrentHashMap<>();

    private final AdminActionRecorder recorder;

    @Context
    ResourceInfo resourceInfo;

    @Inject
    public AdminActionAuditFilter(AdminActionRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        if (!recorder.isEnabled() || !MUTATING_METHODS.contains(request.getMethod())) {
            return;
        }
        String resource = "unknown";
        try {
            Method resourceMethod = resourceInfo != null ? resourceInfo.getResourceMethod() : null;
            if (resourceMethod == null) {
                return; // no endpoint matched — a scanner's 404, not an action
            }
            resource = resourceMethod.getDeclaringClass().getSimpleName() + "#" + resourceMethod.getName();
            String path = request.getUriInfo().getPath();
            if (!isAdministrative(path) && !adminOnly(resourceMethod)) {
                return;
            }
            recorder.record(request.getMethod(), path, request.getUriInfo().getPathParameters(), resource, response.getStatus());
        } catch (Exception e) {
            // Recording an action must never change its outcome. The recorder never
            // throws; this guards the request-side lookups above.
            // Neither the request path nor the exception message is logged: both can
            // hold the raw user id the record pseudonymises (CWE-532). The endpoint and
            // the exception type are enough to diagnose with, and the endpoint name is
            // sanitized so line breaks cannot forge log records (CWE-117).
            LOGGER.warnf("Could not record administrative action %s %s in the audit ledger: %s", sanitize(request.getMethod()),
                    sanitize(resource), e.getClass().getName());
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
     * Whether an endpoint is admin-only: its effective {@code @RolesAllowed} does
     * not admit {@value #USER_ROLE}. Such an endpoint is administrative wherever
     * its path lives, and the role is declared on it already, so nothing new has to
     * be remembered when one is added under a data-plane root.
     * <p>
     * The nearest declaration wins, as Quarkus applies it: a method's own
     * {@code @RolesAllowed}, {@code @PermitAll} or {@code @Authenticated} before
     * its class's. Each is looked for on the implementation first and then on the
     * interface that declares the method — {@link ResourceInfo} hands over the
     * implementation method, and Java does not inherit annotations from an
     * interface, which is where this codebase declares its roles.
     */
    static boolean adminOnly(Method resourceMethod) {
        return ADMIN_ONLY.computeIfAbsent(resourceMethod, AdminActionAuditFilter::resolveAdminOnly);
    }

    private static boolean resolveAdminOnly(Method resourceMethod) {
        for (AnnotatedElement scope : securityScopes(resourceMethod)) {
            RolesAllowed roles = scope.getAnnotation(RolesAllowed.class);
            if (roles != null) {
                return !Arrays.asList(roles.value()).contains(USER_ROLE);
            }
            if (scope.isAnnotationPresent(PermitAll.class) || scope.isAnnotationPresent(Authenticated.class)) {
                return false;
            }
        }
        return false;
    }

    /**
     * Where a security annotation for a resource method can sit, nearest first: the
     * method, its same-signature declaration on each interface, the implementation
     * class, then each of those interfaces.
     */
    private static List<AnnotatedElement> securityScopes(Method resourceMethod) {
        List<Method> declarations = new ArrayList<>();
        declarations.add(resourceMethod);
        for (Class<?> contract : resourceMethod.getDeclaringClass().getInterfaces()) {
            try {
                declarations.add(contract.getMethod(resourceMethod.getName(), resourceMethod.getParameterTypes()));
            } catch (NoSuchMethodException e) {
                // This interface does not declare the method, so its roles do not apply to it.
            }
        }
        List<AnnotatedElement> scopes = new ArrayList<>(declarations);
        for (Method declaration : declarations) {
            scopes.add(declaration.getDeclaringClass());
        }
        return scopes;
    }
}
