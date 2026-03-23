package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.rules.IRestRuleSetStore;
import ai.labs.eddi.configs.rules.model.RuleSetConfiguration;
import ai.labs.eddi.engine.triggermanagement.IRestAgentTriggerStore;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IRestDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.apicalls.IRestApiCallsStore;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.llm.IRestLlmStore;
import ai.labs.eddi.configs.output.IRestOutputStore;
import ai.labs.eddi.configs.output.model.OutputConfigurationSet;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.configs.patch.PatchInstruction;
import ai.labs.eddi.configs.propertysetter.IRestPropertySetterStore;
import ai.labs.eddi.configs.propertysetter.model.PropertySetterConfiguration;
import ai.labs.eddi.configs.dictionary.IRestDictionaryStore;
import ai.labs.eddi.configs.dictionary.model.DictionaryConfiguration;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.schedule.model.ScheduleFireLog;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.engine.runtime.internal.CronDescriber;
import ai.labs.eddi.engine.runtime.internal.CronParser;
import ai.labs.eddi.engine.runtime.internal.ScheduleFireExecutor;
import ai.labs.eddi.engine.runtime.internal.SchedulePollerService;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.engine.triggermanagement.model.AgentTriggerConfiguration;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
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
 * MCP tools for administering EDDI agents, packages, and resources.
 * Exposes deploy, undeploy, CRUD, batch cascade, and introspection
 * as MCP-compliant tools via the Quarkus MCP Server extension.
 *
 * <p>
 * Phase 8a — Admin API MCP Server
 * <p>
 * Phase 8a.2 — Resource CRUD + Batch Cascade + Introspection
 *
 * @author ginccc
 */
@ApplicationScoped
public class McpAdminTools {

    private static final Logger LOGGER = Logger.getLogger(McpAdminTools.class);

    private final IRestInterfaceFactory restInterfaceFactory;
    private final IRestAgentAdministration agentAdmin;
    private final IJsonSerialization jsonSerialization;
    private final IScheduleStore scheduleStore;
    private final ScheduleFireExecutor scheduleFireExecutor;
    private final SchedulePollerService schedulePollerService;

    @Inject
    public McpAdminTools(IRestInterfaceFactory restInterfaceFactory,
            IRestAgentAdministration agentAdmin,
            IJsonSerialization jsonSerialization,
            IScheduleStore scheduleStore,
            ScheduleFireExecutor scheduleFireExecutor,
            SchedulePollerService schedulePollerService) {
        this.restInterfaceFactory = restInterfaceFactory;
        this.agentAdmin = agentAdmin;
        this.jsonSerialization = jsonSerialization;
        this.scheduleStore = scheduleStore;
        this.scheduleFireExecutor = scheduleFireExecutor;
        this.schedulePollerService = schedulePollerService;
    }

    @Tool(name = "deploy_agent", description = "Deploy a Agent to an environment. The Agent must exist and have a valid configuration. "
            +
            "Returns the deployment status.")
    public String deployAgent(
            @ToolArg(description = "Agent ID (required)") String agentId,
            @ToolArg(description = "Version number to deploy (required)") Integer version,
            @ToolArg(description = "Environment: 'production' (default), 'restricted', or 'test'") String environment) {
        try {
            var env = parseEnvironment(environment);
            int ver = version != null ? version : 1;
            Response response = agentAdmin.deployAgent(env, agentId, ver, true, true);
            int httpStatus = response.getStatus();

            var result = new java.util.LinkedHashMap<String, Object>();
            result.put("agentId", agentId);
            result.put("version", ver);
            result.put("environment", env.name());
            result.put("httpStatus", httpStatus);

            if (httpStatus == 200) {
                // Read actual deployment status from response body
                try {
                    @SuppressWarnings("unchecked")
                    var body = (java.util.Map<String, Object>) response.getEntity();
                    if (body != null && body.containsKey("status")) {
                        String deployStatus = body.get("status").toString();
                        result.put("deploymentStatus", deployStatus);
                        boolean ready = "READY".equals(deployStatus);
                        result.put("deployed", ready);
                        if (!ready && !"IN_PROGRESS".equals(deployStatus)) {
                            result.put("action", "deploy_failed");
                            result.put("error", "Deployment status is " + deployStatus +
                                    ". Check Agent configuration, LLM provider credentials, and model availability.");
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
            LOGGER.error("MCP deploy_agent failed for Agent " + agentId, e);
            return errorJson("Failed to deploy agent. Check server logs for details.");
        }
    }

    @Tool(name = "undeploy_agent", description = "Undeploy a Agent from an environment. Optionally end all active conversations.")
    public String undeployAgent(
            @ToolArg(description = "Agent ID (required)") String agentId,
            @ToolArg(description = "Version number to undeploy (required)") Integer version,
            @ToolArg(description = "Environment: 'production' (default), 'restricted', or 'test'") String environment,
            @ToolArg(description = "End all active conversations? (default: false)") Boolean endConversations) {
        try {
            var env = parseEnvironment(environment);
            int ver = version != null ? version : 1;
            boolean endAll = endConversations != null ? endConversations : false;
            Response response = agentAdmin.undeployAgent(env, agentId, ver, endAll, false);
            return resultJson("undeployed", Map.of(
                    "agentId", agentId,
                    "version", ver,
                    "environment", env.name(),
                    "endedConversations", endAll,
                    "status", response.getStatus()));
        } catch (Exception e) {
            LOGGER.error("MCP undeploy_agent failed for Agent " + agentId, e);
            return errorJson("Failed to undeploy agent: " + e.getMessage());
        }
    }

    @Tool(name = "get_deployment_status", description = "Get the deployment status of a specific Agent version in an environment.")
    public String getDeploymentStatus(
            @ToolArg(description = "Agent ID (required)") String agentId,
            @ToolArg(description = "Version number (required)") Integer version,
            @ToolArg(description = "Environment: 'production' (default), 'restricted', or 'test'") String environment) {
        try {
            var env = parseEnvironment(environment);
            int ver = version != null ? version : 1;
            Response response = agentAdmin.getDeploymentStatus(env, agentId, ver, "json");
            Object entity = response.getEntity();
            if (entity == null) {
                return resultJson("status_check", Map.of(
                        "agentId", agentId,
                        "version", ver,
                        "httpStatus", response.getStatus()));
            }
            return jsonSerialization.serialize(entity);
        } catch (Exception e) {
            LOGGER.error("MCP get_deployment_status failed for Agent " + agentId, e);
            return errorJson("Failed to get deployment status: " + e.getMessage());
        }
    }

    @Tool(name = "list_workflows", description = "List all packages (workflow configurations). " +
            "Returns a JSON array of package descriptors with name, description, and IDs.")
    public String listWorkflows(
            @ToolArg(description = "Optional filter string to search package names") String filter,
            @ToolArg(description = "Maximum number of results (default 20)") Integer limit) {
        try {
            int limitInt = limit != null ? limit : 20;
            String filterStr = filter != null ? filter : "";
            List<DocumentDescriptor> descriptors = getRestStore(IRestWorkflowStore.class)
                    .readWorkflowDescriptors(filterStr, 0, limitInt);
            return jsonSerialization.serialize(descriptors);
        } catch (Exception e) {
            LOGGER.error("MCP list_workflows failed", e);
            return errorJson("Failed to list packages: " + e.getMessage());
        }
    }

    @Tool(name = "create_agent", description = "Create a new Agent with a given name and optional packages. " +
            "The Agent is created and its descriptor is updated with the provided name and description. " +
            "Returns the Agent ID and Location URI of the newly created agent.")
    public String createAgent(
            @ToolArg(description = "Agent name (required)") String name,
            @ToolArg(description = "Agent description (optional)") String description,
            @ToolArg(description = "Comma-separated list of package URIs to include (optional, " +
                    "format: eddi://ai.labs.workflow/workflowstore/workflows/ID?version=1)") String workflowUris) {
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
                    LOGGER.warn("MCP create_agent: Agent created but descriptor update failed for " + agentId,
                            patchError);
                    // Agent was still created — return success with warning
                }
            }

            return resultJson("created", Map.of(
                    "agentId", agentId != null ? agentId : "unknown",
                    "name", name != null ? name : "",
                    "description", description != null ? description : "",
                    "location", location != null ? location : "unknown",
                    "status", response.getStatus()));
        } catch (Exception e) {
            LOGGER.error("MCP create_agent failed", e);
            return errorJson("Failed to create agent: " + e.getMessage());
        }
    }

    @Tool(name = "delete_agent", description = "Delete a agent. Optionally cascade-delete all referenced packages and resources.")
    public String deleteAgent(
            @ToolArg(description = "Agent ID (required)") String agentId,
            @ToolArg(description = "Version number (required)") Integer version,
            @ToolArg(description = "Permanently delete? (default: false)") Boolean permanent,
            @ToolArg(description = "Cascade-delete packages and resources? (default: false)") Boolean cascade) {
        try {
            int ver = version != null ? version : 1;
            boolean isPermanent = permanent != null ? permanent : false;
            boolean isCascade = cascade != null ? cascade : false;
            Response response = getRestStore(IRestAgentStore.class).deleteAgent(agentId, ver, isPermanent, isCascade);
            return resultJson("deleted", Map.of(
                    "agentId", agentId,
                    "version", ver,
                    "permanent", isPermanent,
                    "cascade", isCascade,
                    "status", response.getStatus()));
        } catch (Exception e) {
            LOGGER.error("MCP delete_agent failed for Agent " + agentId, e);
            return errorJson("Failed to delete agent: " + e.getMessage());
        }
    }

    @Tool(name = "update_agent", description = "Update an agent's name and/or description, and optionally redeploy. " +
            "For structural changes (adding/removing packages, modifying resources), use the " +
            "individual resource tools (read_workflow, read_resource) and the REST API directly.")
    public String updateAgent(
            @ToolArg(description = "Agent ID (required)") String agentId,
            @ToolArg(description = "Version number (required)") Integer version,
            @ToolArg(description = "New Agent name (optional)") String name,
            @ToolArg(description = "New Agent description (optional)") String description,
            @ToolArg(description = "Redeploy the Agent after update? (default: false)") Boolean redeploy,
            @ToolArg(description = "Environment for redeployment: 'production' (default), 'restricted', or 'test'") String environment) {
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
            return errorJson("Failed to update agent: " + e.getMessage());
        }
    }

    @Tool(name = "read_workflow", description = "Read a workflow's full workflow configuration. " +
            "Returns the list of package extensions (parser, behavior, langchain, httpcalls, output, etc.) " +
            "with their types and resource URIs. Use this to understand what's inside an agent's workflow.")
    public String readWorkflow(
            @ToolArg(description = "Workflow ID (required)") String workflowId,
            @ToolArg(description = "Version number (default: 1)") Integer version) {
        if (workflowId == null || workflowId.isBlank())
            return errorJson("workflowId is required");
        try {
            int ver = version != null ? version : 1;
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
            LOGGER.error("MCP read_workflow failed for package " + workflowId, e);
            return errorJson("Failed to read package: " + e.getMessage());
        }
    }

    @Tool(name = "read_resource", description = "Read any EDDI resource configuration by type and ID. " +
            "Supported types: 'behavior', 'langchain', 'httpcalls', 'output', " +
            "'propertysetter', 'dictionaries'. Returns the full configuration JSON.")
    public String readResource(
            @ToolArg(description = "Resource type: 'behavior', 'langchain', 'httpcalls', 'output', " +
                    "'propertysetter', or 'dictionaries' (required)") String resourceType,
            @ToolArg(description = "Resource ID (required)") String resourceId,
            @ToolArg(description = "Version number (default: 1)") Integer version) {
        if (resourceType == null || resourceType.isBlank())
            return errorJson("resourceType is required");
        if (resourceId == null || resourceId.isBlank())
            return errorJson("resourceId is required");
        try {
            int ver = version != null ? version : 1;
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
            return errorJson("Failed to read resource: " + e.getMessage());
        }
    }

    /**
     * Dispatch resource read to the correct REST store based on type.
     */
    private Object readResourceByType(String type, String id, int version) {
        return switch (type) {
            case "behavior" -> getRestStore(IRestRuleSetStore.class).readRuleSet(id, version);
            case "langchain" -> getRestStore(IRestLlmStore.class).readLlm(id, version);
            case "httpcalls" -> getRestStore(IRestApiCallsStore.class).readApiCalls(id, version);
            case "output" -> getRestStore(IRestOutputStore.class).readOutputSet(id, version, "", "", 0, 0);
            case "propertysetter" -> getRestStore(IRestPropertySetterStore.class).readPropertySetter(id, version);
            case "dictionaries" -> getRestStore(IRestDictionaryStore.class)
                    .readRegularDictionary(id, version, "", "", 0, 0);
            default -> throw new IllegalArgumentException("Unknown resource type: " + type +
                    ". Supported: behavior, langchain, httpcalls, output, propertysetter, dictionaries");
        };
    }

    // ==================== Phase 8a.2: Resource CRUD + Batch Cascade
    // ====================

    @Tool(name = "update_resource", description = "Update any EDDI resource configuration by type and ID. " +
            "Returns the new version URI after update. " +
            "Supported types: 'behavior', 'langchain', 'httpcalls', 'output', 'propertysetter', 'dictionaries'.")
    public String updateResource(
            @ToolArg(description = "Resource type: 'behavior', 'langchain', 'httpcalls', 'output', " +
                    "'propertysetter', or 'dictionaries' (required)") String resourceType,
            @ToolArg(description = "Resource ID (required)") String resourceId,
            @ToolArg(description = "Current version number (required)") Integer version,
            @ToolArg(description = "Full JSON configuration body (required)") String config) {
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
            return errorJson("Failed to update resource: " + e.getMessage());
        }
    }

    @Tool(name = "create_resource", description = "Create a new EDDI resource configuration. " +
            "Returns the new resource ID and URI. " +
            "Supported types: 'behavior', 'langchain', 'httpcalls', 'output', 'propertysetter', 'dictionaries'.")
    public String createResource(
            @ToolArg(description = "Resource type: 'behavior', 'langchain', 'httpcalls', 'output', " +
                    "'propertysetter', or 'dictionaries' (required)") String resourceType,
            @ToolArg(description = "Full JSON configuration body (required)") String config) {
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
            return errorJson("Failed to create resource: " + e.getMessage());
        }
    }

    @Tool(name = "delete_resource", description = "Delete an EDDI resource configuration. " +
            "Soft-deletes by default; set permanent=true for permanent removal. " +
            "Supported types: 'behavior', 'langchain', 'httpcalls', 'output', 'propertysetter', 'dictionaries'.")
    public String deleteResource(
            @ToolArg(description = "Resource type: 'behavior', 'langchain', 'httpcalls', 'output', " +
                    "'propertysetter', or 'dictionaries' (required)") String resourceType,
            @ToolArg(description = "Resource ID (required)") String resourceId,
            @ToolArg(description = "Current version number (required)") Integer version,
            @ToolArg(description = "Permanently delete? (default: false)") Boolean permanent) {
        if (resourceType == null || resourceType.isBlank())
            return errorJson("resourceType is required");
        if (resourceId == null || resourceId.isBlank())
            return errorJson("resourceId is required");
        try {
            int ver = version != null ? version : 1;
            boolean isPermanent = permanent != null ? permanent : false;
            String type = resourceType.trim().toLowerCase();
            Response response = deleteResourceByType(type, resourceId, ver, isPermanent);

            return resultJson("deleted", Map.of(
                    "resourceType", type,
                    "resourceId", resourceId,
                    "version", ver,
                    "permanent", isPermanent,
                    "status", response.getStatus()));
        } catch (Exception e) {
            LOGGER.error("MCP delete_resource failed for " + resourceType + "/" + resourceId, e);
            return errorJson("Failed to delete resource: " + e.getMessage());
        }
    }

    @Tool(name = "apply_agent_changes", description = "Batch-cascade multiple resource URI changes through " +
            "package → Agent in ONE pass. After updating resources with update_resource, use this to wire " +
            "the new versions into the agent's packages and optionally redeploy. " +
            "This replaces ALL URIs in-memory and saves each package/agent ONCE — no wasteful intermediate versions.")
    public String applyAgentChanges(
            @ToolArg(description = "Agent ID (required)") String agentId,
            @ToolArg(description = "Current Agent version (required)") Integer agentVersion,
            @ToolArg(description = "JSON array of URI mappings: " +
                    "[{\"oldUri\":\"eddi://...?version=1\",\"newUri\":\"eddi://...?version=2\"}, ...]") String resourceMappings,
            @ToolArg(description = "Redeploy the Agent after cascading changes? (default: false)") Boolean redeploy,
            @ToolArg(description = "Environment for redeployment: 'production' (default), 'restricted', or 'test'") String environment) {
        if (agentId == null || agentId.isBlank())
            return errorJson("agentId is required");
        if (resourceMappings == null || resourceMappings.isBlank())
            return errorJson("resourceMappings is required");
        try {
            int ver = agentVersion != null ? agentVersion : 1;

            // 1. Parse resource mappings
            @SuppressWarnings("unchecked")
            List<Map<String, String>> mappings = jsonSerialization.deserialize(resourceMappings, List.class);
            if (mappings == null || mappings.isEmpty()) {
                return resultJson("no_changes", Map.of("agentId", agentId, "reason", "Empty resource mappings"));
            }

            // 2. Read Agent config
            var localAgentStore = getRestStore(IRestAgentStore.class);
            AgentConfiguration agentConfig = localAgentStore.readAgent(agentId, ver);
            if (agentConfig == null) {
                return errorJson("Agent not found: " + agentId + " version " + ver);
            }

            // 3. Process each package
            var pkgStore = getRestStore(IRestWorkflowStore.class);
            List<URI> originalWorkflowUris = new ArrayList<>(agentConfig.getWorkflows());
            List<URI> updatedWorkflowUris = new ArrayList<>();
            int updatedWorkflowCount = 0;

            for (URI pkgUri : originalWorkflowUris) {
                String pkgId = extractIdFromLocation(pkgUri.toString());
                int pkgVersion = extractVersionFromLocation(pkgUri.toString());
                if (pkgId == null) {
                    updatedWorkflowUris.add(pkgUri); // keep as-is
                    continue;
                }

                WorkflowConfiguration pkgConfig = pkgStore.readWorkflow(pkgId, pkgVersion);
                if (pkgConfig == null) {
                    updatedWorkflowUris.add(pkgUri);
                    continue;
                }

                // Replace URIs in package extensions
                boolean packageModified = false;
                for (var ext : pkgConfig.getWorkflowSteps()) {
                    Object uriObj = ext.getConfig().get("uri");
                    if (uriObj != null) {
                        String currentUri = uriObj.toString();
                        for (var mapping : mappings) {
                            String oldUri = mapping.get("oldUri");
                            String newUri = mapping.get("newUri");
                            if (oldUri != null && newUri != null && currentUri.equals(oldUri)) {
                                ext.getConfig().put("uri", newUri);
                                packageModified = true;
                                break;
                            }
                        }
                    }
                }

                if (packageModified) {
                    // Save package ONCE with all replacements
                    Response pkgResponse = pkgStore.updateWorkflow(pkgId, pkgVersion, pkgConfig);
                    String pkgLocation = pkgResponse.getHeaderString("Location");
                    if (pkgLocation != null) {
                        updatedWorkflowUris.add(URI.create(pkgLocation));
                    } else {
                        // Construct new URI with incremented version
                        int newPkgVersion = pkgVersion + 1;
                        String newPkgUri = pkgUri.toString().replaceAll(
                                "version=\\d+", "version=" + newPkgVersion);
                        updatedWorkflowUris.add(URI.create(newPkgUri));
                    }
                    updatedWorkflowCount++;
                } else {
                    updatedWorkflowUris.add(pkgUri); // unchanged
                }
            }

            // 4. Update Agent if any packages changed
            int newAgentVersion = ver;
            if (updatedWorkflowCount > 0) {
                agentConfig.setWorkflows(updatedWorkflowUris);
                Response agentResponse = localAgentStore.updateAgent(agentId, ver, agentConfig);
                String agentLocation = agentResponse.getHeaderString("Location");
                newAgentVersion = agentLocation != null
                        ? extractVersionFromLocation(agentLocation)
                        : ver + 1;
            }

            // 5. Redeploy if requested
            var result = new LinkedHashMap<String, Object>();
            result.put("agentId", agentId);
            result.put("previousAgentVersion", ver);
            result.put("newAgentVersion", newAgentVersion);
            result.put("updatedWorkflows", updatedWorkflowCount);
            result.put("totalWorkflows", originalWorkflowUris.size());
            result.put("mappingsApplied", mappings.size());

            if (Boolean.TRUE.equals(redeploy) && updatedWorkflowCount > 0) {
                var env = parseEnvironment(environment);
                try {
                    Response deployResponse = agentAdmin.deployAgent(env, agentId, newAgentVersion, true, true);
                    result.put("redeployed", deployResponse.getStatus() == 200);
                    result.put("environment", env.name());
                } catch (Exception deployErr) {
                    result.put("redeployed", false);
                    result.put("deployError", "Redeployment failed: " + deployErr.getMessage());
                }
            }

            return resultJson("cascaded", result);
        } catch (Exception e) {
            LOGGER.error("MCP apply_agent_changes failed for Agent " + agentId, e);
            return errorJson("Failed to apply Agent changes: " + e.getMessage());
        }
    }

    @Tool(name = "list_agent_resources", description = "Get a complete inventory of all resources in an agent's workflow. "
            +
            "Walks Agent → packages → extensions and returns a flat summary with all resource IDs, types, and URIs. " +
            "This is the fastest way to understand an agent's full configuration before making changes.")
    public String listAgentResources(
            @ToolArg(description = "Agent ID (required)") String agentId,
            @ToolArg(description = "Agent version (default: 1)") Integer version) {
        if (agentId == null || agentId.isBlank())
            return errorJson("agentId is required");
        try {
            int ver = version != null ? version : 1;

            // Read Agent config
            var localAgentStore = getRestStore(IRestAgentStore.class);
            AgentConfiguration agentConfig = localAgentStore.readAgent(agentId, ver);
            if (agentConfig == null) {
                return errorJson("Agent not found: " + agentId + " version " + ver);
            }

            // Read Agent name from descriptor
            String agentName = null;
            try {
                DocumentDescriptor descriptor = getRestStore(IRestDocumentDescriptorStore.class)
                        .readDescriptor(agentId, ver);
                if (descriptor != null) {
                    agentName = descriptor.getName();
                }
            } catch (Exception e) {
                LOGGER.debug("Could not read Agent descriptor for " + agentId, e);
            }

            // Walk packages → extensions
            var pkgStore = getRestStore(IRestWorkflowStore.class);
            var packages = new ArrayList<Map<String, Object>>();

            for (URI pkgUri : agentConfig.getWorkflows()) {
                String pkgId = extractIdFromLocation(pkgUri.toString());
                int pkgVersion = extractVersionFromLocation(pkgUri.toString());
                if (pkgId == null)
                    continue;

                var pkgInfo = new LinkedHashMap<String, Object>();
                pkgInfo.put("workflowId", pkgId);
                pkgInfo.put("packageVersion", pkgVersion);
                pkgInfo.put("workflowUri", pkgUri.toString());

                try {
                    WorkflowConfiguration pkgConfig = pkgStore.readWorkflow(pkgId, pkgVersion);
                    if (pkgConfig != null) {
                        var extensions = new ArrayList<Map<String, Object>>();
                        for (var ext : pkgConfig.getWorkflowSteps()) {
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
                        pkgInfo.put("extensions", extensions);
                        pkgInfo.put("extensionCount", extensions.size());
                    }
                } catch (Exception pkgErr) {
                    pkgInfo.put("error", "Failed to read package: " + pkgErr.getMessage());
                }

                packages.add(pkgInfo);
            }

            var result = new LinkedHashMap<String, Object>();
            result.put("agentId", agentId);
            result.put("agentVersion", ver);
            if (agentName != null)
                result.put("agentName", agentName);
            result.put("packageCount", packages.size());
            result.put("packages", packages);
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            LOGGER.error("MCP list_agent_resources failed for Agent " + agentId, e);
            return errorJson("Failed to list Agent resources: " + e.getMessage());
        }
    }

    // ==================== Type Dispatch Helpers ====================

    /**
     * Dispatch resource update to the correct REST store based on type.
     */
    private Response updateResourceByType(String type, String id, int version, String configJson)
            throws IOException {
        return switch (type) {
            case "behavior" -> getRestStore(IRestRuleSetStore.class)
                    .updateRuleSet(id, version,
                            jsonSerialization.deserialize(configJson, RuleSetConfiguration.class));
            case "langchain" -> getRestStore(IRestLlmStore.class)
                    .updateLlm(id, version,
                            jsonSerialization.deserialize(configJson, LlmConfiguration.class));
            case "httpcalls" -> getRestStore(IRestApiCallsStore.class)
                    .updateApiCalls(id, version,
                            jsonSerialization.deserialize(configJson, ApiCallsConfiguration.class));
            case "output" -> getRestStore(IRestOutputStore.class)
                    .updateOutputSet(id, version,
                            jsonSerialization.deserialize(configJson, OutputConfigurationSet.class));
            case "propertysetter" -> getRestStore(IRestPropertySetterStore.class)
                    .updatePropertySetter(id, version,
                            jsonSerialization.deserialize(configJson, PropertySetterConfiguration.class));
            case "dictionaries" -> getRestStore(IRestDictionaryStore.class)
                    .updateRegularDictionary(id, version,
                            jsonSerialization.deserialize(configJson, DictionaryConfiguration.class));
            default -> throw new IllegalArgumentException("Unknown resource type: " + type +
                    ". Supported: behavior, langchain, httpcalls, output, propertysetter, dictionaries");
        };
    }

    /**
     * Dispatch resource create to the correct REST store based on type.
     */
    private Response createResourceByType(String type, String configJson) throws IOException {
        return switch (type) {
            case "behavior" -> getRestStore(IRestRuleSetStore.class)
                    .createRuleSet(jsonSerialization.deserialize(configJson, RuleSetConfiguration.class));
            case "langchain" -> getRestStore(IRestLlmStore.class)
                    .createLlm(jsonSerialization.deserialize(configJson, LlmConfiguration.class));
            case "httpcalls" -> getRestStore(IRestApiCallsStore.class)
                    .createApiCalls(jsonSerialization.deserialize(configJson, ApiCallsConfiguration.class));
            case "output" -> getRestStore(IRestOutputStore.class)
                    .createOutputSet(jsonSerialization.deserialize(configJson, OutputConfigurationSet.class));
            case "propertysetter" -> getRestStore(IRestPropertySetterStore.class)
                    .createPropertySetter(jsonSerialization.deserialize(configJson, PropertySetterConfiguration.class));
            case "dictionaries" -> getRestStore(IRestDictionaryStore.class)
                    .createRegularDictionary(
                            jsonSerialization.deserialize(configJson, DictionaryConfiguration.class));
            default -> throw new IllegalArgumentException("Unknown resource type: " + type +
                    ". Supported: behavior, langchain, httpcalls, output, propertysetter, dictionaries");
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
            case "output" -> getRestStore(IRestOutputStore.class).deleteOutputSet(id, version, permanent);
            case "propertysetter" ->
                getRestStore(IRestPropertySetterStore.class).deletePropertySetter(id, version, permanent);
            case "dictionaries" ->
                getRestStore(IRestDictionaryStore.class).deleteRegularDictionary(id, version, permanent);
            default -> throw new IllegalArgumentException("Unknown resource type: " + type +
                    ". Supported: behavior, langchain, httpcalls, output, propertysetter, dictionaries");
        };
    }

    /**
     * Map a workflow extension type URI to the MCP resource type slug.
     * E.g., "eddi://ai.labs.rules" → "behavior"
     */
    private static String uriToResourceType(String typeUri) {
        if (typeUri == null)
            return "unknown";
        if (typeUri.contains("behavior"))
            return "behavior";
        if (typeUri.contains("langchain"))
            return "langchain";
        if (typeUri.contains("httpcalls"))
            return "httpcalls";
        if (typeUri.contains("output"))
            return "output";
        if (typeUri.contains("property"))
            return "propertysetter";
        if (typeUri.contains("dictionary") || typeUri.contains("parser"))
            return "dictionaries";
        return "unknown";
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

    @Tool(name = "list_agent_triggers", description = "List all Agent triggers (intent→agent mappings). " +
            "Returns all configured intents with their Agent deployments. " +
            "Agent triggers enable intent-based conversation management via chat_managed.")
    public String listAgentTriggers() {
        try {
            var triggerStore = getRestStore(IRestAgentTriggerStore.class);
            List<AgentTriggerConfiguration> triggers = triggerStore.readAllAgentTriggers();

            var result = new LinkedHashMap<String, Object>();
            result.put("count", triggers.size());
            result.put("triggers", triggers);
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            LOGGER.error("MCP list_agent_triggers failed", e);
            return errorJson("Failed to list Agent triggers: " + e.getMessage());
        }
    }

    @Tool(name = "create_agent_trigger", description = "Create a Agent trigger that maps an intent to one or more agents. "
            +
            "Once created, the intent can be used with chat_managed to talk to the agent. " +
            "The config must include: intent (string) and agentDeployments (array of {agentId, environment}).")
    public String createAgentTrigger(
            @ToolArg(description = "Full JSON configuration: {\"intent\":\"...\",\"agentDeployments\":[{\"agentId\":\"...\",\"environment\":\"production\"}]} (required)") String config) {
        if (config == null || config.isBlank())
            return errorJson("config is required");
        try {
            var triggerStore = getRestStore(IRestAgentTriggerStore.class);
            AgentTriggerConfiguration triggerConfig = jsonSerialization.deserialize(config,
                    AgentTriggerConfiguration.class);

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
            return errorJson("Failed to create Agent trigger: " + e.getMessage());
        }
    }

    @Tool(name = "update_agent_trigger", description = "Update an existing Agent trigger. " +
            "Changes the Agent deployments for a given intent.")
    public String updateAgentTrigger(
            @ToolArg(description = "Intent to update (required)") String intent,
            @ToolArg(description = "Full JSON configuration: {\"intent\":\"...\",\"agentDeployments\":[{\"agentId\":\"...\",\"environment\":\"production\"}]} (required)") String config) {
        if (intent == null || intent.isBlank())
            return errorJson("intent is required");
        if (config == null || config.isBlank())
            return errorJson("config is required");
        try {
            var triggerStore = getRestStore(IRestAgentTriggerStore.class);
            AgentTriggerConfiguration triggerConfig = jsonSerialization.deserialize(config,
                    AgentTriggerConfiguration.class);
            Response response = triggerStore.updateAgentTrigger(intent, triggerConfig);

            var result = new LinkedHashMap<String, Object>();
            result.put("intent", intent);
            result.put("status", response.getStatus());
            return resultJson("updated", result);
        } catch (Exception e) {
            LOGGER.error("MCP update_agent_trigger failed for intent " + intent, e);
            return errorJson("Failed to update Agent trigger: " + e.getMessage());
        }
    }

    @Tool(name = "delete_agent_trigger", description = "Delete a Agent trigger for a given intent.")
    public String deleteAgentTrigger(
            @ToolArg(description = "Intent to delete (required)") String intent) {
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
            return errorJson("Failed to delete Agent trigger: " + e.getMessage());
        }
    }

    // ── Schedule Management ───────────────────────────────────────────────

    @Tool(name = "create_schedule", description = "Create a new scheduled Agent trigger (cron job or heartbeat). " +
            "For CRON: provide cronExpression. For HEARTBEAT: provide heartbeatIntervalSeconds. " +
            "Returns the created schedule with human-readable description and next fire time.")
    public String createSchedule(
            @ToolArg(description = "Agent ID to trigger (required)") String agentId,
            @ToolArg(description = "Trigger type: 'CRON' (default) or 'HEARTBEAT'") String triggerType,
            @ToolArg(description = "5-field cron expression, e.g. '0 9 * * MON-FRI' (required for CRON)") String cron,
            @ToolArg(description = "Heartbeat interval in seconds, e.g. 300 for 5 min (required for HEARTBEAT)") Long heartbeatIntervalSeconds,
            @ToolArg(description = "Message text to send to the Agent on each fire (required for CRON, defaults to 'heartbeat' for HEARTBEAT)") String message,
            @ToolArg(description = "Human-readable name for this schedule (required)") String name,
            @ToolArg(description = "IANA time zone, e.g. 'Europe/Vienna' (default: UTC)") String timeZone,
            @ToolArg(description = "Conversation strategy: 'new' or 'persistent' (CRON defaults to 'new', HEARTBEAT defaults to 'persistent')") String conversationStrategy,
            @ToolArg(description = "User identity for the scheduled message (default: 'system:scheduler')") String userId,
            @ToolArg(description = "Environment: 'production' (default), 'restricted', or 'test'") String environment) {
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
            schedule.setMessage(message != null && !message.isBlank() ? message
                    : (type == ScheduleConfiguration.TriggerType.HEARTBEAT ? "heartbeat" : null));
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
                description = sec < 60 ? "Every " + sec + " seconds"
                        : sec < 3600 ? "Every " + (sec / 60) + " minutes"
                                : "Every " + (sec / 3600) + " hours";
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
            return errorJson("Invalid schedule: " + e.getMessage());
        } catch (Exception e) {
            LOGGER.error("MCP create_schedule failed", e);
            return errorJson("Failed to create schedule: " + e.getMessage());
        }
    }

    @Tool(name = "list_schedules", description = "List all scheduled Agent triggers. " +
            "Returns schedules with name, type, agent, cron/interval, status, next fire time, and fire count. " +
            "Optionally filter by agentId.")
    public String listSchedules(
            @ToolArg(description = "Filter by Agent ID (optional)") String agentId) {
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
            return errorJson("Failed to list schedules: " + e.getMessage());
        }
    }

    @Tool(name = "read_schedule", description = "Read a schedule's full configuration including fire history. " +
            "Returns the schedule config, human-readable description, and recent fire logs.")
    public String readSchedule(
            @ToolArg(description = "Schedule ID (required)") String scheduleId) {
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
            return errorJson("Failed to read schedule: " + e.getMessage());
        }
    }

    @Tool(name = "delete_schedule", description = "Delete a scheduled Agent trigger.")
    public String deleteSchedule(
            @ToolArg(description = "Schedule ID (required)") String scheduleId) {
        if (scheduleId == null || scheduleId.isBlank())
            return errorJson("scheduleId is required");
        try {
            scheduleStore.deleteSchedule(scheduleId);
            return resultJson("schedule_deleted", Map.of("scheduleId", scheduleId));
        } catch (Exception e) {
            LOGGER.error("MCP delete_schedule failed for " + scheduleId, e);
            return errorJson("Failed to delete schedule: " + e.getMessage());
        }
    }

    @Tool(name = "fire_schedule_now", description = "Manually trigger a schedule fire immediately. " +
            "Useful for testing or one-off executions. Returns the fire result with conversation ID.")
    public String fireScheduleNow(
            @ToolArg(description = "Schedule ID (required)") String scheduleId) {
        if (scheduleId == null || scheduleId.isBlank())
            return errorJson("scheduleId is required");
        try {
            var schedule = scheduleStore.readSchedule(scheduleId);
            ScheduleFireLog fireLog = scheduleFireExecutor.fire(schedule, schedulePollerService.getInstanceId(), 1);

            var result = new LinkedHashMap<String, Object>();
            result.put("scheduleId", scheduleId);
            result.put("scheduleName", schedule.getName());
            result.put("fireStatus", fireLog.status());
            result.put("conversationId", fireLog.conversationId());
            result.put("duration", fireLog.completedAt() != null && fireLog.startedAt() != null
                    ? (fireLog.completedAt().toEpochMilli() - fireLog.startedAt().toEpochMilli()) + "ms"
                    : null);
            if (fireLog.errorMessage() != null) {
                result.put("error", fireLog.errorMessage());
            }
            return resultJson("schedule_fired", result);
        } catch (Exception e) {
            LOGGER.error("MCP fire_schedule_now failed for " + scheduleId, e);
            return errorJson("Failed to fire schedule: " + e.getMessage());
        }
    }

    @Tool(name = "retry_failed_schedule", description = "Re-queue a dead-lettered schedule for another fire attempt. " +
            "Use after investigating and fixing the cause of failure.")
    public String retryFailedSchedule(
            @ToolArg(description = "Schedule ID (required)") String scheduleId) {
        if (scheduleId == null || scheduleId.isBlank())
            return errorJson("scheduleId is required");
        try {
            scheduleStore.requeueDeadLetter(scheduleId);
            return resultJson("schedule_requeued", Map.of(
                    "scheduleId", scheduleId,
                    "status", "PENDING",
                    "message", "Schedule requeued for next poll cycle"));
        } catch (Exception e) {
            LOGGER.error("MCP retry_failed_schedule failed for " + scheduleId, e);
            return errorJson("Failed to retry schedule: " + e.getMessage());
        }
    }
}
