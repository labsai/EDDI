/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.compat;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.output.model.OutputConfigurationSet;
import ai.labs.eddi.configs.propertysetter.model.PropertySetterConfiguration;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.compat.CompatibilityRules.Finding;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import ai.labs.eddi.utils.RestUtilities;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads an agent's configuration at deployment and reports what would behave
 * differently in v6 than it did in 5.x, without ever blocking the deployment.
 * <p>
 * A 5.x agent migrates cleanly and deploys READY, then fails on its first real
 * turn: a credential that is still a template, a JSON-mode flag the engine no
 * longer reads, tools that are now on by default, a template the new engine
 * cannot parse. This lint says so up front, once per deployment, in the log and
 * in the deployment status ({@code AgentDeploymentStatus.warnings}).
 * <p>
 * It reads the same stored documents the pipeline will load, and parses
 * templates with the restricted runtime engine ({@link ITemplatingEngine}), so
 * "does not parse" means the same here as at render time. What it checks is in
 * {@link CompatibilityRules}.
 * <p>
 * Best-effort by contract: {@link #lint} never throws. A document that cannot
 * be read is skipped, so a store hiccup costs warnings, never a deployment.
 */
@ApplicationScoped
public class AgentCompatibilityLint {

    private static final Logger LOGGER = Logger.getLogger(AgentCompatibilityLint.class);

    private static final String LLM_AUTHORITY = "ai.labs.llm";
    private static final String API_CALLS_AUTHORITY = "ai.labs.apicalls";
    private static final String HTTP_CALLS_AUTHORITY = "ai.labs.httpcalls";
    private static final String MCP_CALLS_AUTHORITY = "ai.labs.mcpcalls";
    private static final String PROPERTY_AUTHORITY = "ai.labs.property";
    private static final String OUTPUT_AUTHORITY = "ai.labs.output";

    private final IAgentStore agentStore;
    private final IWorkflowStore workflowStore;
    private final IResourceClientLibrary resourceClientLibrary;
    private final ITemplatingEngine templatingEngine;
    private final IJsonSerialization jsonSerialization;

    @Inject
    public AgentCompatibilityLint(IAgentStore agentStore, IWorkflowStore workflowStore, IResourceClientLibrary resourceClientLibrary,
            ITemplatingEngine templatingEngine, IJsonSerialization jsonSerialization) {
        this.agentStore = agentStore;
        this.workflowStore = workflowStore;
        this.resourceClientLibrary = resourceClientLibrary;
        this.templatingEngine = templatingEngine;
        this.jsonSerialization = jsonSerialization;
    }

    /**
     * @return one rendered line per finding; empty when the agent is clean or could
     *         not be inspected. Never throws.
     */
    public List<String> lint(String agentId, Integer agentVersion) {
        try {
            return collect(agentId, agentVersion).stream().map(Finding::render).toList();
        } catch (Exception e) {
            // Advisory only: whatever went wrong must not touch the deployment.
            LOGGER.debugf(e, "Compatibility lint skipped for agent %s v%s", agentId, agentVersion);
            return List.of();
        }
    }

    private List<Finding> collect(String agentId, Integer agentVersion) throws Exception {
        var agentConfiguration = agentStore.read(agentId, agentVersion);
        List<Finding> findings = new ArrayList<>();
        if (agentConfiguration == null || agentConfiguration.getWorkflows() == null) {
            return findings;
        }

        for (URI workflowUri : agentConfiguration.getWorkflows()) {
            IResourceStore.IResourceId workflowId = RestUtilities.extractResourceId(workflowUri);
            if (workflowId == null || workflowId.getId() == null || workflowId.getVersion() == null) {
                continue;
            }
            WorkflowConfiguration workflow;
            try {
                workflow = workflowStore.read(workflowId.getId(), workflowId.getVersion());
            } catch (Exception e) {
                LOGGER.debugf(e, "Compatibility lint: cannot read workflow %s", workflowId.getId());
                continue;
            }
            lintWorkflow(workflow, findings);
        }
        return findings;
    }

    private void lintWorkflow(WorkflowConfiguration workflow, List<Finding> findings) {
        List<URI> uris = new ArrayList<>();
        boolean hasApiCalls = false;
        boolean hasMcpCalls = false;
        for (var step : workflow.getWorkflowSteps()) {
            URI uri = referenceOf(step);
            if (uri == null) {
                continue;
            }
            uris.add(uri);
            hasApiCalls |= API_CALLS_AUTHORITY.equals(uri.getHost()) || HTTP_CALLS_AUTHORITY.equals(uri.getHost());
            hasMcpCalls |= MCP_CALLS_AUTHORITY.equals(uri.getHost());
        }

        for (URI uri : uris) {
            String authority = uri.getHost();
            try {
                switch (authority == null ? "" : authority) {
                    case LLM_AUTHORITY -> lintLlm(uri, hasApiCalls, hasMcpCalls, findings);
                    case API_CALLS_AUTHORITY, HTTP_CALLS_AUTHORITY -> scan("api calls " + idOf(uri), uri, ApiCallsConfiguration.class, findings);
                    case PROPERTY_AUTHORITY -> scan("property setter " + idOf(uri), uri, PropertySetterConfiguration.class, findings);
                    case OUTPUT_AUTHORITY -> scan("output set " + idOf(uri), uri, OutputConfigurationSet.class, findings);
                    default -> {
                        // not a document this lint reads
                    }
                }
            } catch (Exception e) {
                LOGGER.debugf(e, "Compatibility lint: cannot check %s", uri);
            }
        }
    }

    private void lintLlm(URI uri, boolean hasApiCalls, boolean hasMcpCalls, List<Finding> findings) throws Exception {
        LlmConfiguration configuration = resourceClientLibrary.getResource(uri, LlmConfiguration.class);
        if (configuration == null || configuration.tasks() == null) {
            return;
        }
        for (var task : configuration.tasks()) {
            String location = "llm task '" + (task.getId() != null ? task.getId() : "?") + "'";
            findings.addAll(CompatibilityRules.lintLlmTask(location, task, hasApiCalls, hasMcpCalls));
            CompatibilityRules.scanTemplates(location, asTree(task), templatingEngine::validateTemplate, findings);
        }
    }

    private <T> void scan(String location, URI uri, Class<T> type, List<Finding> findings) throws Exception {
        T configuration = resourceClientLibrary.getResource(uri, type);
        if (configuration != null) {
            CompatibilityRules.scanTemplates(location, asTree(configuration), templatingEngine::validateTemplate, findings);
        }
    }

    /**
     * The configuration as the JSON the engine would store, as plain maps and
     * lists.
     */
    private Object asTree(Object configuration) throws Exception {
        return jsonSerialization.deserialize(jsonSerialization.serialize(configuration), Map.class);
    }

    private static URI referenceOf(WorkflowConfiguration.WorkflowStep step) {
        if (step.getConfig() == null || !(step.getConfig().get("uri") instanceof String uri)) {
            return null;
        }
        try {
            return URI.create(uri);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String idOf(URI uri) {
        var id = RestUtilities.extractResourceId(uri);
        return id != null && id.getId() != null ? id.getId() : uri.toString();
    }
}
