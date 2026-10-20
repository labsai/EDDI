/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared logic for traversing agent workflows and discovering extension
 * configurations by step type. Eliminates duplicated traversal code between
 * httpcall and mcpcalls tool discovery.
 * <p>
 * Finding F12: a single conversation turn traverses the SAME agent and the SAME
 * workflows three to four times — httpcall tools, mcpcall tools, vector RAG and
 * httpCall RAG each call {@link #discoverConfigs} independently. A cache
 * collapses those into one traversal per (agent, version, step type, config
 * type) — and, since a config edit always creates a new version, keeps it for
 * every later turn too (see {@link #CACHE}).
 * <p>
 * Only <em>complete</em> traversals are cached. A traversal that could not read
 * a workflow or a step's configuration is returned but never memoized —
 * otherwise a single transient store failure would be frozen into an empty
 * result and replayed to the rest of the turn, silently stripping the agent of
 * its httpcalls/mcpcalls/RAG configuration.
 */
public final class WorkflowTraversal {
    private static final Logger LOGGER = Logger.getLogger(WorkflowTraversal.class);

    /**
     * Matches the numeric value of a real {@code version} query param.
     * <p>
     * Anchored to parameter boundaries on purpose: an unanchored {@code version=}
     * also matches inside {@code subversion=123}, which would parse a version out
     * of a query that does not carry one.
     */
    private static final Pattern VERSION_PARAM = Pattern.compile("(?:^|&)version=(\\d+)(?:&|$)");

    /**
     * Bound on cached traversals, least-recently-used evicted first. One entry per
     * (agent version, step type, config type) — a deployment needs a few per agent
     * version it runs.
     */
    static final int CACHE_MAX_ENTRIES = 1_000;

    /**
     * Complete traversals, keyed by agent id, agent version, step type and config
     * type.
     * <p>
     * <b>No TTL, because nothing it holds can change.</b> An agent version, the
     * workflow versions it names and the extension versions those name are
     * immutable once stored — the only in-place writers are the descriptor store
     * and startup migrations, which run before traffic. This used to expire after 2
     * s, which deduplicated the traversals of one turn but re-read the agent, its
     * workflows and their extensions on practically every turn (turns of one
     * conversation are rarely 2 s apart) — about one agent read per LLM turn.
     * Entries are dropped when the agent is deployed or undeployed
     * ({@link #invalidateAgent}), so a deleted version does not linger.
     */
    private static final Cache<String, List<?>> CACHE = Caffeine.newBuilder().maximumSize(CACHE_MAX_ENTRIES).build();

    private WorkflowTraversal() {
        // static utility class
    }

    /**
     * Drop every cached traversal. Exposed for tests and config-change handling.
     */
    public static void clearCache() {
        CACHE.invalidateAll();
    }

    /**
     * Drop the cached traversals of every version of {@code agentId} — called when
     * the agent is deployed or undeployed.
     */
    public static void invalidateAgent(String agentId) {
        if (agentId == null) {
            return;
        }
        String prefix = agentId + '|';
        CACHE.asMap().keySet().removeIf(key -> key.startsWith(prefix));
    }

    /**
     * Result of traversing a workflow step — the loaded configuration and the
     * step's raw config map.
     *
     * @param config
     *            the deserialized extension configuration
     * @param stepConfig
     *            the raw step config map (for access to additional properties)
     * @param <T>
     *            the configuration type
     */
    record StepConfig<T>(T config, Map<String, Object> stepConfig) {
    }

    /**
     * Traverse all workflows for the agent in memory, filter by step type, and load
     * the extension configuration for each matching step.
     *
     * @param memory
     *            conversation memory (provides agentId/version)
     * @param stepTypeUri
     *            the step type URI to match (e.g., "eddi://ai.labs.httpcalls")
     * @param configClass
     *            the class to deserialize the configuration into
     * @param agentStore
     *            agent store for loading agent configurations
     * @param workflowStore
     *            workflow store for loading workflow configurations
     * @param resourceClientLibrary
     *            resource client for loading extension configurations
     * @param <T>
     *            the configuration type
     * @return list of discovered configurations (never null)
     */
    static <T> List<StepConfig<T>> discoverConfigs(IConversationMemory memory, String stepTypeUri, Class<T> configClass,
                                                   IAgentStore agentStore, IWorkflowStore workflowStore,
                                                   IResourceClientLibrary resourceClientLibrary) {

        return discoverConfigs(memory, stepTypeUri, configClass, agentStore, workflowStore, resourceClientLibrary,
                System.currentTimeMillis());
    }

    /**
     * As
     * {@link #discoverConfigs(IConversationMemory, String, Class, IAgentStore, IWorkflowStore, IResourceClientLibrary)},
     * with an explicit clock reading. Entries no longer expire, so the clock is
     * unused; the overload is kept for the callers and tests that pass one.
     *
     * @param nowMillis
     *            the current time in milliseconds (ignored)
     */
    static <T> List<StepConfig<T>> discoverConfigs(IConversationMemory memory, String stepTypeUri, Class<T> configClass,
                                                   IAgentStore agentStore, IWorkflowStore workflowStore,
                                                   IResourceClientLibrary resourceClientLibrary, long nowMillis) {

        List<StepConfig<T>> results = new ArrayList<>();

        String agentId = memory.getAgentId();
        Integer agentVersion = memory.getAgentVersion();
        if (agentId == null || agentVersion == null) {
            LOGGER.debugf("No agent context in memory — skipping %s discovery", stepTypeUri);
            return results;
        }

        // F12: one traversal per (agent, version, step type, config type) per turn
        // instead of the three or four identical re-reads the pipeline used to issue.
        // The config type belongs in the key: without it, two callers asking for the
        // same step type but a different target class would share an entry and the
        // second one would get a ClassCastException out of an unrelated cache hit.
        String cacheKey = agentId + '|' + agentVersion + '|' + stepTypeUri + '|' + configClass.getName();
        List<?> cached = CACHE.getIfPresent(cacheKey);
        if (cached != null) {
            @SuppressWarnings("unchecked") // configClass is part of the key, so the element type matches
            List<StepConfig<T>> hit = (List<StepConfig<T>>) cached;
            LOGGER.debugf("Reusing cached %s traversal for agent %s v%d (%d config(s))", stepTypeUri, agentId, agentVersion, hit.size());
            return new ArrayList<>(hit);
        }

        // Set whenever a step, a workflow or the agent could not be read as intended.
        // A degraded traversal produces an incomplete picture, and memoizing that would
        // turn one transient store blip into an agent that silently loses its
        // httpcalls/mcpcalls/RAG configuration for the rest of the turn (and every turn
        // after it). Only a complete traversal is cacheable.
        boolean degraded = false;

        AgentConfiguration agentConfig;
        try {
            agentConfig = agentStore.read(agentId, agentVersion);
        } catch (Exception e) {
            LOGGER.warnf("Failed to load agent config for %s v%d: %s", agentId, agentVersion, e.getMessage());
            return results;
        }

        if (agentConfig == null || agentConfig.getWorkflows() == null || agentConfig.getWorkflows().isEmpty()) {
            LOGGER.debugf("No workflows found for agent %s — skipping %s discovery", agentId, stepTypeUri);
            return results;
        }

        for (URI workflowUri : agentConfig.getWorkflows()) {
            String workflowPath = workflowUri.getPath();
            if (workflowPath == null) {
                LOGGER.warnf("Workflow URI has no path: %s", workflowUri);
                degraded = true;
                continue;
            }
            String workflowId = workflowPath.substring(workflowPath.lastIndexOf('/') + 1);
            String workflowQuery = workflowUri.getQuery();
            if (workflowQuery == null || !workflowQuery.contains("version=")) {
                LOGGER.warnf("Workflow URI has no version query: %s", workflowUri);
                degraded = true;
                continue;
            }
            // replaceAll returns the string UNCHANGED when the pattern does not match, so
            // "version=abc" reached parseInt as "version=abc" and threw — escaping the
            // degrade-and-continue contract every other branch here honours, and aborting
            // discovery for the whole turn over one malformed workflow URI. Match
            // explicitly
            // instead, and treat "present but unusable" exactly like "absent".
            Matcher versionMatcher = VERSION_PARAM.matcher(workflowQuery);
            int workflowVersion;
            try {
                if (!versionMatcher.find()) {
                    throw new NumberFormatException("no numeric version in query");
                }
                workflowVersion = Integer.parseInt(versionMatcher.group(1));
            } catch (NumberFormatException e) {
                // Also covers a digit run too large for an int.
                LOGGER.warnf("Workflow URI has an unusable version query: %s", workflowUri);
                degraded = true;
                continue;
            }

            try {
                WorkflowConfiguration workflowConfig = workflowStore.read(workflowId, workflowVersion);
                for (var step : workflowConfig.getWorkflowSteps()) {
                    if (step.getType() != null && stepTypeUri.equals(step.getType().toString())) {
                        String uri = (String) step.getConfig().get("uri");
                        if (uri == null)
                            continue;

                        try {
                            T config = resourceClientLibrary.getResource(URI.create(uri), configClass);
                            results.add(new StepConfig<>(config, step.getConfig()));
                        } catch (ServiceException e) {
                            LOGGER.warnf("Failed to load %s config: %s — %s", stepTypeUri, uri, e.getMessage());
                            degraded = true;
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.warnf("Failed to load workflow %s v%d: %s", workflowId, workflowVersion, e.getMessage());
                degraded = true;
            }
        }

        if (degraded) {
            LOGGER.debugf("Not caching %s traversal for agent %s v%d — the traversal was incomplete", stepTypeUri, agentId, agentVersion);
            return results;
        }

        CACHE.put(cacheKey, List.copyOf(results));

        return results;
    }
}
