/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rest;

import ai.labs.eddi.configs.IRestVersionInfo;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.ReferringVersions;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.ConversationStatus;
import ai.labs.eddi.engine.memory.rest.IRestConversationStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.utils.LogSanitizer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Refuses to delete what something else still uses.
 * <p>
 * <b>Resources.</b> A workflow version an agent lists, or a configuration (LLM,
 * behavior, httpcalls, mcpcalls, output, property, dictionary, rag, parser)
 * version a workflow step points at, is not deleted: the agent would deploy
 * against a hole or fail on its next turn. "Uses" means:
 * <ul>
 * <li>for a workflow version: an agent's <em>current</em> version lists it, or
 * an older agent version that is <em>deployed</em> does (a deployment pins
 * exact versions, so an older version still running is a user);</li>
 * <li>for a configuration version: a workflow's current version names it, or an
 * older workflow version names it that a current or deployed agent version
 * lists.</li>
 * </ul>
 * A soft delete looks at the version addressed, {@code permanent=true} at every
 * version up to it. The answer is a 409 whose body names up to
 * {@link #MAX_NAMED_USERS} users the caller may see; {@code force=true} (a
 * query parameter of every guarded delete endpoint, an argument of MCP
 * {@code delete_resource}) deletes anyway.
 * <p>
 * <b>Cost.</b> Independent of the number of agents: the reverse lookups return
 * ids only, in a constant number of indexed queries
 * ({@link IAgentStore#findAgentVersionsListing},
 * {@link IWorkflowStore#findWorkflowVersionsReferencing} — two respectively
 * four per 50 URIs — plus one read of the deployment records). Descriptors are
 * read only to name users: at most {@link #MAX_NAMED_USERS} for a caller who
 * sees everything, at most {@link #MAX_INSPECTED_USERS} otherwise.
 * <p>
 * <b>A lookup that fails refuses</b> with 503 ({@code IN_USE_CHECK_FAILED},
 * {@code Retry-After}): the delete is irreversible and the check is what stands
 * between it and an agent that stops working, so a store timeout must not turn
 * into an unguarded delete; {@code force=true} remains the explicit way past
 * it.
 * <p>
 * <b>Agents.</b> An agent with active conversations on a deployed version is
 * not deleted either — the rule {@code undeploy} has — unless the caller passes
 * {@code endAllActiveConversations=true}. Counting and refusing happen before
 * anything is touched ({@link #requireNoOpenConversations}); the conversations
 * are ended only once every check that can still refuse the delete has passed
 * ({@link #endOpenConversations}), with end reason
 * {@link IConversationService#END_REASON_AGENT_DELETED}. Conversations paused
 * for a human ({@code AWAITING_HUMAN}) are neither counted nor ended — the same
 * rule as undeploy and old-version cleanup: a forgotten approval must not block
 * a delete forever, and a human's pending decision is not destroyed as a side
 * effect; its own timeout policy still applies.
 * <p>
 * Deletes the caller makes on purpose as part of a larger delete (the agent
 * cascade removing workflows the agent alone used) run inside
 * {@link #callUnguarded}, a {@link ScopedValue} bound to the calling thread for
 * the duration of the call only.
 */
@ApplicationScoped
public class ResourceInUseGuard {

    private static final Logger LOGGER = Logger.getLogger(ResourceInUseGuard.class);

    /** How many users a refusal names. */
    public static final int MAX_NAMED_USERS = 10;

    /**
     * How many users' descriptors are read, at most, to decide which ones a caller
     * who does not see everything may see.
     */
    public static final int MAX_INSPECTED_USERS = 50;

    /** Query parameter that deletes a resource although it is in use. */
    public static final String FORCE_PARAM = "force";

    /** The undeploy endpoint's parameter, reused for deleting an agent. */
    public static final String END_CONVERSATIONS_PARAM = "endAllActiveConversations";

    public static final String CODE_IN_USE = "RESOURCE_IN_USE";
    public static final String CODE_OPEN_CONVERSATIONS = "AGENT_HAS_OPEN_CONVERSATIONS";
    public static final String CODE_CHECK_FAILED = "IN_USE_CHECK_FAILED";

    /**
     * Seconds a client should wait before repeating a delete the check could not
     * answer.
     */
    static final int RETRY_AFTER_SECONDS = 5;

    private static final int END_BATCH_SIZE = 500;

    private static final String WORKFLOW_AUTHORITY = "ai.labs.workflow";
    private static final String WORKFLOW_URI = "eddi://ai.labs.workflow/workflowstore/workflows/";

    /**
     * The authorities of everything a workflow step (or a parser step) points at.
     */
    private static final Set<String> STEP_RESOURCE_AUTHORITIES = Set.of("ai.labs.parser", "ai.labs.dictionary", "ai.labs.rules",
            "ai.labs.behavior", "ai.labs.apicalls", "ai.labs.httpcalls", "ai.labs.llm", "ai.labs.property", "ai.labs.output",
            "ai.labs.mcpcalls", "ai.labs.rag");

    private static final ScopedValue<Boolean> UNGUARDED = ScopedValue.newInstance();

    private final IAgentStore agentStore;
    private final IWorkflowStore workflowStore;
    private final IDeploymentStore deploymentStore;
    private final IDocumentDescriptorStore descriptorStore;
    private final IConversationMemoryStore conversationMemoryStore;
    private final IRestConversationStore restConversationStore;
    private final ResourceAccessGuard accessGuard;

    @Inject
    public ResourceInUseGuard(IAgentStore agentStore, IWorkflowStore workflowStore, IDeploymentStore deploymentStore,
            IDocumentDescriptorStore descriptorStore, IConversationMemoryStore conversationMemoryStore,
            IRestConversationStore restConversationStore, ResourceAccessGuard accessGuard) {
        this.agentStore = agentStore;
        this.workflowStore = workflowStore;
        this.deploymentStore = deploymentStore;
        this.descriptorStore = descriptorStore;
        this.conversationMemoryStore = conversationMemoryStore;
        this.restConversationStore = restConversationStore;
        this.accessGuard = accessGuard;
    }

    // ---------------------------------------------------------------- scope

    /**
     * Runs {@code delete} without the in-use check: for a delete that removes the
     * users itself (a cascade). Bound to this thread for the call only.
     */
    public static <T, X extends Throwable> T callUnguarded(ScopedValue.CallableOp<T, X> delete) throws X {
        return ScopedValue.where(UNGUARDED, Boolean.TRUE).call(delete);
    }

    static boolean isUnguarded() {
        return UNGUARDED.isBound() && UNGUARDED.get();
    }

    // ----------------------------------------------------------- static entry

    /**
     * What {@code RestVersionInfo.delete} calls: refuses (409) when something still
     * uses the resource and {@code force} is false. Does nothing for resource types
     * nothing points at, and where no container runs (a store built by hand in a
     * unit test).
     */
    public static void requireUnusedOrForced(String resourceUri, String id, Integer version, boolean permanent, boolean force) {
        if (force || isUnguarded() || resourceUri == null || !isGuarded(resourceUri)) {
            return;
        }
        ResourceInUseGuard guard;
        try {
            guard = CDI.current().select(ResourceInUseGuard.class).get();
        } catch (RuntimeException noContainer) {
            return;
        }
        if (guard != null) {
            guard.requireUnused(resourceUri, id, version, permanent, false);
        }
    }

    static boolean isGuarded(String resourceUri) {
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

    // ------------------------------------------------------------- resources

    /**
     * @param resourceUri
     *            the store's resource URI prefix, e.g.
     *            {@code eddi://ai.labs.llm/llmstore/llms/}
     * @param version
     *            the version addressed (for {@code permanent}, the newest)
     */
    public void requireUnused(String resourceUri, String id, Integer version, boolean permanent, boolean force) {
        if (force || isUnguarded() || version == null || version < 1) {
            return;
        }
        String authority = authorityOf(resourceUri);
        List<String> addressed = new ArrayList<>();
        for (int v = permanent ? 1 : version; v <= version; v++) {
            addressed.add(resourceUri + id + IRestVersionInfo.versionQueryParam + v);
        }
        List<User> users;
        try {
            users = WORKFLOW_AUTHORITY.equals(authority) ? usersOfWorkflow(addressed) : usersOfConfig(addressed);
        } catch (RuntimeException | IResourceStore.ResourceStoreException e) {
            LOGGER.errorf(e, "In-use check before deleting %s %s failed; refusing the delete (503)", authority, LogSanitizer.sanitize(id));
            throw checkFailed("Could not check whether this " + typeOf(authority) + " is still in use, so it was not deleted. Retry, or "
                    + "repeat the request with " + FORCE_PARAM + "=true to delete it without the check.");
        }
        if (!users.isEmpty()) {
            throw inUse(authority, id, version, users);
        }
    }

    /** One user of a resource: an agent or workflow version. */
    record User(String type, String id, Integer version) {
    }

    /**
     * Agents whose current version lists one of {@code workflowUris}, and deployed
     * agent versions that do.
     */
    private List<User> usersOfWorkflow(List<String> workflowUris) throws IResourceStore.ResourceStoreException {
        ReferringVersions agents = agentStore.findAgentVersionsListing(workflowUris);
        if (agents.live().isEmpty() && agents.historical().isEmpty()) {
            return List.of();
        }
        Set<String> deployed = deployedAgentVersions();
        List<User> users = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        addAgents(agents, deployed, users, seen);
        return users;
    }

    /**
     * Workflows whose current version names one of {@code configUris}; and, for
     * older workflow versions that name it, the current or deployed agent versions
     * that list them.
     */
    private List<User> usersOfConfig(List<String> configUris) throws IResourceStore.ResourceStoreException {
        ReferringVersions workflows = workflowStore.findWorkflowVersionsReferencing(configUris);
        List<User> users = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (IResourceStore.IResourceId workflow : workflows.live()) {
            if (seen.add("workflow:" + workflow.getId())) {
                users.add(new User("workflow", workflow.getId(), workflow.getVersion()));
            }
        }
        List<String> olderWorkflowVersions = new ArrayList<>();
        for (IResourceStore.IResourceId workflow : workflows.historical()) {
            olderWorkflowVersions.add(WORKFLOW_URI + workflow.getId() + IRestVersionInfo.versionQueryParam + workflow.getVersion());
        }
        if (!olderWorkflowVersions.isEmpty()) {
            ReferringVersions agents = agentStore.findAgentVersionsListing(olderWorkflowVersions);
            if (!agents.live().isEmpty() || !agents.historical().isEmpty()) {
                addAgents(agents, deployedAgentVersions(), users, seen);
            }
        }
        return users;
    }

    private static void addAgents(ReferringVersions agents, Set<String> deployed, List<User> users, Set<String> seen) {
        for (IResourceStore.IResourceId agent : agents.live()) {
            if (seen.add("agent:" + agent.getId())) {
                users.add(new User("agent", agent.getId(), agent.getVersion()));
            }
        }
        for (IResourceStore.IResourceId agent : agents.historical()) {
            if (deployed.contains(agent.getId() + "#" + agent.getVersion()) && seen.add("agent:" + agent.getId())) {
                users.add(new User("agent", agent.getId(), agent.getVersion()));
            }
        }
    }

    /** {@code agentId#version} of every deployment record that says deployed. */
    private Set<String> deployedAgentVersions() throws IResourceStore.ResourceStoreException {
        Set<String> deployed = new HashSet<>();
        for (DeploymentInfo info : deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)) {
            deployed.add(info.getAgentId() + "#" + info.getAgentVersion());
        }
        return deployed;
    }

    private WebApplicationException inUse(String authority, String id, Integer version, List<User> users) {
        List<Map<String, Object>> named = new ArrayList<>();
        int visible = 0;
        boolean seesEverything = accessGuard.seesEverything();
        int inspected = 0;
        for (User user : users) {
            if (!seesEverything && inspected >= MAX_INSPECTED_USERS || seesEverything && named.size() >= MAX_NAMED_USERS) {
                break;
            }
            inspected++;
            DocumentDescriptor descriptor = descriptorOf(user.id());
            if (!seesEverything && (descriptor == null || !accessGuard.canAccess(descriptor, AccessLevel.VIEW))) {
                continue;
            }
            visible++;
            if (named.size() < MAX_NAMED_USERS) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("type", user.type());
                entry.put("id", user.id());
                entry.put("name", descriptor == null ? null : descriptor.getName());
                entry.put("version", user.version());
                named.add(entry);
            }
        }
        // An administrator (or anyone, with workspaces off) is told the full count;
        // anyone else only how many of the inspected users they may see. Whether
        // there are others is a yes/no — the refusal itself already says that much.
        int usedByCount = seesEverything ? users.size() : visible;
        boolean othersNotListed = users.size() > named.size();
        String resourceType = typeOf(authority);
        String usedByWhat = named.isEmpty() ? "agents or workflows you cannot see" : usedByCount + " agent(s) or workflow(s)";
        String message = "This " + resourceType + " is used by " + usedByWhat + (othersNotListed && !named.isEmpty() ? " and others" : "")
                + " and was not deleted. Remove it from them first, or repeat the request with " + FORCE_PARAM + "=true to delete it anyway.";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        body.put("code", CODE_IN_USE);
        body.put("resource", Map.of("type", resourceType, "id", String.valueOf(id), "version", version));
        body.put("usedBy", named);
        body.put("usedByCount", usedByCount);
        body.put("othersNotListed", othersNotListed);
        body.put("override", FORCE_PARAM + "=true");
        return status(Response.Status.CONFLICT, message, body);
    }

    private DocumentDescriptor descriptorOf(String id) {
        try {
            return descriptorStore.readCurrentDescriptor(id);
        } catch (Exception e) {
            return null;
        }
    }

    private static String typeOf(String authority) {
        return authority.startsWith("ai.labs.") ? authority.substring("ai.labs.".length()) : authority;
    }

    // ---------------------------------------------------------------- agents

    /**
     * The active conversations of an agent's deployed versions, by version; empty
     * when there are none.
     */
    public record OpenConversations(Map<Integer, Long> byVersion) {
        public OpenConversations {
            byVersion = Map.copyOf(byVersion);
        }

        public boolean isEmpty() {
            return byVersion.isEmpty();
        }

        public long total() {
            return byVersion.values().stream().mapToLong(Long::longValue).sum();
        }

        static OpenConversations none() {
            return new OpenConversations(Map.of());
        }
    }

    /**
     * Counts the active conversations of the agent's deployed versions and refuses
     * (409) when there are any, unless {@code endAll}. Ends nothing: the caller
     * ends what this returns with {@link #endOpenConversations} once every other
     * check that can refuse the delete has passed.
     *
     * @return what must be ended before the delete; empty when nothing
     */
    public OpenConversations requireNoOpenConversations(String agentId, Collection<Integer> deployedVersions, boolean endAll) {
        if (isUnguarded() || deployedVersions == null || deployedVersions.isEmpty()) {
            return OpenConversations.none();
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
            LOGGER.errorf(e, "Open-conversation check before deleting agent %s failed; refusing the delete (503)", LogSanitizer.sanitize(agentId));
            throw checkFailed("Could not count the open conversations of this agent, so it was not deleted. Retry the request.");
        }
        var result = new OpenConversations(open);
        if (result.isEmpty() || endAll) {
            return result;
        }
        String message = result.total() + " active conversation(s) are open on this deployed agent and it was not deleted. End them "
                + "(POST /conversationstore/conversations/end) or repeat the request with " + END_CONVERSATIONS_PARAM
                + "=true to end them and delete the agent.";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        body.put("code", CODE_OPEN_CONVERSATIONS);
        body.put("resource", Map.of("type", "agent", "id", agentId));
        body.put("openConversations", open);
        body.put("openConversationsCount", result.total());
        body.put("override", END_CONVERSATIONS_PARAM + "=true");
        throw status(Response.Status.CONFLICT, message, body);
    }

    /**
     * Ends the active conversations counted by {@link #requireNoOpenConversations},
     * page by page ({@value #END_BATCH_SIZE} at a time), with end reason
     * {@link IConversationService#END_REASON_AGENT_DELETED}. Conversations paused
     * for a human are left as they are.
     *
     * @throws IllegalStateException
     *             when a conversation could not be ended — the caller must not
     *             delete the agent then
     */
    public void endOpenConversations(String agentId, OpenConversations open) {
        for (Integer version : open.byVersion().keySet()) {
            String after = null;
            while (true) {
                List<ConversationStatus> page = new ArrayList<>();
                String last;
                try {
                    var batch = conversationMemoryStore.loadOpenConversationActivity(agentId, version, after, END_BATCH_SIZE);
                    if (batch.isEmpty()) {
                        break;
                    }
                    for (var activity : batch) {
                        if (activity.conversationState() == ConversationState.AWAITING_HUMAN) {
                            continue;
                        }
                        var status = new ConversationStatus();
                        status.setConversationId(activity.conversationId());
                        status.setAgentId(agentId);
                        status.setAgentVersion(version);
                        status.setConversationState(activity.conversationState());
                        page.add(status);
                    }
                    last = batch.getLast().conversationId();
                } catch (IResourceStore.ResourceStoreException e) {
                    throw new IllegalStateException("Could not list the open conversations of agent " + agentId + " - not deleting it", e);
                }
                if (!page.isEmpty()) {
                    Response ended = restConversationStore.endActiveConversations(page, IConversationService.END_REASON_AGENT_DELETED);
                    if (ended != null && ended.getStatus() >= 300) {
                        throw new IllegalStateException("Could not end every open conversation of agent " + agentId + " - not deleting it");
                    }
                }
                after = last;
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private static WebApplicationException checkFailed(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        body.put("code", CODE_CHECK_FAILED);
        body.put("retryable", true);
        return new WebApplicationException(message, Response.status(Response.Status.SERVICE_UNAVAILABLE).header("Retry-After",
                RETRY_AFTER_SECONDS).entity(body).type(MediaType.APPLICATION_JSON).build());
    }

    private static WebApplicationException status(Response.Status status, String message, Map<String, Object> body) {
        return new WebApplicationException(message, Response.status(status).entity(body).type(MediaType.APPLICATION_JSON).build());
    }
}
