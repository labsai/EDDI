/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Decides who may store a configuration that contains {@code ${caller:token}}.
 * <p>
 * <b>Why:</b> {@link CallerIdentityResolver} releases the bearer token of
 * whoever is <em>chatting</em> to the caller's origin or to EDDI's own address.
 * Nothing at that point knows who <em>wrote</em> the config. An editor could
 * therefore save an httpcall to
 * {@code http://127.0.0.1:7070/administration/...} carrying {@code Bearer
 * ${caller:token}}, fire it on {@code CONVERSATION_START}, and have the next
 * admin who opens the agent run an admin-only endpoint with their own token — a
 * confused deputy. The decision has to be taken where the author is known,
 * which is the write: REST create, update, patch and duplicate, and import.
 * <p>
 * The check is a text scan of the serialized config, deliberately: the
 * reference may sit in an httpcall header, an MCP server's {@code apiKey}, or
 * an LLM config's MCP server list, and a field-by-field check would have to be
 * kept in step with every one of them. {@code ${caller:userId}} is not a
 * credential and is not affected.
 * <p>
 * Inactive when {@code authorization.enabled=false}: then every caller is an
 * administrator anyway ({@link OwnershipValidator#isAdmin}).
 */
@ApplicationScoped
public class CallerTokenAuthorPolicy {

    /** The roles allowed to author {@code ${caller:token}}; any one suffices. */
    public static final String AUTHOR_ROLES_PROPERTY = "eddi.caller-identity.author-roles";

    private final SecurityIdentity identity;
    private final IJsonSerialization jsonSerialization;
    private final boolean authorizationEnabled;
    private final List<String> authorRoles;

    @Inject
    public CallerTokenAuthorPolicy(SecurityIdentity identity, IJsonSerialization jsonSerialization,
            @ConfigProperty(name = "authorization.enabled", defaultValue = "false") boolean authorizationEnabled,
            @ConfigProperty(name = AUTHOR_ROLES_PROPERTY, defaultValue = "eddi-admin") List<String> authorRoles) {
        this.identity = identity;
        this.jsonSerialization = jsonSerialization;
        this.authorizationEnabled = authorizationEnabled;
        this.authorRoles = authorRoles == null ? List.of() : authorRoles.stream().map(String::trim).filter(r -> !r.isEmpty()).toList();
    }

    /**
     * Refuses the write when {@code configuration} contains {@code ${caller:token}}
     * and the caller holds none of the author roles.
     *
     * @param configuration
     *            the document about to be stored; null is allowed and passes
     * @param kind
     *            what is being stored, for the message ("httpcalls", "mcpcalls")
     * @throws ForbiddenException
     *             403 with a JSON {@code error} naming the property that widens it
     */
    public void requireMayAuthor(Object configuration, String kind) {
        if (!authorizationEnabled) {
            return;
        }
        // The role check runs first and unconditionally: whether the caller may author
        // is decided by who they are, never skipped because of what the document says.
        if (callerHoldsAuthorRole() || configuration == null || !containsCallerToken(configuration)) {
            return;
        }
        String message = "This " + kind + " configuration contains ${caller:token}, which sends the bearer token of whoever chats "
                + "with the agent. Only " + String.join(" or ", authorRoles) + " may save it (" + AUTHOR_ROLES_PROPERTY + "), because "
                + "a call written by someone else would run with that user's privileges. Use ${caller:userId}, a ${vault:...} "
                + "reference or a connection instead, or ask an administrator to save it.";
        throw new ForbiddenException(message, Response.status(Response.Status.FORBIDDEN)
                .entity(Map.of("error", message))
                .type(MediaType.APPLICATION_JSON)
                .build());
    }

    boolean containsCallerToken(Object configuration) {
        if (configuration instanceof String text) {
            return CallerIdentityResolver.containsTokenReference(text);
        }
        try {
            return CallerIdentityResolver.containsTokenReference(jsonSerialization.serialize(configuration));
        } catch (IOException e) {
            // Not serializable means not inspectable: refuse rather than wave through.
            return true;
        }
    }

    private boolean callerHoldsAuthorRole() {
        try {
            return identity != null && !identity.isAnonymous() && authorRoles.stream().anyMatch(identity::hasRole);
        } catch (RuntimeException outsideARequest) {
            // No request context, so no author to vouch for the reference.
            return false;
        }
    }
}
