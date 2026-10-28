/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.engine.schedule.rest.RestScheduleStore;
import ai.labs.eddi.configs.rest.StrictConfigurationParser;
import ai.labs.eddi.configs.rules.IRestRuleSetStore;
import ai.labs.eddi.configs.rules.model.RuleSetConfiguration;
import ai.labs.eddi.engine.triggermanagement.IRestAgentTriggerStore;
import ai.labs.eddi.configs.agents.AgentCascadeService;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentCascadeRequest;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IRestDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.apicalls.IRestApiCallsStore;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.channels.IRestChannelIntegrationStore;
import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.mcpcalls.IRestMcpCallsStore;
import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import ai.labs.eddi.configs.llm.IRestLlmStore;
import ai.labs.eddi.configs.output.IRestOutputStore;
import ai.labs.eddi.configs.output.model.OutputConfigurationSet;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.configs.patch.PatchInstruction;
import ai.labs.eddi.configs.propertysetter.IRestPropertySetterStore;
import ai.labs.eddi.configs.rag.IRestRagStore;
import ai.labs.eddi.configs.propertysetter.model.PropertySetterConfiguration;
import ai.labs.eddi.configs.dictionary.IRestDictionaryStore;
import ai.labs.eddi.configs.dictionary.model.DictionaryConfiguration;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.schedule.model.ScheduleFireLog;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.model.DeploymentFailure;
import ai.labs.eddi.engine.continuation.ConversationContinuations;
import ai.labs.eddi.engine.hitl.HitlSchedules;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.engine.tenancy.QuotaRefusal;
import ai.labs.eddi.engine.runtime.internal.CronDescriber;
import ai.labs.eddi.engine.runtime.internal.CronParser;
import ai.labs.eddi.engine.runtime.internal.ScheduleFireExecutor;
import ai.labs.eddi.engine.runtime.internal.SchedulePollerService;
import ai.labs.eddi.modules.llm.impl.ToolBudgetEnforcement;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.engine.triggermanagement.model.AgentTriggerConfiguration;
import ai.labs.eddi.utils.LogSanitizer;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.engine.mcp.McpToolUtils.*;

/**
 * MCP tools for administering EDDI agents, packages, and resources. Exposes
 * deploy, undeploy, CRUD, batch cascade, and introspection as MCP-compliant
 * tools via the Quarkus MCP Server extension.
 *
 * <p>
 * Phase 8a — Admin API MCP Server
 * <p>
 * Phase 8a.2 — Resource CRUD + Batch Cascade + Introspection
 *
 * @author ginccc
 */
@ApplicationScoped
@McpErrorResults
public class McpAdminTools {

    private static final Logger LOGGER = Logger.getLogger(McpAdminTools.class);

    private final IRestInterfaceFactory restInterfaceFactory;
    private final IRestAgentAdministration agentAdmin;
    private final AgentCascadeService cascadeService;
    private final IJsonSerialization jsonSerialization;

    /**
     * Deserialises resource configurations with the same strictness the REST
     * surface applies. {@code StrictConfigurationBodyInterceptor} is a JAX-RS
     * {@code ReaderInterceptor}, so it only fires on a real inbound HTTP body —
     * these tools call the very same stores in-process and were therefore parsing
     * leniently, accepting payloads REST rejects and storing the emptied result as
     * a success.
     */
    private final StrictConfigurationParser configParser;
    private final IScheduleStore scheduleStore;
    private final ScheduleFireExecutor scheduleFireExecutor;
    private final SchedulePollerService schedulePollerService;
    private final SecurityIdentity identity;
    private final boolean authEnabled;

    @Inject
    public McpAdminTools(IRestInterfaceFactory restInterfaceFactory, IRestAgentAdministration agentAdmin, IJsonSerialization jsonSerialization,
            StrictConfigurationParser configParser, IScheduleStore scheduleStore, ScheduleFireExecutor scheduleFireExecutor,
            SchedulePollerService schedulePollerService, SecurityIdentity identity,
            @ConfigProperty(name = "authorization.enabled", defaultValue = "false") boolean authEnabled, AgentCascadeService cascadeService) {
        this.cascadeService = cascadeService;
        this.restInterfaceFactory = restInterfaceFactory;
        this.agentAdmin = agentAdmin;
        this.jsonSerialization = jsonSerialization;
        this.configParser = configParser;
        this.scheduleStore = scheduleStore;
        this.scheduleFireExecutor = scheduleFireExecutor;
        this.schedulePollerService = schedulePollerService;
        this.identity = identity;
        this.authEnabled = authEnabled;
    }

    @Tool(name = "deploy_agent", description = "Deploy a Agent to an environment. The Agent must exist and have a valid configuration. "
            + "Returns the deployment status.")
    public String deployAgent(@ToolArg(description = "Agent ID (required)") String agentId,
                              @ToolArg(description = "Version number to deploy (required)") Integer version,
                              @ToolArg(description = "Environment: 'production' (default) or 'test'", required = false,
                                       defaultValue = "production") String environment) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        try {
            var env = parseEnvironment(environment);
            int ver = version != null ? version : 1;
            Response response = agentAdmin.deployAgent(env, agentId, ver, true, true);
            int httpStatus = response.getStatus();

            var result = new LinkedHashMap<String, Object>();
            result.put("agentId", agentId);
            result.put("version", ver);
            result.put("environment", env.name());
            result.put("httpStatus", httpStatus);
            // Whether a maxBudgetPerConversation on this agent's LLM tasks refuses
            // calls when the task does not set enforceBudget (M8).
            result.put("toolBudgetEnforcedByDefault", ToolBudgetEnforcement.isEnforcedByDefault());

            if (httpStatus == 200) {
                // Read actual deployment status from response body
                try {
                    @SuppressWarnings("unchecked")
                    var body = (Map<String, Object>) response.getEntity();
                    if (body != null && body.containsKey("status")) {
                        String deployStatus = body.get("status").toString();
                        result.put("deploymentStatus", deployStatus);
                        boolean ready = "READY".equals(deployStatus);
                        result.put("deployed", ready);
                        if (!ready && !"IN_PROGRESS".equals(deployStatus)) {
                            result.put("action", "deploy_failed");
                            if (body.get("failure") instanceof DeploymentFailure failure) {
                                // The structured reason — for a missing vault grant, the
                                // secret names and the exact grant call. Granting is
                                // deliberately NOT something this toolset can do: no MCP tool
                                // widens a grant, so the model relays the fix to a human admin.
                                result.put("failureCode", failure.code());
                                if (failure.secrets() != null) {
                                    result.put("secrets", failure.secrets());
                                }
                                result.put("error", failure.message());
                                if (failure.isGrantMissing()) {
                                    result.put("humanActionRequired", "An administrator must add this agent to the grant of each listed "
                                            + "secret (the Manager's deploy dialog or Secrets page, or the endpoints in 'fix'), then deploy "
                                            + "this same agent again. No tool available here can change a grant.");
                                    result.put("fix", failure.fix());
                                }
                            } else {
                                result.put("error", "Deployment status is " + deployStatus
                                        + ". Check Agent configuration, LLM provider credentials, and model availability.");
                            }
                            return jsonSerialization.serialize(result);
                        }
                    }
                } catch (Exception parseError) {
                    result.put("deployed", false);
                    result.put("parseWarning", "Could not read deployment status from response");
                }
            } else if (httpStatus == 202) {
                result.put("deployed", false);
                result.put("deploymentStatus", "IN_PROGRESS");
            }

            return resultJson("deployed", result);
        } catch (Exception e) {
            // Match the QuotaRefusal marker rather than one concrete class: the
            // accounting-outage refusal is a sibling of QuotaExceededException, not a
            // subclass, so a catch naming only the latter drops a store outage into
            // the generic branch below.
            if (e instanceof QuotaRefusal) {
                // Return the quota reason rather than the generic message: an MCP client
                // (and the model driving it) cannot self-correct from "check server logs"
                // and will retry the deploy in a loop.
                LOGGER.warn("MCP deploy_agent refused by quota layer for Agent " + agentId + ": " + e.getMessage());
                return errorJson(e.getMessage());
            }
            LOGGER.error("MCP deploy_agent failed for Agent " + agentId, e);
            return errorJson("Failed to deploy agent. Check server logs for details.");
        }
    }

    @Tool(name = "undeploy_agent", description = "Undeploy a Agent from an environment. Optionally end all active conversations.")
    public String undeployAgent(@ToolArg(description = "Agent ID (required)") String agentId,
                                @ToolArg(description = "Version number to undeploy (required)") Integer version,
                                @ToolArg(description = "Environment: 'production' (default) or 'test'", required = false,
                                         defaultValue = "production") String environment,
                                @ToolArg(description = "End all active conversations? (default: false)", required = false,
                                         defaultValue = "false") Boolean endConversations) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        try {
            var env = parseEnvironment(environment);
            int ver = version != null ? version : 1;
            boolean endAll = endConversations != null ? endConversations : false;
            Response response = agentAdmin.undeployAgent(env, agentId, ver, endAll, false);
            return resultJson("undeployed", Map.of("agentId", agentId, "version", ver, "environment", env.name(), "endedConversations", endAll,
                    "status", response.getStatus()));
        } catch (Exception e) {
            LOGGER.error("MCP undeploy_agent failed for Agent " + agentId, e);
            return errorJson("Failed to undeploy agent", e);
        }
    }

    @Tool(name = "upgrade_conversations", description = "Move an agent's running conversations that sit on an older compatibility generation "
            + "(or none) onto the generation of its CURRENT version, so they continue on it from their next turn instead of being ended. "
            + "Use after a breaking save, or a ZIP import or agent sync made without compatible=true, which pins every running conversation to the "
            + "old one. DRY RUN BY DEFAULT: it only reports per source version how many conversations match, would move, or are skipped "
            + "(ended, awaitingHuman, inProgress); call again with dryRun=false after checking the numbers. Requires a version of the "
            + "current generation to be READY in the environment. Never lowers a generation; idempotent. Each call looks at a bounded "
            + "number of conversations: when the report says truncated, call again with after=<nextAfter>. After a real run, dry-run "
            + "again before undeploying an old version, and undeploy it only when it reports eligible 0; conversations skipped as busy, "
            + "awaitingHuman or inProgress still run on the old version.")
    public String upgradeConversations(@ToolArg(description = "Agent ID (required)") String agentId,
                                       @ToolArg(description = "Environment: 'production' (default) or 'test'", required = false,
                                                defaultValue = "production") String environment,
                                       @ToolArg(description = "true (default): only report what would happen. false: move the conversations.",
                                                required = false, defaultValue = "true") Boolean dryRun,
                                       @ToolArg(description = "Only conversations stored on these agent versions, comma separated (optional; "
                                               + "default: every version on an older generation)", required = false) String fromVersions,
                                       @ToolArg(description = "Most conversations to look at in this call (default 5000, so a call stays "
                                               + "well inside a proxy timeout)", required = false) Integer limit,
                                       @ToolArg(description = "Continue after this conversation id: the 'nextAfter' of the previous "
                                               + "report when it said truncated (optional)", required = false) String after) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        try {
            var env = parseEnvironment(environment);
            List<Integer> versions = new ArrayList<>();
            if (fromVersions != null && !fromVersions.isBlank()) {
                for (String version : fromVersions.split(",")) {
                    versions.add(Integer.valueOf(version.trim()));
                }
            }
            Response response = agentAdmin.upgradeConversations(env, agentId, dryRun, versions, limit, after);
            if (response.getStatus() != 200) {
                return errorJson(String.valueOf(response.getEntity()));
            }
            return jsonSerialization.serialize(response.getEntity());
        } catch (NumberFormatException e) {
            return errorJson("fromVersions must be a comma separated list of version numbers");
        } catch (Exception e) {
            LOGGER.error("MCP upgrade_conversations failed for Agent " + LogSanitizer.sanitize(agentId), e);
            return errorJson("Failed to upgrade conversations", e);
        }
    }

    @Tool(name = "get_deployment_status", description = "Get the deployment status of a specific Agent version in an environment.")
    public String getDeploymentStatus(@ToolArg(description = "Agent ID (required)") String agentId,
                                      @ToolArg(description = "Version number (required)") Integer version,
                                      @ToolArg(description = "Environment: 'production' (default) or 'test'", required = false,
                                               defaultValue = "production") String environment) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        try {
            var env = parseEnvironment(environment);
            int ver = version != null ? version : 1;
            Response response = agentAdmin.getDeploymentStatus(env, agentId, ver, "json");
            Object entity = response.getEntity();
            if (entity == null) {
                return resultJson("status_check", Map.of("agentId", agentId, "version", ver, "httpStatus", response.getStatus()));
            }
            return jsonSerialization.serialize(entity);
        } catch (Exception e) {
            LOGGER.error("MCP get_deployment_status failed for Agent " + agentId, e);
            return errorJson("Failed to get deployment status", e);
        }
    }

    @Tool(name = "list_workflows", description = "List all workflow configurations. "
            + "Returns a JSON array of workflow descriptors with name, description, and IDs.")
    public String listWorkflows(@ToolArg(description = "Filter string to search workflow names (optional)", required = false) String filter,
                                @ToolArg(description = "Maximum number of results (default: 20)", required = false,
                                         defaultValue = "20") Integer limit) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        try {
            int limitInt = limit != null ? limit : 20;
            String filterStr = filter != null ? filter : "";
            List<DocumentDescriptor> descriptors = getRestStore(IRestWorkflowStore.class).readWorkflowDescriptors(filterStr, 0, limitInt);
            return jsonSerialization.serialize(descriptors);
        } catch (Exception e) {
            LOGGER.error("MCP list_workflows failed", e);
            return errorJson("Failed to list workflows", e);
        }
    }

    @Tool(name = "create_agent", description = "Create a new Agent with a given name and optional packages. "
            + "The Agent is created and its descriptor is updated with the provided name and description. "
            + "Returns the Agent ID and Location URI of the newly created agent.")
    public String createAgent(@ToolArg(description = "Agent name (required)") String name,
                              @ToolArg(description = "Agent description (optional)", required = false) String description,
                              @ToolArg(description = "Comma-separated list of package URIs to include (optional, format: eddi://ai.labs.workflow/workflowstore/workflows/ID?version=1)",
                                       required = false) String workflowUris) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        if (name == null || name.isBlank())
            return errorJson("Agent name is required");
        try {
            var agentConfig = new AgentConfiguration();
            if (workflowUris != null && !workflowUris.isBlank()) {
                var uris = new ArrayList<URI>();
                for (String uri : workflowUris.split(",")) {
                    uris.add(URI.create(uri.trim()));
                }
                agentConfig.setWorkflows(uris);
            }

            Response response = getRestStore(IRestAgentStore.class).createAgent(agentConfig);
            String location = response.getHeaderString("Location");

            // Extract Agent ID from location header (format:
            // /agentstore/agents/{id}?version=1)
            String agentId = extractIdFromLocation(location);

            // Update descriptor with name and description
            if (agentId != null && (name != null || description != null)) {
                try {
                    var descriptor = new DocumentDescriptor();
                    if (name != null)
                        descriptor.setName(name);
                    if (description != null)
                        descriptor.setDescription(description);

                    var patch = new PatchInstruction<DocumentDescriptor>();
                    patch.setOperation(PatchInstruction.PatchOperation.SET);
                    patch.setDocument(descriptor);
                    getRestStore(IRestDocumentDescriptorStore.class).patchDescriptor(agentId, 1, patch);
                } catch (Exception patchError) {
                    LOGGER.warn("MCP create_agent: Agent created but descriptor update failed for " + agentId, patchError);
                    // Agent was still created — return success with warning
                }
            }

            return resultJson("created", Map.of("agentId", agentId != null ? agentId : "unknown", "name", name != null ? name : "", "description",
                    description != null ? description : "", "location", location != null ? location : "unknown", "status", response.getStatus()));
        } catch (Exception e) {
            LOGGER.error("MCP create_agent failed", e);
            return errorJson("Failed to create agent", e);
        }
    }

    @Tool(name = "delete_agent", description = "Delete a agent. Optionally cascade-delete all referenced packages and resources.")
    public String deleteAgent(@ToolArg(description = "Agent ID (required)") String agentId,
                              @ToolArg(description = "Version number (required)") Integer version,
                              @ToolArg(description = "Permanently delete? (default: false)", required = false,
                                       defaultValue = "false") Boolean permanent,
                              @ToolArg(description = "Cascade-delete packages and resources? (default: false)", required = false,
                                       defaultValue = "false") Boolean cascade) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        try {
            int ver = version != null ? version : 1;
            boolean isPermanent = permanent != null ? permanent : false;
            boolean isCascade = cascade != null ? cascade : false;
            Response response = getRestStore(IRestAgentStore.class).deleteAgent(agentId, ver, isPermanent, isCascade);
            return resultJson("deleted",
                    Map.of("agentId", agentId, "version", ver, "permanent", isPermanent, "cascade", isCascade, "status", response.getStatus()));
        } catch (Exception e) {
            LOGGER.error("MCP delete_agent failed for Agent " + agentId, e);
            return errorJson("Failed to delete agent", e);
        }
    }

    @Tool(name = "update_agent", description = "Update an agent's name and/or description, and optionally redeploy. "
            + "For structural changes (adding/removing packages, modifying resources), use the "
            + "individual resource tools (read_workflow, read_resource) and the REST API directly.")
    public String updateAgent(@ToolArg(description = "Agent ID (required)") String agentId,
                              @ToolArg(description = "Version number (required)") Integer version,
                              @ToolArg(description = "New Agent name (optional)", required = false) String name,
                              @ToolArg(description = "New Agent description (optional)", required = false) String description,
                              @ToolArg(description = "Redeploy the Agent after update? (default: false)", required = false,
                                       defaultValue = "false") Boolean redeploy,
                              @ToolArg(description = "Environment for redeployment: 'production' (default) or 'test'", required = false,
                                       defaultValue = "production") String environment) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        try {
            if (agentId == null || agentId.isBlank())
                return errorJson("agentId is required");
            int ver = version != null ? version : 1;

            // Update descriptor (name/description) via REST
            if (name != null || description != null) {
                var descriptor = new DocumentDescriptor();
                if (name != null)
                    descriptor.setName(name);
                if (description != null)
                    descriptor.setDescription(description);

                var patch = new PatchInstruction<DocumentDescriptor>();
                patch.setOperation(PatchInstruction.PatchOperation.SET);
                patch.setDocument(descriptor);
                getRestStore(IRestDocumentDescriptorStore.class).patchDescriptor(agentId, ver, patch);
            }

            var result = new LinkedHashMap<String, Object>();
            result.put("agentId", agentId);
            result.put("version", ver);
            result.put("updated", true);

            // Redeploy if requested
            if (Boolean.TRUE.equals(redeploy)) {
                var env = parseEnvironment(environment);
                try {
                    Response response = agentAdmin.deployAgent(env, agentId, ver, true, true);
                    result.put("redeployed", response.getStatus() == 200);
                    result.put("environment", env.name());
                } catch (Exception deployError) {
                    result.put("redeployed", false);
                    result.put("deployError", "Redeployment failed: " + deployError.getMessage());
                }
            }

            return resultJson("updated", result);
        } catch (Exception e) {
            LOGGER.error("MCP update_agent failed for Agent " + agentId, e);
            return errorJson("Failed to update agent", e);
        }
    }

    @Tool(name = "read_workflow", description = "Read a workflow's full workflow configuration. "
            + "Returns the list of package extensions (parser, behavior, langchain, httpcalls, output, etc.) "
            + "with their types and resource URIs. Use this to understand what's inside an agent's workflow.")
    public String readWorkflow(@ToolArg(description = "Workflow ID (required)") String workflowId,
                               @ToolArg(description = "Version number (default: latest)", required = false) Integer version) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        if (workflowId == null || workflowId.isBlank())
            return errorJson("workflowId is required");
        try {
            int ver = version != null && version > 0 ? version : getRestStore(IRestWorkflowStore.class).getCurrentVersion(workflowId);
            WorkflowConfiguration config = getRestStore(IRestWorkflowStore.class).readWorkflow(workflowId, ver);
            if (config == null) {
                return errorJson("Workflow not found: " + workflowId + " version " + ver);
            }

            var result = new LinkedHashMap<String, Object>();
            result.put("workflowId", workflowId);
            result.put("version", ver);
            result.put("extensionCount", config.getWorkflowSteps().size());
            result.put("configuration", config);
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            LOGGER.error("MCP read_workflow failed for workflow " + workflowId, e);
            return errorJson("Failed to read workflow", e);
        }
    }

    @Tool(name = "read_resource", description = "Read any EDDI resource configuration by type and ID. "
            + "Supported types: 'behavior', 'langchain', 'httpcalls', 'mcpcalls', 'output', "
            + "'propertysetter', 'dictionaries', 'rag'. Returns the full configuration JSON.")
    public String readResource(
                               @ToolArg(description = "Resource type: 'behavior', 'langchain', 'httpcalls', 'mcpcalls', 'output', "
                                       + "'propertysetter', 'dictionaries', or 'rag' (required)") String resourceType,
                               @ToolArg(description = "Resource ID (required)") String resourceId,
                               @ToolArg(description = "Version number (default: latest)", required = false) Integer version) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        if (resourceType == null || resourceType.isBlank())
            return errorJson("resourceType is required");
        if (resourceId == null || resourceId.isBlank())
            return errorJson("resourceId is required");
        try {
            int ver = version != null && version > 0 ? version : currentVersionByType(resourceType.trim().toLowerCase(), resourceId);
            Object config = readResourceByType(resourceType.trim().toLowerCase(), resourceId, ver);
            if (config == null) {
                return errorJson("Resource not found: " + resourceType + "/" + resourceId + " version " + ver);
            }

            var result = new LinkedHashMap<String, Object>();
            result.put("resourceType", resourceType);
            result.put("resourceId", resourceId);
            result.put("version", ver);
            result.put("configuration", config);
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            LOGGER.error("MCP read_resource failed for " + resourceType + "/" + resourceId, e);
            return errorJson("Failed to read resource", e);
        }
    }

    /**
     * Dispatch resource read to the correct REST store based on type.
     * <p>
     * {@code "rag"} is readable here but deliberately absent from
     * {@link #updateResourceByType}, {@link #createResourceByType} and
     * {@link #deleteResourceByType}: a knowledge base is a workflow extension like
     * any other and an agent's configuration cannot be explained without it, but
     * authoring one — and ingesting into one — is a separate decision that has not
     * been taken. An unsupported type is rejected by name in each switch's default
     * branch, so the asymmetry surfaces as a clear refusal rather than a silent
     * write to the wrong store.
     */
    private Object readResourceByType(String type, String id, int version) {
        return switch (type) {
            case "behavior" -> getRestStore(IRestRuleSetStore.class).readRuleSet(id, version);
            case "langchain" -> getRestStore(IRestLlmStore.class).readLlm(id, version);
            case "httpcalls" -> getRestStore(IRestApiCallsStore.class).readApiCalls(id, version);
            case "mcpcalls" -> getRestStore(IRestMcpCallsStore.class).readMcpCalls(id, version);
            case "output" -> getRestStore(IRestOutputStore.class).readOutputSet(id, version, "", "", 0, 0);
            case "propertysetter" -> getRestStore(IRestPropertySetterStore.class).readPropertySetter(id, version);
            case "dictionaries" -> getRestStore(IRestDictionaryStore.class).readRegularDictionary(id, version, "", "", 0, 0);
            case "rag" -> getRestStore(IRestRagStore.class).readRag(id, version);
            default -> throw new IllegalArgumentException("Unknown resource type: " + type
                    + ". Supported: behavior, langchain, httpcalls, mcpcalls, output, propertysetter, dictionaries, rag");
        };
    }

    /**
     * The latest version of a resource, for the read tools whose {@code version}
     * argument defaults to "latest". Same type dispatch as
     * {@link #readResourceByType}.
     */
    private int currentVersionByType(String type, String id) {
        return switch (type) {
            case "behavior" -> getRestStore(IRestRuleSetStore.class).getCurrentVersion(id);
            case "langchain" -> getRestStore(IRestLlmStore.class).getCurrentVersion(id);
            case "httpcalls" -> getRestStore(IRestApiCallsStore.class).getCurrentVersion(id);
            case "mcpcalls" -> getRestStore(IRestMcpCallsStore.class).getCurrentVersion(id);
            case "output" -> getRestStore(IRestOutputStore.class).getCurrentVersion(id);
            case "propertysetter" -> getRestStore(IRestPropertySetterStore.class).getCurrentVersion(id);
            case "dictionaries" -> getRestStore(IRestDictionaryStore.class).getCurrentVersion(id);
            case "rag" -> getRestStore(IRestRagStore.class).getCurrentVersion(id);
            default -> throw new IllegalArgumentException("Unknown resource type: " + type
                    + ". Supported: behavior, langchain, httpcalls, mcpcalls, output, propertysetter, dictionaries, rag");
        };
    }

    // ==================== Phase 8a.2: Resource CRUD + Batch Cascade
    // ====================

    @Tool(name = "update_resource", description = "Update any EDDI resource configuration by type and ID. "
            + "Returns the new version URI after update. "
            + "Supported types: 'behavior', 'langchain', 'httpcalls', 'mcpcalls', 'output', 'propertysetter', 'dictionaries'.")
    public String updateResource(
                                 @ToolArg(description = "Resource type: 'behavior', 'langchain', 'httpcalls', 'mcpcalls', 'output', "
                                         + "'propertysetter', or 'dictionaries' (required)") String resourceType,
                                 @ToolArg(description = "Resource ID (required)") String resourceId,
                                 @ToolArg(description = "Current version number (required)") Integer version,
                                 @ToolArg(description = "Full JSON configuration body (required)") String config) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        if (resourceType == null || resourceType.isBlank())
            return errorJson("resourceType is required");
        if (resourceId == null || resourceId.isBlank())
            return errorJson("resourceId is required");
        if (config == null || config.isBlank())
            return errorJson("config is required");
        try {
            int ver = version != null ? version : 1;
            String type = resourceType.trim().toLowerCase();
            Response response = updateResourceByType(type, resourceId, ver, config);
            String location = response.getHeaderString("Location");
            int newVersion = extractVersionFromLocation(location);

            var result = new LinkedHashMap<String, Object>();
            result.put("resourceType", type);
            result.put("resourceId", resourceId);
            result.put("previousVersion", ver);
            result.put("newVersion", newVersion);
            if (location != null) {
                result.put("resourceUri", location);
            }
            result.put("status", response.getStatus());
            return resultJson("updated", result);
        } catch (Exception e) {
            LOGGER.error("MCP update_resource failed for " + resourceType + "/" + resourceId, e);
            return errorJson("Failed to update resource", e);
        }
    }

    @Tool(name = "create_resource", description = "Create a new EDDI resource configuration. " + "Returns the new resource ID and URI. "
            + "Supported types: 'behavior', 'langchain', 'httpcalls', 'mcpcalls', 'output', 'propertysetter', 'dictionaries'.")
    public String createResource(
                                 @ToolArg(description = "Resource type: 'behavior', 'langchain', 'httpcalls', 'mcpcalls', 'output', "
                                         + "'propertysetter', or 'dictionaries' (required)") String resourceType,
                                 @ToolArg(description = "Full JSON configuration body (required)") String config) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        if (resourceType == null || resourceType.isBlank())
            return errorJson("resourceType is required");
        if (config == null || config.isBlank())
            return errorJson("config is required");
        try {
            String type = resourceType.trim().toLowerCase();
            Response response = createResourceByType(type, config);
            String location = response.getHeaderString("Location");
            String newId = extractIdFromLocation(location);
            int newVersion = extractVersionFromLocation(location);

            var result = new LinkedHashMap<String, Object>();
            result.put("resourceType", type);
            result.put("resourceId", newId != null ? newId : "unknown");
            result.put("version", newVersion);
            if (location != null) {
                result.put("resourceUri", location);
            }
            result.put("status", response.getStatus());
            return resultJson("created", result);
        } catch (Exception e) {
            LOGGER.error("MCP create_resource failed for " + resourceType, e);
            return errorJson("Failed to create resource", e);
        }
    }

    @Tool(name = "delete_resource", description = "Delete an EDDI resource configuration. "
            + "Soft-deletes by default; set permanent=true for permanent removal. "
            + "Supported types: 'behavior', 'langchain', 'httpcalls', 'mcpcalls', 'output', 'propertysetter', 'dictionaries'.")
    public String deleteResource(
                                 @ToolArg(description = "Resource type: 'behavior', 'langchain', 'httpcalls', 'mcpcalls', 'output', "
                                         + "'propertysetter', or 'dictionaries' (required)") String resourceType,
                                 @ToolArg(description = "Resource ID (required)") String resourceId,
                                 @ToolArg(description = "Current version number (required)") Integer version,
                                 @ToolArg(description = "Permanently delete? (default: false)", required = false,
                                          defaultValue = "false") Boolean permanent) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        if (resourceType == null || resourceType.isBlank())
            return errorJson("resourceType is required");
        if (resourceId == null || resourceId.isBlank())
            return errorJson("resourceId is required");
        try {
            int ver = version != null ? version : 1;
            boolean isPermanent = permanent != null ? permanent : false;
            String type = resourceType.trim().toLowerCase();
            Response response = deleteResourceByType(type, resourceId, ver, isPermanent);

            return resultJson("deleted",
                    Map.of("resourceType", type, "resourceId", resourceId, "version", ver, "permanent", isPermanent, "status", response.getStatus()));
        } catch (Exception e) {
            LOGGER.error("MCP delete_resource failed for " + resourceType + "/" + resourceId, e);
            return errorJson("Failed to delete resource", e);
        }
    }

    @Tool(name = "apply_agent_changes", description = "Batch-cascade multiple resource URI changes through "
            + "package → Agent in ONE pass. After updating resources with update_resource, use this to wire "
            + "the new versions into the agent's packages and optionally redeploy. "
            + "This replaces ALL URIs in-memory and saves each package/agent ONCE — no wasteful intermediate versions. "
            + "Each oldUri must be a reference the agent actually holds (a workflow step's resource or the workflow itself), "
            + "and newUri the same kind of resource with a version; otherwise nothing is written. Shared with the REST "
            + "endpoint POST /agentstore/agents/{id}/cascade.")
    public String applyAgentChanges(@ToolArg(description = "Agent ID (required)") String agentId,
                                    @ToolArg(description = "Current Agent version (required)") Integer agentVersion,
                                    @ToolArg(description = "JSON array of URI mappings (required): [{\"oldUri\":\"eddi://...?version=1\",\"newUri\":\"eddi://...?version=2\"}, ...]") String resourceMappings,
                                    @ToolArg(description = "Redeploy the Agent after cascading changes? (default: false)", required = false,
                                             defaultValue = "false") Boolean redeploy,
                                    @ToolArg(description = "Environment for redeployment: 'production' (default) or 'test'", required = false,
                                             defaultValue = "production") String environment,
                                    @ToolArg(description = "Is the new Agent version compatible with the one it replaces? true = conversations already running switch to it on their next turn once it is deployed. Only for changes that cannot break a conversation in progress, e.g. prompt wording or a different model. false (the default) = a breaking change: running conversations stay on their version. Choose false if properties or actions were renamed or removed, or if the new version needs conversation state older conversations do not have.",
                                             required = false, defaultValue = "false") Boolean compatible) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        if (agentId == null || agentId.isBlank())
            return errorJson("agentId is required");
        if (resourceMappings == null || resourceMappings.isBlank())
            return errorJson("resourceMappings is required");
        try {
            int ver = agentVersion != null ? agentVersion : 1;

            @SuppressWarnings("unchecked")
            List<Map<String, String>> parsed = jsonSerialization.deserialize(resourceMappings, List.class);
            if (parsed == null || parsed.isEmpty()) {
                return resultJson("no_changes", Map.of("agentId", agentId, "reason", "Empty resource mappings"));
            }
            List<AgentCascadeRequest.ResourceMapping> mappings = new ArrayList<>();
            for (Map<String, String> mapping : parsed) {
                mappings.add(new AgentCascadeRequest.ResourceMapping(mapping.get("oldUri"), mapping.get("newUri")));
            }

            // The cascade itself lives in AgentCascadeService, shared with
            // POST /agentstore/agents/{id}/cascade so the two cannot drift.
            var result = cascadeService.cascade(agentId, ver, mappings, Boolean.TRUE.equals(redeploy), parseEnvironment(environment),
                    Boolean.TRUE.equals(compatible));
            return resultJson("cascaded", result.toMap());
        } catch (AgentCascadeService.CascadeException e) {
            return errorJson(e.getMessage());
        } catch (Exception e) {
            LOGGER.error("MCP apply_agent_changes failed for Agent " + agentId, e);
            return errorJson("Failed to apply Agent changes", e);
        }
    }

    @Tool(name = "list_agent_resources", description = "Get a complete inventory of all resources in an agent's workflow. "
            + "Walks Agent → packages → extensions and returns a flat summary with all resource IDs, types, and URIs. "
            + "This is the fastest way to understand an agent's full configuration before making changes.")
    public String listAgentResources(@ToolArg(description = "Agent ID (required)") String agentId,
                                     @ToolArg(description = "Agent version (default: latest)", required = false) Integer version) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        if (agentId == null || agentId.isBlank())
            return errorJson("agentId is required");
        try {
            int ver = version != null && version > 0 ? version : getRestStore(IRestAgentStore.class).getCurrentVersion(agentId);

            // Read Agent config
            var localAgentStore = getRestStore(IRestAgentStore.class);
            AgentConfiguration agentConfig = localAgentStore.readAgent(agentId, ver);
            if (agentConfig == null) {
                return errorJson("Agent not found: " + agentId + " version " + ver);
            }

            // Read Agent name from descriptor
            String agentName = null;
            try {
                DocumentDescriptor descriptor = getRestStore(IRestDocumentDescriptorStore.class).readDescriptor(agentId, ver);
                if (descriptor != null) {
                    agentName = descriptor.getName();
                }
            } catch (Exception e) {
                LOGGER.debug("Could not read Agent descriptor for " + agentId, e);
            }

            // Walk workflows → extensions
            var workflowStore = getRestStore(IRestWorkflowStore.class);
            var workflows = new ArrayList<Map<String, Object>>();

            for (URI wfUri : agentConfig.getWorkflows()) {
                String wfId = extractIdFromLocation(wfUri.toString());
                int wfVersion = extractVersionFromLocation(wfUri.toString());
                if (wfId == null)
                    continue;

                var wfInfo = new LinkedHashMap<String, Object>();
                wfInfo.put("workflowId", wfId);
                wfInfo.put("workflowVersion", wfVersion);
                wfInfo.put("workflowUri", wfUri.toString());

                try {
                    WorkflowConfiguration wfConfig = workflowStore.readWorkflow(wfId, wfVersion);
                    if (wfConfig != null) {
                        var extensions = new ArrayList<Map<String, Object>>();
                        for (var ext : wfConfig.getWorkflowSteps()) {
                            var extInfo = new LinkedHashMap<String, Object>();
                            if (ext.getType() != null) {
                                extInfo.put("type", ext.getType().toString());
                                extInfo.put("resourceType", uriToResourceType(ext.getType().toString()));
                            }
                            Object uriObj = ext.getConfig().get("uri");
                            if (uriObj != null) {
                                String uri = uriObj.toString();
                                extInfo.put("resourceUri", uri);
                                extInfo.put("resourceId", extractIdFromLocation(uri));
                                extInfo.put("resourceVersion", extractVersionFromLocation(uri));
                            }
                            extensions.add(extInfo);
                        }
                        wfInfo.put("extensions", extensions);
                        wfInfo.put("extensionCount", extensions.size());
                    }
                } catch (Exception wfErr) {
                    wfInfo.put("error", "Failed to read workflow: " + wfErr.getMessage());
                }

                workflows.add(wfInfo);
            }

            var result = new LinkedHashMap<String, Object>();
            result.put("agentId", agentId);
            result.put("agentVersion", ver);
            if (agentName != null)
                result.put("agentName", agentName);
            result.put("workflowCount", workflows.size());
            result.put("workflows", workflows);
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            LOGGER.error("MCP list_agent_resources failed for Agent " + agentId, e);
            return errorJson("Failed to list Agent resources", e);
        }
    }

    // ==================== Type Dispatch Helpers ====================

    /**
     * Dispatch resource update to the correct REST store based on type.
     */
    private Response updateResourceByType(String type, String id, int version, String configJson) throws IOException {
        return switch (type) {
            case "behavior" -> getRestStore(IRestRuleSetStore.class).updateRuleSet(id, version,
                    configParser.parse(configJson, RuleSetConfiguration.class));
            case "langchain" ->
                getRestStore(IRestLlmStore.class).updateLlm(id, version, configParser.parse(configJson, LlmConfiguration.class));
            case "httpcalls" -> getRestStore(IRestApiCallsStore.class).updateApiCalls(id, version,
                    configParser.parse(configJson, ApiCallsConfiguration.class));
            case "mcpcalls" -> getRestStore(IRestMcpCallsStore.class).updateMcpCalls(id, version,
                    configParser.parse(configJson, McpCallsConfiguration.class));
            case "output" -> getRestStore(IRestOutputStore.class).updateOutputSet(id, version,
                    configParser.parse(configJson, OutputConfigurationSet.class));
            case "propertysetter" -> getRestStore(IRestPropertySetterStore.class).updatePropertySetter(id, version,
                    configParser.parse(configJson, PropertySetterConfiguration.class));
            case "dictionaries" -> getRestStore(IRestDictionaryStore.class).updateRegularDictionary(id, version,
                    configParser.parse(configJson, DictionaryConfiguration.class));
            default -> throw new IllegalArgumentException(
                    "Unknown resource type: " + type + ". Supported: behavior, langchain, httpcalls, mcpcalls, output, propertysetter, dictionaries");
        };
    }

    /**
     * Dispatch resource create to the correct REST store based on type.
     */
    private Response createResourceByType(String type, String configJson) throws IOException {
        return switch (type) {
            case "behavior" ->
                getRestStore(IRestRuleSetStore.class).createRuleSet(configParser.parse(configJson, RuleSetConfiguration.class));
            case "langchain" -> getRestStore(IRestLlmStore.class).createLlm(configParser.parse(configJson, LlmConfiguration.class));
            case "httpcalls" ->
                getRestStore(IRestApiCallsStore.class).createApiCalls(configParser.parse(configJson, ApiCallsConfiguration.class));
            case "mcpcalls" ->
                getRestStore(IRestMcpCallsStore.class).createMcpCalls(configParser.parse(configJson, McpCallsConfiguration.class));
            case "output" ->
                getRestStore(IRestOutputStore.class).createOutputSet(configParser.parse(configJson, OutputConfigurationSet.class));
            case "propertysetter" -> getRestStore(IRestPropertySetterStore.class)
                    .createPropertySetter(configParser.parse(configJson, PropertySetterConfiguration.class));
            case "dictionaries" -> getRestStore(IRestDictionaryStore.class)
                    .createRegularDictionary(configParser.parse(configJson, DictionaryConfiguration.class));
            default -> throw new IllegalArgumentException(
                    "Unknown resource type: " + type + ". Supported: behavior, langchain, httpcalls, mcpcalls, output, propertysetter, dictionaries");
        };
    }

    /**
     * Dispatch resource delete to the correct REST store based on type.
     */
    private Response deleteResourceByType(String type, String id, int version, boolean permanent) {
        return switch (type) {
            case "behavior" -> getRestStore(IRestRuleSetStore.class).deleteRuleSet(id, version, permanent);
            case "langchain" -> getRestStore(IRestLlmStore.class).deleteLlm(id, version, permanent);
            case "httpcalls" -> getRestStore(IRestApiCallsStore.class).deleteApiCalls(id, version, permanent);
            case "mcpcalls" -> getRestStore(IRestMcpCallsStore.class).deleteMcpCalls(id, version, permanent);
            case "output" -> getRestStore(IRestOutputStore.class).deleteOutputSet(id, version, permanent);
            case "propertysetter" -> getRestStore(IRestPropertySetterStore.class).deletePropertySetter(id, version, permanent);
            case "dictionaries" -> getRestStore(IRestDictionaryStore.class).deleteRegularDictionary(id, version, permanent);
            default -> throw new IllegalArgumentException(
                    "Unknown resource type: " + type + ". Supported: behavior, langchain, httpcalls, mcpcalls, output, propertysetter, dictionaries");
        };
    }

    /**
     * Workflow step type (the authority of its {@code eddi://} URI) → the resource
     * type slug {@code read_resource}/{@code update_resource} accept. Both the v6
     * names and their legacy aliases are listed.
     */
    private static final Map<String, String> STEP_TYPE_TO_RESOURCE_TYPE = Map.ofEntries(
            Map.entry("ai.labs.rules", "behavior"), Map.entry("ai.labs.behavior", "behavior"),
            Map.entry("ai.labs.llm", "langchain"), Map.entry("ai.labs.langchain", "langchain"),
            Map.entry("ai.labs.apicalls", "httpcalls"), Map.entry("ai.labs.httpcalls", "httpcalls"),
            Map.entry("ai.labs.mcpcalls", "mcpcalls"),
            Map.entry("ai.labs.output", "output"),
            Map.entry("ai.labs.property", "propertysetter"),
            Map.entry("ai.labs.dictionary", "dictionaries"), Map.entry("ai.labs.parser", "dictionaries"),
            Map.entry("ai.labs.rag", "rag"));

    /**
     * Map a workflow extension type URI to the MCP resource type slug. E.g.,
     * "eddi://ai.labs.rules" → "behavior".
     * <p>
     * An exact lookup on the step type. The substring matching this replaced missed
     * every v6 name ({@code ai.labs.rules}, {@code ai.labs.llm},
     * {@code ai.labs.apicalls}), so {@code list_agent_resources} labelled the
     * resources of every newly created agent "unknown".
     */
    static String uriToResourceType(String typeUri) {
        if (typeUri == null)
            return "unknown";
        String stepType = typeUri.startsWith("eddi://") ? typeUri.substring("eddi://".length()) : typeUri;
        int end = stepType.indexOf('/');
        if (end >= 0) {
            stepType = stepType.substring(0, end);
        }
        return STEP_TYPE_TO_RESOURCE_TYPE.getOrDefault(stepType, "unknown");
    }

    private String resultJson(String action, Map<String, Object> data) {
        try {
            var result = new LinkedHashMap<>(data);
            result.put("action", action);
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            return "{\"action\":\"" + escapeJsonString(action) + "\",\"status\":\"completed\"}";
        }
    }

    /**
     * Get a REST interface proxy. Delegates to McpToolUtils.getRestStore().
     */
    private <T> T getRestStore(Class<T> clazz) {
        return McpToolUtils.getRestStore(restInterfaceFactory, clazz);
    }

    // ── Agent Trigger CRUD ──────────────────────────────────────────────────

    @Tool(name = "list_agent_triggers", description = "List all Agent triggers (intent→agent mappings). "
            + "Returns all configured intents with their Agent deployments. "
            + "Agent triggers enable intent-based conversation management via chat_managed.")
    public String listAgentTriggers() {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        try {
            var triggerStore = getRestStore(IRestAgentTriggerStore.class);
            List<AgentTriggerConfiguration> triggers = triggerStore.readAllAgentTriggers();

            var result = new LinkedHashMap<String, Object>();
            result.put("count", triggers.size());
            result.put("triggers", triggers);
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            LOGGER.error("MCP list_agent_triggers failed", e);
            return errorJson("Failed to list Agent triggers", e);
        }
    }

    @Tool(name = "create_agent_trigger", description = "Create a Agent trigger that maps an intent to one or more agents. "
            + "Once created, the intent can be used with chat_managed to talk to the agent. "
            + "The config must include: intent (string) and agentDeployments (array of {agentId, environment}).")
    public String createAgentTrigger(@ToolArg(description = "Full JSON configuration: "
            + "{\"intent\":\"...\",\"agentDeployments\":[{\"agentId\":\"...\",\"environment\":\"production\"}]} (required)") String config) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        if (config == null || config.isBlank())
            return errorJson("config is required");
        try {
            var triggerStore = getRestStore(IRestAgentTriggerStore.class);
            AgentTriggerConfiguration triggerConfig = jsonSerialization.deserialize(config, AgentTriggerConfiguration.class);

            if (triggerConfig.getIntent() == null || triggerConfig.getIntent().isBlank()) {
                return errorJson("intent is required in config");
            }

            Response response = triggerStore.createAgentTrigger(triggerConfig);

            var result = new LinkedHashMap<String, Object>();
            result.put("intent", triggerConfig.getIntent());
            result.put("status", response.getStatus());
            return resultJson("created", result);
        } catch (Exception e) {
            LOGGER.error("MCP create_agent_trigger failed", e);
            return errorJson("Failed to create Agent trigger", e);
        }
    }

    @Tool(name = "update_agent_trigger", description = "Update an existing Agent trigger. " + "Changes the Agent deployments for a given intent.")
    public String updateAgentTrigger(@ToolArg(description = "Intent to update (required)") String intent,
                                     @ToolArg(description = "Full JSON configuration: "
                                             + "{\"intent\":\"...\",\"agentDeployments\":"
                                             + "[{\"agentId\":\"...\",\"environment\":"
                                             + "\"production\"}]} (required)") String config) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        if (intent == null || intent.isBlank())
            return errorJson("intent is required");
        if (config == null || config.isBlank())
            return errorJson("config is required");
        try {
            var triggerStore = getRestStore(IRestAgentTriggerStore.class);
            AgentTriggerConfiguration triggerConfig = jsonSerialization.deserialize(config, AgentTriggerConfiguration.class);
            Response response = triggerStore.updateAgentTrigger(intent, triggerConfig);

            var result = new LinkedHashMap<String, Object>();
            result.put("intent", intent);
            result.put("status", response.getStatus());
            return resultJson("updated", result);
        } catch (Exception e) {
            LOGGER.error("MCP update_agent_trigger failed for intent " + intent, e);
            return errorJson("Failed to update Agent trigger", e);
        }
    }

    @Tool(name = "delete_agent_trigger", description = "Delete a Agent trigger for a given intent.")
    public String deleteAgentTrigger(@ToolArg(description = "Intent to delete (required)") String intent) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        if (intent == null || intent.isBlank())
            return errorJson("intent is required");
        try {
            var triggerStore = getRestStore(IRestAgentTriggerStore.class);
            Response response = triggerStore.deleteAgentTrigger(intent);

            var result = new LinkedHashMap<String, Object>();
            result.put("intent", intent);
            result.put("status", response.getStatus());
            return resultJson("deleted", result);
        } catch (Exception e) {
            LOGGER.error("MCP delete_agent_trigger failed for intent " + intent, e);
            return errorJson("Failed to delete Agent trigger", e);
        }
    }

    // ── Schedule Management ───────────────────────────────────────────────

    @Tool(name = "create_schedule", description = "Create a new scheduled Agent trigger (cron job or heartbeat). "
            + "For CRON: provide cron (a cron expression). For HEARTBEAT: provide heartbeatIntervalSeconds. "
            + "Returns the created schedule with human-readable description and next fire time.")
    public String createSchedule(@ToolArg(description = "Agent ID to trigger (required)") String agentId,
                                 @ToolArg(description = "Trigger type: 'CRON' or 'HEARTBEAT' (default: CRON, or HEARTBEAT when only heartbeatIntervalSeconds is given)",
                                          required = false) String triggerType,
                                 @ToolArg(description = "5-field cron expression, e.g. '0 9 * * MON-FRI' (optional; required for CRON)",
                                          required = false) String cron,
                                 @ToolArg(description = "Heartbeat interval in seconds, e.g. 300 for 5 min (optional; required for HEARTBEAT)",
                                          required = false) Long heartbeatIntervalSeconds,
                                 @ToolArg(description = "Message text to send to the Agent on each fire (optional; required for CRON, defaults to 'heartbeat' for HEARTBEAT)",
                                          required = false) String message,
                                 @ToolArg(description = "Human-readable name for this schedule (required)") String name,
                                 @ToolArg(description = "IANA time zone, e.g. 'Europe/Vienna' (default: UTC)", required = false,
                                          defaultValue = "UTC") String timeZone,
                                 @ToolArg(description = "Conversation strategy: 'new' or 'persistent' (default: 'new' for CRON, 'persistent' for HEARTBEAT)",
                                          required = false) String conversationStrategy,
                                 @ToolArg(description = "User identity for the scheduled message (default: 'system:scheduler')", required = false,
                                          defaultValue = "system:scheduler") String userId,
                                 @ToolArg(description = "Environment: 'production' (default) or 'test'", required = false,
                                          defaultValue = "production") String environment) {
        requireAnyRole(identity, authEnabled, McpRoles.ADMIN_ONLY);
        if (agentId == null || agentId.isBlank())
            return errorJson("agentId is required");
        if (name == null || name.isBlank())
            return errorJson("name is required");
        try {
            // Determine trigger type
            ScheduleConfiguration.TriggerType type = ScheduleConfiguration.TriggerType.CRON;
            if (triggerType != null && !triggerType.isBlank()) {
                type = ScheduleConfiguration.TriggerType.valueOf(triggerType.toUpperCase());
            } else if (heartbeatIntervalSeconds != null) {
                type = ScheduleConfiguration.TriggerType.HEARTBEAT;
            }

            // Validate based on type
            if (type == ScheduleConfiguration.TriggerType.CRON) {
                if (cron == null || cron.isBlank())
                    return errorJson("cron expression is required for CRON triggers");
                if (message == null || message.isBlank())
                    return errorJson("message is required for CRON triggers");
                CronParser.validate(cron);
            } else {
                if (heartbeatIntervalSeconds == null || heartbeatIntervalSeconds <= 0) {
                    return errorJson("heartbeatIntervalSeconds is required and must be > 0 for HEARTBEAT triggers");
                }
            }

            var schedule = new ScheduleConfiguration();
            schedule.setName(name);
            schedule.setAgentId(agentId);
            schedule.setTriggerType(type);
            schedule.setCronExpression(cron);
            schedule.setHeartbeatIntervalSeconds(heartbeatIntervalSeconds);
            schedule.setMessage(
                    message != null && !message.isBlank() ? message : (type == ScheduleConfiguration.TriggerType.HEARTBEAT ? "heartbeat" : null));
            schedule.setEnvironment(environment != null && !environment.isBlank() ? environment : "production");
            schedule.setConversationStrategy(conversationStrategy != null && !conversationStrategy.isBlank()
                    ? conversationStrategy
                    : (type == ScheduleConfiguration.TriggerType.HEARTBEAT ? "persistent" : "new"));
            schedule.setTimeZone(timeZone != null && !timeZone.isBlank() ? timeZone : "UTC");
            schedule.setUserId(userId != null && !userId.isBlank() ? userId : "system:scheduler");
            schedule.setFireStatus(ScheduleConfiguration.FireStatus.PENDING);
            schedule.setEnabled(true);

            // Compute initial nextFire
            Instant nextFire;
            String description;
            if (type == ScheduleConfiguration.TriggerType.HEARTBEAT) {
                long sec = heartbeatIntervalSeconds != null ? heartbeatIntervalSeconds : 300L;
                nextFire = Instant.now().plusSeconds(sec);
                description = sec < 60
                        ? "Every " + sec + " seconds"
                        : sec < 3600 ? "Every " + (sec / 60) + " minutes" : "Every " + (sec / 3600) + " hours";
            } else {
                ZoneId zone = ZoneId.of(schedule.getTimeZone());
                nextFire = CronParser.computeNextFire(cron, Instant.now(), zone);
                description = CronDescriber.describe(cron);
            }
            schedule.setNextFire(nextFire);

            String id = scheduleStore.createSchedule(schedule);

            var result = new LinkedHashMap<String, Object>();
            result.put("scheduleId", id);
            result.put("name", name);
            result.put("triggerType", type.name());
            result.put("agentId", agentId);
            if (cron != null)
                result.put("cronExpression", cron);
            if (heartbeatIntervalSeconds != null)
                result.put("heartbeatIntervalSeconds", heartbeatIntervalSeconds);
            result.put("description", description);
            result.put("timeZone", schedule.getTimeZone());
            result.put("nextFire", nextFire.toString());
            result.put("conversationStrategy", schedule.getConversationStrategy());
            result.put("environment", schedule.getEnvironment());
            return resultJson("schedule_created", result);
        } catch (IllegalArgumentException e) {
            return errorJson("Invalid schedule", e);
        } catch (Exception e) {
            LOGGER.error("MCP create_schedule failed", e);
            return errorJson("Failed to create schedule", e);
        }
    }

    @Tool(name = "list_schedules", description = "List all scheduled Agent triggers. "
            + "Returns schedules with name, type, agent, cron/interval, status, next fire time, and fire count. " + "Optionally filter by agentId.")
    public String listSchedules(@ToolArg(description = "Filter by Agent ID (optional)", required = false) String agentId) {
        requireAnyRole(identity, authEnabled, McpRoles.ADMIN_ONLY);
        try {
            List<ScheduleConfiguration> schedules;
            if (agentId != null && !agentId.isBlank()) {
                schedules = scheduleStore.readSchedulesByAgentId(agentId);
            } else {
                schedules = scheduleStore.readAllSchedules(100);
            }

            // Build enriched response
            var items = new ArrayList<Map<String, Object>>();
            for (var s : schedules) {
                var item = new LinkedHashMap<String, Object>();
                item.put("scheduleId", s.getId());
                item.put("name", s.getName());
                item.put("triggerType", s.getTriggerType() != null ? s.getTriggerType().name() : "CRON");
                item.put("agentId", s.getAgentId());
                if (s.getCronExpression() != null) {
                    item.put("cronExpression", s.getCronExpression());
                    item.put("cronDescription", CronDescriber.describe(s.getCronExpression()));
                }
                if (s.getHeartbeatIntervalSeconds() != null) {
                    item.put("heartbeatIntervalSeconds", s.getHeartbeatIntervalSeconds());
                }
                item.put("enabled", s.isEnabled());
                item.put("fireStatus", s.getFireStatus() != null ? s.getFireStatus().name() : null);
                item.put("nextFire", s.getNextFire() != null ? s.getNextFire().toString() : null);
                item.put("lastFired", s.getLastFired() != null ? s.getLastFired().toString() : null);
                item.put("failCount", s.getFailCount());
                item.put("conversationStrategy", s.getConversationStrategy());
                items.add(item);
            }

            var result = new LinkedHashMap<String, Object>();
            result.put("count", items.size());
            result.put("schedules", items);
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            LOGGER.error("MCP list_schedules failed", e);
            return errorJson("Failed to list schedules", e);
        }
    }

    @Tool(name = "read_schedule", description = "Read a schedule's full configuration including fire history. "
            + "Returns the schedule config, human-readable description, and recent fire logs.")
    public String readSchedule(@ToolArg(description = "Schedule ID (required)") String scheduleId) {
        requireAnyRole(identity, authEnabled, McpRoles.ADMIN_ONLY);
        if (scheduleId == null || scheduleId.isBlank())
            return errorJson("scheduleId is required");
        try {
            var schedule = scheduleStore.readSchedule(scheduleId);
            var fireLogs = scheduleStore.readFireLogs(scheduleId, 10);

            var result = new LinkedHashMap<String, Object>();
            result.put("scheduleId", schedule.getId());
            result.put("name", schedule.getName());
            result.put("triggerType", schedule.getTriggerType() != null ? schedule.getTriggerType().name() : "CRON");
            result.put("agentId", schedule.getAgentId());
            if (schedule.getCronExpression() != null) {
                result.put("cronExpression", schedule.getCronExpression());
                result.put("cronDescription", CronDescriber.describe(schedule.getCronExpression()));
            }
            if (schedule.getHeartbeatIntervalSeconds() != null) {
                result.put("heartbeatIntervalSeconds", schedule.getHeartbeatIntervalSeconds());
            }
            result.put("timeZone", schedule.getTimeZone());
            result.put("message", schedule.getMessage());
            result.put("enabled", schedule.isEnabled());
            result.put("fireStatus", schedule.getFireStatus() != null ? schedule.getFireStatus().name() : null);
            result.put("nextFire", schedule.getNextFire() != null ? schedule.getNextFire().toString() : null);
            result.put("lastFired", schedule.getLastFired() != null ? schedule.getLastFired().toString() : null);
            result.put("failCount", schedule.getFailCount());
            result.put("conversationStrategy", schedule.getConversationStrategy());
            result.put("environment", schedule.getEnvironment());
            result.put("maxCostPerFire", schedule.getMaxCostPerFire());
            result.put("recentFires", fireLogs);
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            LOGGER.error("MCP read_schedule failed for " + scheduleId, e);
            return errorJson("Failed to read schedule", e);
        }
    }

    @Tool(name = "delete_schedule", description = "Delete a scheduled Agent trigger.")
    public String deleteSchedule(@ToolArg(description = "Schedule ID (required)") String scheduleId) {
        requireAnyRole(identity, authEnabled, McpRoles.ADMIN_ONLY);
        if (scheduleId == null || scheduleId.isBlank())
            return errorJson("scheduleId is required");
        try {
            // The alerts-maintenance schedule is the server's own; REST refuses this too.
            if (RestScheduleStore.isSystemSchedule(scheduleStore.readSchedule(scheduleId))) {
                return errorJson("This schedule is managed by the server (alert retries, digests and retention) and cannot be deleted.",
                        RestScheduleStore.SYSTEM_SCHEDULE, null);
            }
            scheduleStore.deleteSchedule(scheduleId);
            return resultJson("schedule_deleted", Map.of("scheduleId", scheduleId));
        } catch (Exception e) {
            LOGGER.error("MCP delete_schedule failed for " + scheduleId, e);
            return errorJson("Failed to delete schedule", e);
        }
    }

    @Tool(name = "fire_schedule_now", description = "Manually trigger a schedule fire immediately. "
            + "Useful for testing or one-off executions. Returns the fire result with conversation ID.")
    public String fireScheduleNow(@ToolArg(description = "Schedule ID (required)") String scheduleId) {
        requireAnyRole(identity, authEnabled, McpRoles.ADMIN_ONLY);
        if (scheduleId == null || scheduleId.isBlank())
            return errorJson("scheduleId is required");
        try {
            var schedule = scheduleStore.readSchedule(scheduleId);
            // A HITL approval timeout fires the configured AUTO_APPROVE/AUTO_REJECT/
            // ABORT decision with a system actor and no owner/admin/approver check, so
            // REST refuses to fire one for EVERYONE. This tool must refuse too, or it
            // is simply the same bypass with a different front door.
            if (HitlSchedules.isHitlTimeout(schedule.getMetadata())) {
                return errorJson("This schedule is a human-in-the-loop approval timeout and cannot be fired manually. "
                        + "Resolve the pending approval via the conversation's resume or cancel endpoint.");
            }
            if (ConversationContinuations.isContinuationSchedule(schedule.getMetadata())) {
                return errorJson("This schedule is the next turn of a self-continuing conversation and cannot be fired manually. "
                        + "It runs on its own; end or cancel the conversation to stop it.");
            }
            // Claim it on the poller's own terms first, and release the claim in the
            // finally. Firing unclaimed raced the poller — with
            // conversationStrategy=persistent both pushed a turn into the SAME
            // conversation — and skipping recordManualFireOutcome meant the fire never
            // reached the retry/backoff/one-shot state machine at all: a failure here
            // did not increment failCount, and a success did not re-arm the schedule.
            if (!schedulePollerService.claimForManualFire(schedule)) {
                return errorJson("Schedule " + scheduleId + " is not in a claimable state (fireStatus="
                        + schedule.getFireStatus() + "); it may be firing already, dead-lettered, or still in retry backoff.");
            }
            ScheduleFireLog fireLog = null;
            try {
                // The attempt this actually is, not a constant 1 — a manual retry of a
                // schedule on its third failure logged as "attempt 1" and hid the history.
                fireLog = scheduleFireExecutor.fire(schedule, schedulePollerService.getInstanceId(), schedule.getFailCount() + 1);
            } finally {
                // Always release the claim, whatever happened: a manual fire that left
                // the row CLAIMED would block the poller until the lease expired. A null
                // fireLog (the executor threw) is recorded as a failure, which is what it
                // is.
                //
                // Park the interrupt across that write, exactly as
                // RestScheduleStore.fireNow and SchedulePollerService.fireClaimedSchedule
                // do. ScheduleFireExecutor re-asserts a consumed interrupt before
                // returning, so on the interrupted path (shutdown, a cancelled tool
                // invocation) this block would otherwise run with the flag set — and the
                // synchronous Mongo driver throws MongoInterruptedException on connection
                // checkout. recordManualFireOutcome swallows that, so the claim would
                // never be released and failCount never incremented, leaving the schedule
                // CLAIMED until its lease expires. The flag is re-asserted immediately
                // afterwards so the cancellation signal still reaches the caller.
                boolean wasInterrupted = Thread.interrupted();
                try {
                    schedulePollerService.recordManualFireOutcome(schedule, fireLog);
                } finally {
                    if (wasInterrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            var result = new LinkedHashMap<String, Object>();
            result.put("scheduleId", scheduleId);
            result.put("scheduleName", schedule.getName());
            result.put("fireStatus", fireLog.status());
            result.put("conversationId", fireLog.conversationId());
            result.put("duration",
                    fireLog.completedAt() != null && fireLog.startedAt() != null
                            ? (fireLog.completedAt().toEpochMilli() - fireLog.startedAt().toEpochMilli()) + "ms"
                            : null);
            if (fireLog.errorMessage() != null) {
                result.put("error", fireLog.errorMessage());
            }
            return resultJson("schedule_fired", result);
        } catch (Exception e) {
            LOGGER.error("MCP fire_schedule_now failed for " + scheduleId, e);
            return errorJson("Failed to fire schedule", e);
        }
    }

    @Tool(name = "retry_failed_schedule", description = "Re-queue a dead-lettered schedule for another fire attempt. "
            + "Use after investigating and fixing the cause of failure.")
    public String retryFailedSchedule(@ToolArg(description = "Schedule ID (required)") String scheduleId) {
        requireAnyRole(identity, authEnabled, McpRoles.ADMIN_ONLY);
        if (scheduleId == null || scheduleId.isBlank())
            return errorJson("scheduleId is required");
        try {
            scheduleStore.requeueDeadLetter(scheduleId);
            return resultJson("schedule_requeued",
                    Map.of("scheduleId", scheduleId, "status", "PENDING", "message", "Schedule requeued for next poll cycle"));
        } catch (Exception e) {
            LOGGER.error("MCP retry_failed_schedule failed for " + scheduleId, e);
            return errorJson("Failed to retry schedule", e);
        }
    }

    // ==================== Channel Integration Tools ====================

    @Tool(name = "list_channel_integrations", description = "List all channel integration configurations (Slack, signed "
            + "inbound webhooks and email mailboxes). Returns descriptors with name, channelType, and target count.")
    public String listChannelIntegrations(
                                          @ToolArg(description = "Filter string (optional)", required = false) String filter,
                                          @ToolArg(description = "Maximum number of results (default: 20)", required = false,
                                                   defaultValue = "20") Integer limit) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        try {
            int limitInt = limit != null ? limit : 20;
            String filterStr = filter != null ? filter : "";
            var channelStore = getRestStore(
                    IRestChannelIntegrationStore.class);
            var descriptors = channelStore.readChannelDescriptors(filterStr, 0, limitInt);
            return jsonSerialization.serialize(descriptors);
        } catch (Exception e) {
            LOGGER.error("MCP list_channel_integrations failed", e);
            return errorJson("Failed to list channel integrations", e);
        }
    }

    @Tool(name = "read_channel_integration", description = "Read a channel integration configuration by ID. "
            + "Returns the full config with targets, triggers, platformConfig, and observe mode settings.")
    public String readChannelIntegration(
                                         @ToolArg(description = "Channel integration resource ID (required)") String resourceId,
                                         @ToolArg(description = "Version number (default: latest)", required = false) Integer version) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        if (resourceId == null || resourceId.isBlank())
            return errorJson("resourceId is required");
        try {
            var channelStore = getRestStore(
                    IRestChannelIntegrationStore.class);
            int ver = version != null && version > 0 ? version : channelStore.getCurrentVersion(resourceId);
            var config = channelStore.readChannel(resourceId, ver);

            var result = new LinkedHashMap<String, Object>();
            result.put("resourceId", resourceId);
            result.put("version", ver);
            result.put("configuration", config);
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            LOGGER.error("MCP read_channel_integration failed for " + resourceId, e);
            return errorJson("Failed to read channel integration", e);
        }
    }

    @Tool(name = "create_channel_integration", description = "Create a new channel integration configuration. "
            + "Requires JSON body with name, channelType, platformConfig, targets[], and defaultTargetName. "
            + "channelType is 'slack' (platformConfig: channelId, botToken, signingSecret) or 'webhook' — a signed inbound "
            + "webhook (CloudEvents or plain JSON over HTTP) whose platformConfig.signingSecret must be a ${vault:...} reference; "
            + "optional keys: signatureScheme (eddi-v1 default | github | generic-hmac), signatureHeader, signatureEncoding, "
            + "signaturePrefix, eventTypeHeader, eventTypePath, eventIdHeader, inputTemplate, conversationStrategy (new | correlate), "
            + "correlationKeyPath. For webhook, targets[].triggers are event types (exact, or a 'prefix*'). "
            + "Or 'email' — trigger mode only: EDDI polls an IMAP mailbox and every new mail starts an agent turn; nothing is ever sent "
            + "back to the sender. platformConfig: mode 'trigger' (required; 'conversation' is not supported yet), transport 'imap' "
            + "(required), connection (required: name of a BASIC app-password or OAUTH2_CLIENT_CREDENTIALS/XOAUTH2 connection whose "
            + "baseUrlAllowlist contains imaps://<host>), host (required), port (993 | 143), address (required, unique per deployment), "
            + "folder (INBOX), processedAction (flag | move:<folder>), pollSeconds (60), requireAuthentication (true: only "
            + "DKIM/DMARC-aligned senders run turns), senderAllowlist / senderBlocklist (user@domain, *@domain, *@*.domain), "
            + "identityMode (perSender | integration), correlateThreads (false), attachments (drop | metadata | store), "
            + "attachmentMimeTypes (required for store), maxMessageBytes, stripQuotedHistory (true), inputTemplate, "
            + "maxTurnsPerSenderPerDay (50), dailyCostBudgetUsd, ownerNotifyAddresses. For email, targets[].triggers match the "
            + "address plus-tag or a subject word. Returns the new resource ID and URI, for a webhook its endpoint path "
            + "POST /integrations/webhook/{resourceId}, and save-time warnings (e.g. email.open-trigger, "
            + "email.tools-without-approvals) when there are any.")
    public String createChannelIntegration(
                                           @ToolArg(description = "Full JSON configuration body (required)") String config) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        if (config == null || config.isBlank())
            return errorJson("config is required");
        try {
            // Same strictness as POST/PUT /channelstore/channels —
            // ChannelIntegrationConfiguration
            // is a first-party config model, so a typo'd key must be rejected here too
            // rather than dropped into a silently different integration.
            var channelConfig = configParser.parse(config, ChannelIntegrationConfiguration.class);
            var channelStore = getRestStore(
                    IRestChannelIntegrationStore.class);
            Response response = channelStore.createChannel(channelConfig);
            String location = response.getHeaderString("Location");
            String newId = extractIdFromLocation(location);

            var created = new LinkedHashMap<String, Object>();
            created.put("resourceId", newId != null ? newId : "unknown");
            created.put("name", channelConfig.getName() != null ? channelConfig.getName() : "");
            created.put("channelType", channelConfig.getChannelType() != null ? channelConfig.getChannelType() : "");
            created.put("targetCount", channelConfig.getTargets() != null ? channelConfig.getTargets().size() : 0);
            created.put("location", location != null ? location : "unknown");
            created.put("status", response.getStatus());
            if (newId != null && "webhook".equalsIgnoreCase(channelConfig.getChannelType())) {
                created.put("webhookEndpoint", "/integrations/webhook/" + newId);
            }
            putWarnings(created, response);
            return resultJson("created", created);
        } catch (Exception e) {
            LOGGER.error("MCP create_channel_integration failed", e);
            return errorJson("Failed to create channel integration", e);
        }
    }

    @Tool(name = "update_channel_integration", description = "Update an existing channel integration configuration.")
    public String updateChannelIntegration(
                                           @ToolArg(description = "Channel integration resource ID (required)") String resourceId,
                                           @ToolArg(description = "Current version number (required)") Integer version,
                                           @ToolArg(description = "Full JSON configuration body (required)") String config) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        if (resourceId == null || resourceId.isBlank())
            return errorJson("resourceId is required");
        if (config == null || config.isBlank())
            return errorJson("config is required");
        try {
            int ver = version != null ? version : 1;
            // Same strictness as POST/PUT /channelstore/channels —
            // ChannelIntegrationConfiguration
            // is a first-party config model, so a typo'd key must be rejected here too
            // rather than dropped into a silently different integration.
            var channelConfig = configParser.parse(config, ChannelIntegrationConfiguration.class);
            var channelStore = getRestStore(
                    IRestChannelIntegrationStore.class);
            Response response = channelStore.updateChannel(resourceId, ver, channelConfig);
            String location = response.getHeaderString("Location");
            int newVersion = extractVersionFromLocation(location);

            var updated = new LinkedHashMap<String, Object>();
            updated.put("resourceId", resourceId);
            updated.put("previousVersion", ver);
            updated.put("newVersion", newVersion);
            updated.put("status", response.getStatus());
            putWarnings(updated, response);
            return resultJson("updated", updated);
        } catch (Exception e) {
            LOGGER.error("MCP update_channel_integration failed for " + resourceId, e);
            return errorJson("Failed to update channel integration", e);
        }
    }

    /**
     * Copies the channel store's save-time warning codes ({@code X-Eddi-Warnings})
     * into a tool result, with their meaning, so an assistant can relay them.
     */
    private static void putWarnings(Map<String, Object> result, Response response) {
        String header = response.getHeaderString("X-Eddi-Warnings");
        if (header == null || header.isBlank()) {
            return;
        }
        List<String> warnings = new ArrayList<>();
        for (String code : header.split(",")) {
            String trimmed = code.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            warnings.add(switch (trimmed) {
                case "email.open-trigger" -> trimmed + ": no senderAllowlist and requireAuthentication is false, so anyone on the internet "
                        + "can make this agent run";
                case "email.tools-without-approvals" -> trimmed + ": a targeted agent has tools but no hitlConfig.toolApprovals, and mail "
                        + "is untrusted input";
                default -> trimmed;
            });
        }
        if (!warnings.isEmpty()) {
            result.put("warnings", warnings);
        }
    }

    @Tool(name = "delete_channel_integration", description = "Delete a channel integration configuration.")
    public String deleteChannelIntegration(
                                           @ToolArg(description = "Channel integration resource ID (required)") String resourceId,
                                           @ToolArg(description = "Current version number (required)") Integer version,
                                           @ToolArg(description = "Permanently delete? (default: false)", required = false,
                                                    defaultValue = "false") Boolean permanent) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        if (resourceId == null || resourceId.isBlank())
            return errorJson("resourceId is required");
        try {
            int ver = version != null ? version : 1;
            boolean isPermanent = permanent != null ? permanent : false;
            var channelStore = getRestStore(
                    IRestChannelIntegrationStore.class);
            Response response = channelStore.deleteChannel(resourceId, ver, isPermanent);

            return resultJson("deleted", Map.of(
                    "resourceId", resourceId,
                    "version", ver,
                    "permanent", isPermanent,
                    "status", response.getStatus()));
        } catch (Exception e) {
            LOGGER.error("MCP delete_channel_integration failed for " + resourceId, e);
            return errorJson("Failed to delete channel integration", e);
        }
    }
}
