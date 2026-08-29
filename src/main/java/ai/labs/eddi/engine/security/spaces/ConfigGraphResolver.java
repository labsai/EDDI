/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.utils.RestUtilities;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * Walks the configuration graph beneath an agent or agent group: its workflows,
 * and the rule sets, api calls, LLM configs, output sets, RAG configs, mcp
 * calls and parser dictionaries those workflows reference.
 *
 * <h3>Why sharing needs this</h3> People share <em>agents</em>. Nobody shares
 * an output set. But an agent is a thin document pointing at a dozen others, so
 * a share that stopped at the agent would grant access to a name and a list of
 * URIs the recipient could not resolve — and, because a shared agent's config
 * graph is exactly what {@code VIEW} is supposed to expose, the recipient would
 * see a broken agent rather than a shared one.
 * <p>
 * The closure is computed <b>at share time</b> and materialised as grants on
 * each descriptor, not resolved on every read. Resolving per read would put a
 * graph walk on the hot path of a listing and make the access decision depend
 * on config that may since have changed.
 *
 * <h3>Bounded on purpose</h3> A malformed or hostile config can point at
 * itself, and a deep graph could otherwise fan out without limit. Three bounds:
 * {@code visited} stops a cycle from being walked twice, the <em>result</em> is
 * capped at {@link #MAX_GRAPH_SIZE} (the queue itself is not — it drains
 * against that cap), and group nesting stops at {@link #MAX_GROUP_NESTING}. Any
 * single unreadable reference is skipped rather than failing the whole share: a
 * share that silently grants nothing is worse than one that grants slightly
 * less than the maximum.
 *
 * @author ginccc
 */
@ApplicationScoped
public class ConfigGraphResolver {

    private static final Logger LOGGER = Logger.getLogger(ConfigGraphResolver.class);

    /**
     * Upper bound on resources reachable from one root. Well above any real agent
     * (a large one reaches a few dozen), low enough that a cyclic or generated
     * config cannot turn one share into an unbounded write amplification.
     */
    static final int MAX_GRAPH_SIZE = 500;

    /**
     * How far group-of-group nesting is followed. Deeper than any group a human
     * assembles, and a hard stop for a config that nests into itself — which
     * {@code visited} also catches, belt and braces.
     */
    static final int MAX_GROUP_NESTING = 8;

    private static final String KEY_URI = "uri";
    private static final String PARSER_HOST = "ai.labs.parser";
    private static final String REGULAR_DICTIONARY_HOST = "ai.labs.parser.dictionaries.regular";

    private final IAgentStore agentStore;
    private final IWorkflowStore workflowStore;
    private final IAgentGroupStore agentGroupStore;

    @Inject
    public ConfigGraphResolver(IAgentStore agentStore, IWorkflowStore workflowStore, IAgentGroupStore agentGroupStore) {
        this.agentStore = agentStore;
        this.workflowStore = workflowStore;
        this.agentGroupStore = agentGroupStore;
    }

    /**
     * Every resource id reachable from {@code rootId}, excluding the root itself.
     * <p>
     * Reads through the stores rather than the {@code IRest*} facades on purpose:
     * this runs <em>while</em> deciding what to share, so it must see the graph as
     * it is, not as the caller's current grants already permit. The caller's right
     * to share the root is checked separately, before this is called.
     *
     * @param rootId
     *            an agent or agent-group id
     * @return referenced ids, in discovery order; empty when the root is neither
     */
    public Set<String> referencedResourceIds(String rootId) {
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();

        seedFromRoot(rootId, queue, visited, 0);

        Set<String> result = new LinkedHashSet<>();
        while (!queue.isEmpty() && result.size() < MAX_GRAPH_SIZE) {
            String id = queue.poll();
            if (id == null || id.equals(rootId) || !visited.add(id)) {
                continue;
            }
            result.add(id);
            // Only workflows have children; everything else is a leaf. Attempting to read
            // every id as a workflow is what keeps this free of a type registry, and a
            // non-workflow id simply fails the read and contributes nothing.
            expandWorkflow(id, queue);
        }

        if (result.size() >= MAX_GRAPH_SIZE) {
            LOGGER.warnf("Config graph for %s hit the %d-resource ceiling; the remainder was not included in this operation",
                    sanitize(rootId), MAX_GRAPH_SIZE);
        }
        return result;
    }

    private void seedFromRoot(String rootId, Deque<String> queue, Set<String> visited, int depth) {
        if (!visited.add(rootId) && depth > 0) {
            // Already seeded — a group that (directly or indirectly) contains itself.
            return;
        }

        // An agent points at workflows.
        try {
            var current = agentStore.getCurrentResourceId(rootId);
            var agent = agentStore.read(rootId, current.getVersion());
            if (agent != null && agent.getWorkflows() != null) {
                for (URI workflowUri : agent.getWorkflows()) {
                    offerUri(workflowUri, queue);
                }
                return;
            }
        } catch (Exception e) {
            LOGGER.debugf("Root %s is not a readable agent (%s); trying agent group", sanitize(rootId), e.getMessage());
        }

        // An agent group points at member agents, which point at workflows.
        try {
            var current = agentGroupStore.getCurrentResourceId(rootId);
            AgentGroupConfiguration group = agentGroupStore.read(rootId, current.getVersion());
            if (group != null && group.getMembers() != null) {
                for (var member : group.getMembers()) {
                    if (member == null || member.agentId() == null || member.agentId().isBlank()) {
                        continue;
                    }
                    queue.offer(member.agentId());
                    // A member's own graph. A member is either an agent (workflows) or a
                    // nested group (group-of-groups is a shipped feature), and the id alone
                    // does not say which — so try both and let the unreadable one contribute
                    // nothing. Bounded by `visited` and MAX_GRAPH_SIZE like everything else.
                    for (String nested : referencedFromAgent(member.agentId())) {
                        queue.offer(nested);
                    }
                    if (depth < MAX_GROUP_NESTING) {
                        seedFromRoot(member.agentId(), queue, visited, depth + 1);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debugf("Root %s is not a readable agent group either (%s)", sanitize(rootId), e.getMessage());
        }
    }

    private List<String> referencedFromAgent(String agentId) {
        try {
            var current = agentStore.getCurrentResourceId(agentId);
            var agent = agentStore.read(agentId, current.getVersion());
            if (agent == null || agent.getWorkflows() == null) {
                return List.of();
            }
            return agent.getWorkflows().stream().map(ConfigGraphResolver::idOf).filter(id -> id != null && !id.isBlank()).toList();
        } catch (Exception e) {
            LOGGER.debugf("Could not read member agent %s: %s", sanitize(agentId), e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private void expandWorkflow(String workflowId, Deque<String> queue) {
        WorkflowConfiguration workflow;
        try {
            IResourceStore.IResourceId current = workflowStore.getCurrentResourceId(workflowId);
            workflow = workflowStore.read(workflowId, current.getVersion());
        } catch (Exception e) {
            // Expected for every non-workflow id; debug rather than warn.
            LOGGER.debugf("Not expanding %s as a workflow: %s", sanitize(workflowId), e.getMessage());
            return;
        }
        if (workflow == null || workflow.getWorkflowSteps() == null) {
            return;
        }

        for (WorkflowConfiguration.WorkflowStep step : workflow.getWorkflowSteps()) {
            if (step == null) {
                continue;
            }
            Map<String, Object> config = step.getConfig();
            if (!isNullOrEmpty(config)) {
                Object uri = config.get(KEY_URI);
                if (!isNullOrEmpty(uri)) {
                    offerUri(safeUri(uri.toString()), queue);
                }
            }

            // Parser dictionaries hang off the step's extensions rather than its config,
            // the same shape RestWorkflowStore's cascade delete has to special-case.
            URI type = step.getType();
            if (type != null && PARSER_HOST.equals(type.getHost()) && step.getExtensions() != null) {
                Object dictionariesObj = step.getExtensions().get("dictionaries");
                if (dictionariesObj instanceof List<?> dictionaries) {
                    for (Object entry : dictionaries) {
                        if (entry instanceof Map<?, ?> dictionary) {
                            offerDictionary((Map<String, Object>) dictionary, queue);
                        }
                    }
                }
            }
        }
    }

    private void offerDictionary(Map<String, Object> dictionary, Deque<String> queue) {
        Object dictType = dictionary.get("type");
        if (dictType == null) {
            return;
        }
        URI typeUri = safeUri(dictType.toString());
        if (typeUri == null || !REGULAR_DICTIONARY_HOST.equals(typeUri.getHost())) {
            return;
        }
        Object configObj = dictionary.get("config");
        if (configObj instanceof Map<?, ?> config) {
            Object uri = config.get(KEY_URI);
            if (!isNullOrEmpty(uri)) {
                offerUri(safeUri(uri.toString()), queue);
            }
        }
    }

    private static void offerUri(URI uri, Deque<String> queue) {
        String id = idOf(uri);
        if (id != null && !id.isBlank()) {
            queue.offer(id);
        }
    }

    private static String idOf(URI uri) {
        if (uri == null) {
            return null;
        }
        try {
            IResourceStore.IResourceId resourceId = RestUtilities.extractResourceId(uri);
            return resourceId == null ? null : resourceId.getId();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static URI safeUri(String value) {
        try {
            return URI.create(value);
        } catch (IllegalArgumentException e) {
            // A config field is user input; a malformed URI skips one edge rather than
            // aborting the whole traversal.
            return null;
        }
    }
}
