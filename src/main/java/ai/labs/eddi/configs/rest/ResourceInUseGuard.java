/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rest;

import ai.labs.eddi.configs.IRestVersionInfo;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationStatus;
import ai.labs.eddi.engine.memory.rest.IRestConversationStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.utils.LogSanitizer;
import ai.labs.eddi.utils.RestUtilities;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Refuses to delete what something else still uses.
 * <p>
 * <b>Resources.</b> A workflow an agent lists, or a configuration (LLM,
 * behavior, httpcalls, mcpcalls, output, property, dictionary, rag, parser) a
 * workflow step points at, is not deleted: the agent would deploy against a
 * hole or fail on its next turn. The answer is a 409 whose body names up to
 * {@link #MAX_NAMED_USERS} users the caller may see; {@code ?force=true}
 * deletes anyway. A {@code permanent=true} delete looks at every version, a
 * soft delete at the version addressed.
 * <p>
 * <b>Agents.</b> An agent with open conversations on a deployed version is not
 * deleted either — the guard {@code undeploy} already has — unless the caller
 * passes {@code endAllActiveConversations=true}, which ends them first. With
 * workspaces off nothing else stops one editor deleting another's agent mid
 * conversation.
 * <p>
 * Cascade deletes know that what they remove goes away together and run inside
 * {@link #bypass()}. The MCP tools have no query string and use
 * {@link #forced()} / {@link #bypass()} to pass the same decision.
 * <p>
 * The 409 body is JSON: {@code error}, {@code code} ({@code RESOURCE_IN_USE} or
 * {@code AGENT_HAS_OPEN_CONVERSATIONS}), {@code resource}, {@code usedBy}
 * (type, id, name, version), {@code usedByCount} (all users, including ones the
 * caller may not see) and {@code override} (the query parameter that proceeds).
 */
@ApplicationScoped
public class ResourceInUseGuard {

    private static final Logger LOGGER = Logger.getLogger(ResourceInUseGuard.class);

    /** How many users a refusal names. */
    public static final int MAX_NAMED_USERS = 10;

    /** Query parameter that deletes a resource although it is in use. */
    public static final String FORCE_PARAM = "force";

    /** The undeploy endpoint's parameter, reused for deleting an agent. */
    public static final String END_CONVERSATIONS_PARAM = "endAllActiveConversations";

    public static final String CODE_IN_USE = "RESOURCE_IN_USE";
    public static final String CODE_OPEN_CONVERSATIONS = "AGENT_HAS_OPEN_CONVERSATIONS";

    private static final String WORKFLOW_AUTHORITY = "ai.labs.workflow";

    /**
     * The authorities of everything a workflow step (or a parser step) points at.
     */
    private static final Set<String> STEP_RESOURCE_AUTHORITIES = Set.of("ai.labs.parser", "ai.labs.dictionary", "ai.labs.rules",
            "ai.labs.behavior", "ai.labs.apicalls", "ai.labs.httpcalls", "ai.labs.llm", "ai.labs.property", "ai.labs.output",
            "ai.labs.mcpcalls", "ai.labs.rag");

    private static final ThreadLocal<Integer> BYPASS = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> END_CONVERSATIONS = new ThreadLocal<>();

    private final IAgentStore agentStore;
    private final IWorkflowStore workflowStore;
    private final IConversationMemoryStore conversationMemoryStore;
    private final IRestConversationStore restConversationStore;
    private final ResourceAccessGuard accessGuard;

    /** The request being served; unavailable (and unused) outside one. */
    @Inject
    UriInfo requestUri;

    @Inject
    public ResourceInUseGuard(IAgentStore agentStore, IWorkflowStore workflowStore, IConversationMemoryStore conversationMemoryStore,
            IRestConversationStore restConversationStore, ResourceAccessGuard accessGuard) {
        this.agentStore = agentStore;
        this.workflowStore = workflowStore;
        this.conversationMemoryStore = conversationMemoryStore;
        this.restConversationStore = restConversationStore;
        this.accessGuard = accessGuard;
    }

    // ---------------------------------------------------------------- scopes

    /**
     * Deletes inside the scope skip the guard: the caller removes the users itself.
     */
    public static Scope bypass() {
        return open();
    }

    /** The scope an MCP tool opens when the caller asked for {@code force}. */
    public static Scope forced() {
        return open();
    }

    /**
     * The scope an MCP tool opens when the caller asked to end the open
     * conversations of the agent it deletes (the query parameter's equivalent).
     */
    public static EndScope endingConversations() {
        END_CONVERSATIONS.set(Boolean.TRUE);
        return new EndScope();
    }

    /** Closes an {@link #endingConversations()} scope. */
    public static final class EndScope implements AutoCloseable {
        @Override
        public void close() {
            END_CONVERSATIONS.remove();
        }
    }

    private static Scope open() {
        Integer depth = BYPASS.get();
        BYPASS.set(depth == null ? 1 : depth + 1);
        return new Scope();
    }

    /** Closes a {@link #bypass()} scope. */
    public static final class Scope implements AutoCloseable {
        @Override
        public void close() {
            Integer depth = BYPASS.get();
            if (depth == null || depth <= 1) {
                BYPASS.remove();
            } else {
                BYPASS.set(depth - 1);
            }
        }
    }

    // ----------------------------------------------------------- static entry

    /**
     * What {@code RestVersionInfo.delete} calls: refuses (409) when something still
     * uses the resource. Does nothing for resource types nothing points at, and
     * where no container runs (a unit test that builds the store by hand).
     */
    public static void requireUnusedOrForced(String resourceUri, String id, Integer version, boolean permanent) {
        if (BYPASS.get() != null || resourceUri == null || !isGuarded(resourceUri)) {
            return;
        }
        ResourceInUseGuard guard;
        try {
            guard = CDI.current().select(ResourceInUseGuard.class).get();
        } catch (RuntimeException noContainer) {
            return;
        }
        guard.requireUnused(resourceUri, id, version, permanent);
    }

    private static boolean isGuarded(String resourceUri) {
        String authority = authorityOf(resourceUri);
        return WORKFLOW_AUTHORITY.equals(authority) || STEP_RESOURCE_AUTHORITIES.contains(authority);
    }

    private static String authorityOf(String resourceUri) {
        int scheme = resourceUri.indexOf("://");
        if (scheme < 0) {
            return "";
        }
        int end = resourceUri.indexOf('/', scheme + 3);
        return resourceUri.substring(scheme + 3, end < 0 ? resourceUri.length() : end);
    }

    // ----------------------------------------------------------------- checks

    /**
     * @param resourceUri
     *            the store's resource URI prefix, e.g.
     *            {@code eddi://ai.labs.llm/llmstore/llms/}
     */
    public void requireUnused(String resourceUri, String id, Integer version, boolean permanent) {
        if (BYPASS.get() != null || queryFlag(FORCE_PARAM)) {
            return;
        }
        String authority = authorityOf(resourceUri);
        List<DocumentDescriptor> users;
        String userType;
        try {
            if (WORKFLOW_AUTHORITY.equals(authority)) {
                users = agentStore.getAgentDescriptorsContainingWorkflow(id, version, permanent);
                userType = "agent";
            } else {
                String uri = resourceUri + id + IRestVersionInfo.versionQueryParam + version;
                users = workflowStore.getWorkflowDescriptorsContainingResource(uri, permanent);
                userType = "workflow";
            }
        } catch (IResourceStore.ResourceStoreException | IResourceStore.ResourceNotFoundException | RuntimeException e) {
            // The lookup is a convenience for the caller, not a gate that can lock a
            // resource in place: if it fails, the delete proceeds as it always did.
            LOGGER.warnf("In-use check before deleting %s %s failed, deleting anyway: %s", authority, LogSanitizer.sanitize(id),
                    LogSanitizer.sanitize(e.getMessage()));
            return;
        }
        if (users == null || users.isEmpty()) {
            return;
        }
        throw inUse(authority, id, version, userType, users);
    }

    private WebApplicationException inUse(String authority, String id, Integer version, String userType, List<DocumentDescriptor> users) {
        List<Map<String, Object>> named = new ArrayList<>();
        for (DocumentDescriptor user : users) {
            if (named.size() >= MAX_NAMED_USERS) {
                break;
            }
            if (!accessGuard.canAccess(user, AccessLevel.VIEW)) {
                continue;
            }
            var userId = user.getResource() == null ? null : RestUtilities.extractResourceId(user.getResource());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", userType);
            entry.put("id", userId == null ? null : userId.getId());
            entry.put("name", user.getName());
            entry.put("version", userId == null ? null : userId.getVersion());
            named.add(entry);
        }
        String resourceType = authority.startsWith("ai.labs.") ? authority.substring("ai.labs.".length()) : authority;
        String message = "This " + resourceType + " is used by " + users.size() + " " + userType + (users.size() == 1 ? "" : "s")
                + " and was not deleted. Remove it from them first, or repeat the request with " + FORCE_PARAM + "=true to delete it anyway.";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        body.put("code", CODE_IN_USE);
        body.put("resource", Map.of("type", resourceType, "id", String.valueOf(id), "version", version == null ? 0 : version));
        body.put("usedBy", named);
        body.put("usedByCount", users.size());
        body.put("override", FORCE_PARAM + "=true");
        return conflict(message, body);
    }

    /**
     * Refuses to delete an agent whose deployed versions still have open
     * conversations — or ends them first when the request carries
     * {@code endAllActiveConversations=true}.
     *
     * @param deployedVersions
     *            the versions of the agent that are deployed
     */
    public void requireNoOpenConversations(String agentId, List<Integer> deployedVersions) {
        if (BYPASS.get() != null || deployedVersions == null || deployedVersions.isEmpty()) {
            return;
        }
        Map<Integer, Long> open = new LinkedHashMap<>();
        try {
            for (Integer version : deployedVersions) {
                Long count = conversationMemoryStore.getActiveConversationCount(agentId, version);
                if (count != null && count > 0) {
                    open.put(version, count);
                }
            }
        } catch (RuntimeException e) {
            LOGGER.warnf("Open-conversation check before deleting agent %s failed, deleting anyway: %s", LogSanitizer.sanitize(agentId),
                    LogSanitizer.sanitize(e.getMessage()));
            return;
        }
        if (open.isEmpty()) {
            return;
        }
        if (Boolean.TRUE.equals(END_CONVERSATIONS.get()) || queryFlag(END_CONVERSATIONS_PARAM)) {
            endConversations(agentId, open.keySet());
            return;
        }
        long total = open.values().stream().mapToLong(Long::longValue).sum();
        String message = total + " active (not ended) conversation(s) are open on this deployed agent and it was not deleted. End them "
                + "(POST /conversationstore/conversations/end) or repeat the request with " + END_CONVERSATIONS_PARAM
                + "=true to end them and delete the agent.";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        body.put("code", CODE_OPEN_CONVERSATIONS);
        body.put("resource", Map.of("type", "agent", "id", agentId));
        body.put("openConversations", open);
        body.put("openConversationsCount", total);
        body.put("override", END_CONVERSATIONS_PARAM + "=true");
        throw conflict(message, body);
    }

    private void endConversations(String agentId, Iterable<Integer> versions) {
        try {
            for (Integer version : versions) {
                List<ConversationStatus> statuses = new ArrayList<>();
                String after = null;
                while (true) {
                    var batch = conversationMemoryStore.loadOpenConversationActivity(agentId, version, after, 500);
                    if (batch.isEmpty()) {
                        break;
                    }
                    for (var activity : batch) {
                        var status = new ConversationStatus();
                        status.setConversationId(activity.conversationId());
                        status.setAgentId(agentId);
                        status.setAgentVersion(version);
                        status.setConversationState(activity.conversationState());
                        statuses.add(status);
                    }
                    after = batch.getLast().conversationId();
                }
                Response ended = restConversationStore.endActiveConversations(statuses, IConversationService.END_REASON_AGENT_VERSION_RETIRED);
                if (ended != null && ended.getStatus() >= 300) {
                    throw new IllegalStateException("Could not end every open conversation of agent " + agentId + " - not deleting it");
                }
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not end the open conversations of agent " + agentId + " - not deleting it", e);
        }
    }

    // ---------------------------------------------------------------- helpers

    private boolean queryFlag(String name) {
        try {
            if (requestUri == null) {
                return false;
            }
            List<String> values = requestUri.getQueryParameters().get(name);
            return values != null && values.stream().anyMatch(value -> "true".equalsIgnoreCase(value));
        } catch (RuntimeException outsideARequest) {
            return false;
        }
    }

    private static WebApplicationException conflict(String message, Map<String, Object> body) {
        return new WebApplicationException(message,
                Response.status(Response.Status.CONFLICT).entity(body).type(MediaType.APPLICATION_JSON).build());
    }
}
