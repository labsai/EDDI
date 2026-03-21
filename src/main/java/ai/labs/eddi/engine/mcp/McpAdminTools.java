package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.rules.IRestBehaviorStore;
import ai.labs.eddi.configs.rules.model.BehaviorConfiguration;
import ai.labs.eddi.engine.botmanagement.IRestBotTriggerStore;
import ai.labs.eddi.configs.agents.IRestBotStore;
import ai.labs.eddi.configs.agents.model.BotConfiguration;
import ai.labs.eddi.configs.descriptors.IRestDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.apicalls.IRestHttpCallsStore;
import ai.labs.eddi.configs.apicalls.model.HttpCallsConfiguration;
import ai.labs.eddi.configs.llm.IRestLangChainStore;
import ai.labs.eddi.configs.output.IRestOutputStore;
import ai.labs.eddi.configs.output.model.OutputConfigurationSet;
import ai.labs.eddi.configs.pipelines.IRestPackageStore;
import ai.labs.eddi.configs.pipelines.model.PackageConfiguration;
import ai.labs.eddi.configs.patch.PatchInstruction;
import ai.labs.eddi.configs.propertysetter.IRestPropertySetterStore;
import ai.labs.eddi.configs.propertysetter.model.PropertySetterConfiguration;
import ai.labs.eddi.configs.dictionary.IRestRegularDictionaryStore;
import ai.labs.eddi.configs.dictionary.model.RegularDictionaryConfiguration;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.schedule.model.ScheduleFireLog;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IRestBotAdministration;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.engine.runtime.internal.CronDescriber;
import ai.labs.eddi.engine.runtime.internal.CronParser;
import ai.labs.eddi.engine.runtime.internal.ScheduleFireExecutor;
import ai.labs.eddi.engine.runtime.internal.SchedulePollerService;
import ai.labs.eddi.modules.langchain.model.LangChainConfiguration;
import ai.labs.eddi.engine.botmanagement.model.BotTriggerConfiguration;
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
 * MCP tools for administering EDDI bots, packages, and resources.
 * Exposes deploy, undeploy, CRUD, batch cascade, and introspection
 * as MCP-compliant tools via the Quarkus MCP Server extension.
 *
 * <p>Phase 8a — Admin API MCP Server
 * <p>Phase 8a.2 — Resource CRUD + Batch Cascade + Introspection
 *
 * @author ginccc
 */
@ApplicationScoped
public class McpAdminTools {

    private static final Logger LOGGER = Logger.getLogger(McpAdminTools.class);

    private final IRestInterfaceFactory restInterfaceFactory;
    private final IRestBotAdministration botAdmin;
    private final IJsonSerialization jsonSerialization;
    private final IScheduleStore scheduleStore;
    private final ScheduleFireExecutor scheduleFireExecutor;
    private final SchedulePollerService schedulePollerService;

    @Inject
    public McpAdminTools(IRestInterfaceFactory restInterfaceFactory,
                         IRestBotAdministration botAdmin,
                         IJsonSerialization jsonSerialization,
                         IScheduleStore scheduleStore,
                         ScheduleFireExecutor scheduleFireExecutor,
                         SchedulePollerService schedulePollerService) {
        this.restInterfaceFactory = restInterfaceFactory;
        this.botAdmin = botAdmin;
        this.jsonSerialization = jsonSerialization;
        this.scheduleStore = scheduleStore;
        this.scheduleFireExecutor = scheduleFireExecutor;
        this.schedulePollerService = schedulePollerService;
    }

    @Tool(name = "deploy_bot",
            description = "Deploy a bot to an environment. The bot must exist and have a valid configuration. " +
                    "Returns the deployment status.")
    public String deployBot(
            @ToolArg(description = "Bot ID (required)") String botId,
            @ToolArg(description = "Version number to deploy (required)") Integer version,
            @ToolArg(description = "Environment: 'production' (default), 'restricted', or 'test'")
            String environment) {
        try {
            var env = parseEnvironment(environment);
            int ver = version != null ? version : 1;
            Response response = botAdmin.deployBot(env, botId, ver, true, true);
            int httpStatus = response.getStatus();

            var result = new java.util.LinkedHashMap<String, Object>();
            result.put("botId", botId);
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
                                    ". Check bot configuration, LLM provider credentials, and model availability.");
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
            LOGGER.error("MCP deploy_bot failed for bot " + botId, e);
            return errorJson("Failed to deploy bot. Check server logs for details.");
        }
    }

    @Tool(name = "undeploy_bot",
            description = "Undeploy a bot from an environment. Optionally end all active conversations.")
    public String undeployBot(
            @ToolArg(description = "Bot ID (required)") String botId,
            @ToolArg(description = "Version number to undeploy (required)") Integer version,
            @ToolArg(description = "Environment: 'production' (default), 'restricted', or 'test'")
            String environment,
            @ToolArg(description = "End all active conversations? (default: false)")
            Boolean endConversations) {
        try {
            var env = parseEnvironment(environment);
            int ver = version != null ? version : 1;
            boolean endAll = endConversations != null ? endConversations : false;
            Response response = botAdmin.undeployBot(env, botId, ver, endAll, false);
            return resultJson("undeployed", Map.of(
                    "botId", botId,
                    "version", ver,
                    "environment", env.name(),
                    "endedConversations", endAll,
                    "status", response.getStatus()
            ));
        } catch (Exception e) {
            LOGGER.error("MCP undeploy_bot failed for bot " + botId, e);
            return errorJson("Failed to undeploy bot: " + e.getMessage());
        }
    }

    @Tool(name = "get_deployment_status",
            description = "Get the deployment status of a specific bot version in an environment.")
    public String getDeploymentStatus(
            @ToolArg(description = "Bot ID (required)") String botId,
            @ToolArg(description = "Version number (required)") Integer version,
            @ToolArg(description = "Environment: 'production' (default), 'restricted', or 'test'")
            String environment) {
        try {
            var env = parseEnvironment(environment);
            int ver = version != null ? version : 1;
            Response response = botAdmin.getDeploymentStatus(env, botId, ver, "json");
            Object entity = response.getEntity();
            if (entity == null) {
                return resultJson("status_check", Map.of(
                        "botId", botId,
                        "version", ver,
                        "httpStatus", response.getStatus()
                ));
            }
            return jsonSerialization.serialize(entity);
        } catch (Exception e) {
            LOGGER.error("MCP get_deployment_status failed for bot " + botId, e);
            return errorJson("Failed to get deployment status: " + e.getMessage());
        }
    }

    @Tool(name = "list_packages",
            description = "List all packages (pipeline configurations). " +
                    "Returns a JSON array of package descriptors with name, description, and IDs.")
    public String listPackages(
            @ToolArg(description = "Optional filter string to search package names") String filter,
            @ToolArg(description = "Maximum number of results (default 20)") Integer limit) {
        try {
            int limitInt = limit != null ? limit : 20;
            String filterStr = filter != null ? filter : "";
            List<DocumentDescriptor> descriptors = getRestStore(IRestPackageStore.class)
                    .readPackageDescriptors(filterStr, 0, limitInt);
            return jsonSerialization.serialize(descriptors);
        } catch (Exception e) {
            LOGGER.error("MCP list_packages failed", e);
            return errorJson("Failed to list packages: " + e.getMessage());
        }
    }

    @Tool(name = "create_bot",
            description = "Create a new bot with a given name and optional packages. " +
                    "The bot is created and its descriptor is updated with the provided name and description. " +
                    "Returns the bot ID and Location URI of the newly created bot.")
    public String createBot(
            @ToolArg(description = "Bot name (required)") String name,
            @ToolArg(description = "Bot description (optional)") String description,
            @ToolArg(description = "Comma-separated list of package URIs to include (optional, " +
                    "format: eddi://ai.labs.package/packagestore/packages/ID?version=1)")
            String packageUris) {
        if (name == null || name.isBlank()) return errorJson("Bot name is required");
        try {
            var botConfig = new BotConfiguration();
            if (packageUris != null && !packageUris.isBlank()) {
                var uris = new ArrayList<URI>();
                for (String uri : packageUris.split(",")) {
                    uris.add(URI.create(uri.trim()));
                }
                botConfig.setPackages(uris);
            }

            Response response = getRestStore(IRestBotStore.class).createBot(botConfig);
            String location = response.getHeaderString("Location");

            // Extract bot ID from location header (format: /botstore/bots/{id}?version=1)
            String botId = extractIdFromLocation(location);

            // Update descriptor with name and description
            if (botId != null && (name != null || description != null)) {
                try {
                    var descriptor = new DocumentDescriptor();
                    if (name != null) descriptor.setName(name);
                    if (description != null) descriptor.setDescription(description);

                    var patch = new PatchInstruction<DocumentDescriptor>();
                    patch.setOperation(PatchInstruction.PatchOperation.SET);
                    patch.setDocument(descriptor);
                    getRestStore(IRestDocumentDescriptorStore.class).patchDescriptor(botId, 1, patch);
                } catch (Exception patchError) {
                    LOGGER.warn("MCP create_bot: bot created but descriptor update failed for " + botId, patchError);
                    // Bot was still created — return success with warning
                }
            }

            return resultJson("created", Map.of(
                    "botId", botId != null ? botId : "unknown",
                    "name", name != null ? name : "",
                    "description", description != null ? description : "",
                    "location", location != null ? location : "unknown",
                    "status", response.getStatus()
            ));
        } catch (Exception e) {
            LOGGER.error("MCP create_bot failed", e);
            return errorJson("Failed to create bot: " + e.getMessage());
        }
    }

    @Tool(name = "delete_bot",
            description = "Delete a bot. Optionally cascade-delete all referenced packages and resources.")
    public String deleteBot(
            @ToolArg(description = "Bot ID (required)") String botId,
            @ToolArg(description = "Version number (required)") Integer version,
            @ToolArg(description = "Permanently delete? (default: false)")
            Boolean permanent,
            @ToolArg(description = "Cascade-delete packages and resources? (default: false)")
            Boolean cascade) {
        try {
            int ver = version != null ? version : 1;
            boolean isPermanent = permanent != null ? permanent : false;
            boolean isCascade = cascade != null ? cascade : false;
            Response response = getRestStore(IRestBotStore.class).deleteBot(botId, ver, isPermanent, isCascade);
            return resultJson("deleted", Map.of(
                    "botId", botId,
                    "version", ver,
                    "permanent", isPermanent,
                    "cascade", isCascade,
                    "status", response.getStatus()
            ));
        } catch (Exception e) {
            LOGGER.error("MCP delete_bot failed for bot " + botId, e);
            return errorJson("Failed to delete bot: " + e.getMessage());
        }
    }

    @Tool(name = "update_bot", description = "Update a bot's name and/or description, and optionally redeploy. " +
            "For structural changes (adding/removing packages, modifying resources), use the " +
            "individual resource tools (read_package, read_resource) and the REST API directly.")
    public String updateBot(
            @ToolArg(description = "Bot ID (required)") String botId,
            @ToolArg(description = "Version number (required)") Integer version,
            @ToolArg(description = "New bot name (optional)") String name,
            @ToolArg(description = "New bot description (optional)") String description,
            @ToolArg(description = "Redeploy the bot after update? (default: false)") Boolean redeploy,
            @ToolArg(description = "Environment for redeployment: 'production' (default), 'restricted', or 'test'")
            String environment) {
        try {
            if (botId == null || botId.isBlank()) return errorJson("botId is required");
            int ver = version != null ? version : 1;

            // Update descriptor (name/description) via REST
            if (name != null || description != null) {
                var descriptor = new DocumentDescriptor();
                if (name != null) descriptor.setName(name);
                if (description != null) descriptor.setDescription(description);

                var patch = new PatchInstruction<DocumentDescriptor>();
                patch.setOperation(PatchInstruction.PatchOperation.SET);
                patch.setDocument(descriptor);
                getRestStore(IRestDocumentDescriptorStore.class).patchDescriptor(botId, ver, patch);
            }

            var result = new LinkedHashMap<String, Object>();
            result.put("botId", botId);
            result.put("version", ver);
            result.put("updated", true);

            // Redeploy if requested
            if (Boolean.TRUE.equals(redeploy)) {
                var env = parseEnvironment(environment);
                try {
                    Response response = botAdmin.deployBot(env, botId, ver, true, true);
                    result.put("redeployed", response.getStatus() == 200);
                    result.put("environment", env.name());
                } catch (Exception deployError) {
                    result.put("redeployed", false);
                    result.put("deployError", "Redeployment failed: " + deployError.getMessage());
                }
            }

            return resultJson("updated", result);
        } catch (Exception e) {
            LOGGER.error("MCP update_bot failed for bot " + botId, e);
            return errorJson("Failed to update bot: " + e.getMessage());
        }
    }

    @Tool(name = "read_package", description = "Read a package's full pipeline configuration. " +
            "Returns the list of package extensions (parser, behavior, langchain, httpcalls, output, etc.) " +
            "with their types and resource URIs. Use this to understand what's inside a bot's pipeline.")
    public String readPackage(
            @ToolArg(description = "Package ID (required)") String packageId,
            @ToolArg(description = "Version number (default: 1)") Integer version) {
        if (packageId == null || packageId.isBlank()) return errorJson("packageId is required");
        try {
            int ver = version != null ? version : 1;
            PackageConfiguration config = getRestStore(IRestPackageStore.class).readPackage(packageId, ver);
            if (config == null) {
                return errorJson("Package not found: " + packageId + " version " + ver);
            }

            var result = new LinkedHashMap<String, Object>();
            result.put("packageId", packageId);
            result.put("version", ver);
            result.put("extensionCount", config.getPackageExtensions().size());
            result.put("configuration", config);
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            LOGGER.error("MCP read_package failed for package " + packageId, e);
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
        if (resourceType == null || resourceType.isBlank()) return errorJson("resourceType is required");
        if (resourceId == null || resourceId.isBlank()) return errorJson("resourceId is required");
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
            case "behavior" -> getRestStore(IRestBehaviorStore.class).readBehaviorRuleSet(id, version);
            case "langchain" -> getRestStore(IRestLangChainStore.class).readLangChain(id, version);
            case "httpcalls" -> getRestStore(IRestHttpCallsStore.class).readHttpCalls(id, version);
            case "output" -> getRestStore(IRestOutputStore.class).readOutputSet(id, version, "", "", 0, 0);
            case "propertysetter" -> getRestStore(IRestPropertySetterStore.class).readPropertySetter(id, version);
            case "dictionaries" -> getRestStore(IRestRegularDictionaryStore.class)
                    .readRegularDictionary(id, version, "", "", 0, 0);
            default -> throw new IllegalArgumentException("Unknown resource type: " + type +
                    ". Supported: behavior, langchain, httpcalls, output, propertysetter, dictionaries");
        };
    }

    // ==================== Phase 8a.2: Resource CRUD + Batch Cascade ====================

    @Tool(name = "update_resource", description = "Update any EDDI resource configuration by type and ID. " +
            "Returns the new version URI after update. " +
            "Supported types: 'behavior', 'langchain', 'httpcalls', 'output', 'propertysetter', 'dictionaries'.")
    public String updateResource(
            @ToolArg(description = "Resource type: 'behavior', 'langchain', 'httpcalls', 'output', " +
                    "'propertysetter', or 'dictionaries' (required)") String resourceType,
            @ToolArg(description = "Resource ID (required)") String resourceId,
            @ToolArg(description = "Current version number (required)") Integer version,
            @ToolArg(description = "Full JSON configuration body (required)") String config) {
        if (resourceType == null || resourceType.isBlank()) return errorJson("resourceType is required");
        if (resourceId == null || resourceId.isBlank()) return errorJson("resourceId is required");
        if (config == null || config.isBlank()) return errorJson("config is required");
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
        if (resourceType == null || resourceType.isBlank()) return errorJson("resourceType is required");
        if (config == null || config.isBlank()) return errorJson("config is required");
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
        if (resourceType == null || resourceType.isBlank()) return errorJson("resourceType is required");
        if (resourceId == null || resourceId.isBlank()) return errorJson("resourceId is required");
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
                    "status", response.getStatus()
            ));
        } catch (Exception e) {
            LOGGER.error("MCP delete_resource failed for " + resourceType + "/" + resourceId, e);
            return errorJson("Failed to delete resource: " + e.getMessage());
        }
    }

    @Tool(name = "apply_bot_changes", description = "Batch-cascade multiple resource URI changes through " +
            "package → bot in ONE pass. After updating resources with update_resource, use this to wire " +
            "the new versions into the bot's packages and optionally redeploy. " +
            "This replaces ALL URIs in-memory and saves each package/bot ONCE — no wasteful intermediate versions.")
    public String applyBotChanges(
            @ToolArg(description = "Bot ID (required)") String botId,
            @ToolArg(description = "Current bot version (required)") Integer botVersion,
            @ToolArg(description = "JSON array of URI mappings: " +
                    "[{\"oldUri\":\"eddi://...?version=1\",\"newUri\":\"eddi://...?version=2\"}, ...]") String resourceMappings,
            @ToolArg(description = "Redeploy the bot after cascading changes? (default: false)") Boolean redeploy,
            @ToolArg(description = "Environment for redeployment: 'production' (default), 'restricted', or 'test'")
            String environment) {
        if (botId == null || botId.isBlank()) return errorJson("botId is required");
        if (resourceMappings == null || resourceMappings.isBlank()) return errorJson("resourceMappings is required");
        try {
            int ver = botVersion != null ? botVersion : 1;

            // 1. Parse resource mappings
            @SuppressWarnings("unchecked")
            List<Map<String, String>> mappings = jsonSerialization.deserialize(resourceMappings, List.class);
            if (mappings == null || mappings.isEmpty()) {
                return resultJson("no_changes", Map.of("botId", botId, "reason", "Empty resource mappings"));
            }

            // 2. Read bot config
            var botStore = getRestStore(IRestBotStore.class);
            BotConfiguration botConfig = botStore.readBot(botId, ver);
            if (botConfig == null) {
                return errorJson("Bot not found: " + botId + " version " + ver);
            }

            // 3. Process each package
            var pkgStore = getRestStore(IRestPackageStore.class);
            List<URI> originalPackageUris = new ArrayList<>(botConfig.getPackages());
            List<URI> updatedPackageUris = new ArrayList<>();
            int updatedPackageCount = 0;

            for (URI pkgUri : originalPackageUris) {
                String pkgId = extractIdFromLocation(pkgUri.toString());
                int pkgVersion = extractVersionFromLocation(pkgUri.toString());
                if (pkgId == null) {
                    updatedPackageUris.add(pkgUri); // keep as-is
                    continue;
                }

                PackageConfiguration pkgConfig = pkgStore.readPackage(pkgId, pkgVersion);
                if (pkgConfig == null) {
                    updatedPackageUris.add(pkgUri);
                    continue;
                }

                // Replace URIs in package extensions
                boolean packageModified = false;
                for (var ext : pkgConfig.getPackageExtensions()) {
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
                    Response pkgResponse = pkgStore.updatePackage(pkgId, pkgVersion, pkgConfig);
                    String pkgLocation = pkgResponse.getHeaderString("Location");
                    if (pkgLocation != null) {
                        updatedPackageUris.add(URI.create(pkgLocation));
                    } else {
                        // Construct new URI with incremented version
                        int newPkgVersion = pkgVersion + 1;
                        String newPkgUri = pkgUri.toString().replaceAll(
                                "version=\\d+", "version=" + newPkgVersion);
                        updatedPackageUris.add(URI.create(newPkgUri));
                    }
                    updatedPackageCount++;
                } else {
                    updatedPackageUris.add(pkgUri); // unchanged
                }
            }

            // 4. Update bot if any packages changed
            int newBotVersion = ver;
            if (updatedPackageCount > 0) {
                botConfig.setPackages(updatedPackageUris);
                Response botResponse = botStore.updateBot(botId, ver, botConfig);
                String botLocation = botResponse.getHeaderString("Location");
                newBotVersion = botLocation != null
                        ? extractVersionFromLocation(botLocation)
                        : ver + 1;
            }

            // 5. Redeploy if requested
            var result = new LinkedHashMap<String, Object>();
            result.put("botId", botId);
            result.put("previousBotVersion", ver);
            result.put("newBotVersion", newBotVersion);
            result.put("updatedPackages", updatedPackageCount);
            result.put("totalPackages", originalPackageUris.size());
            result.put("mappingsApplied", mappings.size());

            if (Boolean.TRUE.equals(redeploy) && updatedPackageCount > 0) {
                var env = parseEnvironment(environment);
                try {
                    Response deployResponse = botAdmin.deployBot(env, botId, newBotVersion, true, true);
                    result.put("redeployed", deployResponse.getStatus() == 200);
                    result.put("environment", env.name());
                } catch (Exception deployErr) {
                    result.put("redeployed", false);
                    result.put("deployError", "Redeployment failed: " + deployErr.getMessage());
                }
            }

            return resultJson("cascaded", result);
        } catch (Exception e) {
            LOGGER.error("MCP apply_bot_changes failed for bot " + botId, e);
            return errorJson("Failed to apply bot changes: " + e.getMessage());
        }
    }

    @Tool(name = "list_bot_resources", description = "Get a complete inventory of all resources in a bot's pipeline. " +
            "Walks bot → packages → extensions and returns a flat summary with all resource IDs, types, and URIs. " +
            "This is the fastest way to understand a bot's full configuration before making changes.")
    public String listBotResources(
            @ToolArg(description = "Bot ID (required)") String botId,
            @ToolArg(description = "Bot version (default: 1)") Integer version) {
        if (botId == null || botId.isBlank()) return errorJson("botId is required");
        try {
            int ver = version != null ? version : 1;

            // Read bot config
            var botStore = getRestStore(IRestBotStore.class);
            BotConfiguration botConfig = botStore.readBot(botId, ver);
            if (botConfig == null) {
                return errorJson("Bot not found: " + botId + " version " + ver);
            }

            // Read bot name from descriptor
            String botName = null;
            try {
                DocumentDescriptor descriptor = getRestStore(IRestDocumentDescriptorStore.class)
                        .readDescriptor(botId, ver);
                if (descriptor != null) {
                    botName = descriptor.getName();
                }
            } catch (Exception e) {
                LOGGER.debug("Could not read bot descriptor for " + botId, e);
            }

            // Walk packages → extensions
            var pkgStore = getRestStore(IRestPackageStore.class);
            var packages = new ArrayList<Map<String, Object>>();

            for (URI pkgUri : botConfig.getPackages()) {
                String pkgId = extractIdFromLocation(pkgUri.toString());
                int pkgVersion = extractVersionFromLocation(pkgUri.toString());
                if (pkgId == null) continue;

                var pkgInfo = new LinkedHashMap<String, Object>();
                pkgInfo.put("packageId", pkgId);
                pkgInfo.put("packageVersion", pkgVersion);
                pkgInfo.put("packageUri", pkgUri.toString());

                try {
                    PackageConfiguration pkgConfig = pkgStore.readPackage(pkgId, pkgVersion);
                    if (pkgConfig != null) {
                        var extensions = new ArrayList<Map<String, Object>>();
                        for (var ext : pkgConfig.getPackageExtensions()) {
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
            result.put("botId", botId);
            result.put("botVersion", ver);
            if (botName != null) result.put("botName", botName);
            result.put("packageCount", packages.size());
            result.put("packages", packages);
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            LOGGER.error("MCP list_bot_resources failed for bot " + botId, e);
            return errorJson("Failed to list bot resources: " + e.getMessage());
        }
    }

    // ==================== Type Dispatch Helpers ====================

    /**
     * Dispatch resource update to the correct REST store based on type.
     */
    private Response updateResourceByType(String type, String id, int version, String configJson)
            throws IOException {
        return switch (type) {
            case "behavior" -> getRestStore(IRestBehaviorStore.class)
                    .updateBehaviorRuleSet(id, version, jsonSerialization.deserialize(configJson, BehaviorConfiguration.class));
            case "langchain" -> getRestStore(IRestLangChainStore.class)
                    .updateLangChain(id, version, jsonSerialization.deserialize(configJson, LangChainConfiguration.class));
            case "httpcalls" -> getRestStore(IRestHttpCallsStore.class)
                    .updateHttpCalls(id, version, jsonSerialization.deserialize(configJson, HttpCallsConfiguration.class));
            case "output" -> getRestStore(IRestOutputStore.class)
                    .updateOutputSet(id, version, jsonSerialization.deserialize(configJson, OutputConfigurationSet.class));
            case "propertysetter" -> getRestStore(IRestPropertySetterStore.class)
                    .updatePropertySetter(id, version, jsonSerialization.deserialize(configJson, PropertySetterConfiguration.class));
            case "dictionaries" -> getRestStore(IRestRegularDictionaryStore.class)
                    .updateRegularDictionary(id, version, jsonSerialization.deserialize(configJson, RegularDictionaryConfiguration.class));
            default -> throw new IllegalArgumentException("Unknown resource type: " + type +
                    ". Supported: behavior, langchain, httpcalls, output, propertysetter, dictionaries");
        };
    }

    /**
     * Dispatch resource create to the correct REST store based on type.
     */
    private Response createResourceByType(String type, String configJson) throws IOException {
        return switch (type) {
            case "behavior" -> getRestStore(IRestBehaviorStore.class)
                    .createBehaviorRuleSet(jsonSerialization.deserialize(configJson, BehaviorConfiguration.class));
            case "langchain" -> getRestStore(IRestLangChainStore.class)
                    .createLangChain(jsonSerialization.deserialize(configJson, LangChainConfiguration.class));
            case "httpcalls" -> getRestStore(IRestHttpCallsStore.class)
                    .createHttpCalls(jsonSerialization.deserialize(configJson, HttpCallsConfiguration.class));
            case "output" -> getRestStore(IRestOutputStore.class)
                    .createOutputSet(jsonSerialization.deserialize(configJson, OutputConfigurationSet.class));
            case "propertysetter" -> getRestStore(IRestPropertySetterStore.class)
                    .createPropertySetter(jsonSerialization.deserialize(configJson, PropertySetterConfiguration.class));
            case "dictionaries" -> getRestStore(IRestRegularDictionaryStore.class)
                    .createRegularDictionary(jsonSerialization.deserialize(configJson, RegularDictionaryConfiguration.class));
            default -> throw new IllegalArgumentException("Unknown resource type: " + type +
                    ". Supported: behavior, langchain, httpcalls, output, propertysetter, dictionaries");
        };
    }

    /**
     * Dispatch resource delete to the correct REST store based on type.
     */
    private Response deleteResourceByType(String type, String id, int version, boolean permanent) {
        return switch (type) {
            case "behavior" -> getRestStore(IRestBehaviorStore.class).deleteBehaviorRuleSet(id, version, permanent);
            case "langchain" -> getRestStore(IRestLangChainStore.class).deleteLangChain(id, version, permanent);
            case "httpcalls" -> getRestStore(IRestHttpCallsStore.class).deleteHttpCalls(id, version, permanent);
            case "output" -> getRestStore(IRestOutputStore.class).deleteOutputSet(id, version, permanent);
            case "propertysetter" -> getRestStore(IRestPropertySetterStore.class).deletePropertySetter(id, version, permanent);
            case "dictionaries" -> getRestStore(IRestRegularDictionaryStore.class).deleteRegularDictionary(id, version, permanent);
            default -> throw new IllegalArgumentException("Unknown resource type: " + type +
                    ". Supported: behavior, langchain, httpcalls, output, propertysetter, dictionaries");
        };
    }

    /**
     * Map a package extension type URI to the MCP resource type slug.
     * E.g., "eddi://ai.labs.behavior" → "behavior"
     */
    private static String uriToResourceType(String typeUri) {
        if (typeUri == null) return "unknown";
        if (typeUri.contains("behavior")) return "behavior";
        if (typeUri.contains("langchain")) return "langchain";
        if (typeUri.contains("httpcalls")) return "httpcalls";
        if (typeUri.contains("output")) return "output";
        if (typeUri.contains("property")) return "propertysetter";
        if (typeUri.contains("dictionary") || typeUri.contains("parser")) return "dictionaries";
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

    // ── Bot Trigger CRUD ──────────────────────────────────────────────────

    @Tool(name = "list_bot_triggers", description = "List all bot triggers (intent→bot mappings). " +
            "Returns all configured intents with their bot deployments. " +
            "Bot triggers enable intent-based conversation management via chat_managed.")
    public String listBotTriggers() {
        try {
            var triggerStore = getRestStore(IRestBotTriggerStore.class);
            List<BotTriggerConfiguration> triggers = triggerStore.readAllBotTriggers();

            var result = new LinkedHashMap<String, Object>();
            result.put("count", triggers.size());
            result.put("triggers", triggers);
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            LOGGER.error("MCP list_bot_triggers failed", e);
            return errorJson("Failed to list bot triggers: " + e.getMessage());
        }
    }

    @Tool(name = "create_bot_trigger", description = "Create a bot trigger that maps an intent to one or more bots. " +
            "Once created, the intent can be used with chat_managed to talk to the bot. " +
            "The config must include: intent (string) and botDeployments (array of {botId, environment}).")
    public String createBotTrigger(
            @ToolArg(description = "Full JSON configuration: {\"intent\":\"...\",\"botDeployments\":[{\"botId\":\"...\",\"environment\":\"production\"}]} (required)") String config) {
        if (config == null || config.isBlank()) return errorJson("config is required");
        try {
            var triggerStore = getRestStore(IRestBotTriggerStore.class);
            BotTriggerConfiguration triggerConfig = jsonSerialization.deserialize(config, BotTriggerConfiguration.class);

            if (triggerConfig.getIntent() == null || triggerConfig.getIntent().isBlank()) {
                return errorJson("intent is required in config");
            }

            Response response = triggerStore.createBotTrigger(triggerConfig);

            var result = new LinkedHashMap<String, Object>();
            result.put("intent", triggerConfig.getIntent());
            result.put("status", response.getStatus());
            return resultJson("created", result);
        } catch (Exception e) {
            LOGGER.error("MCP create_bot_trigger failed", e);
            return errorJson("Failed to create bot trigger: " + e.getMessage());
        }
    }

    @Tool(name = "update_bot_trigger", description = "Update an existing bot trigger. " +
            "Changes the bot deployments for a given intent.")
    public String updateBotTrigger(
            @ToolArg(description = "Intent to update (required)") String intent,
            @ToolArg(description = "Full JSON configuration: {\"intent\":\"...\",\"botDeployments\":[{\"botId\":\"...\",\"environment\":\"production\"}]} (required)") String config) {
        if (intent == null || intent.isBlank()) return errorJson("intent is required");
        if (config == null || config.isBlank()) return errorJson("config is required");
        try {
            var triggerStore = getRestStore(IRestBotTriggerStore.class);
            BotTriggerConfiguration triggerConfig = jsonSerialization.deserialize(config, BotTriggerConfiguration.class);
            Response response = triggerStore.updateBotTrigger(intent, triggerConfig);

            var result = new LinkedHashMap<String, Object>();
            result.put("intent", intent);
            result.put("status", response.getStatus());
            return resultJson("updated", result);
        } catch (Exception e) {
            LOGGER.error("MCP update_bot_trigger failed for intent " + intent, e);
            return errorJson("Failed to update bot trigger: " + e.getMessage());
        }
    }

    @Tool(name = "delete_bot_trigger", description = "Delete a bot trigger for a given intent.")
    public String deleteBotTrigger(
            @ToolArg(description = "Intent to delete (required)") String intent) {
        if (intent == null || intent.isBlank()) return errorJson("intent is required");
        try {
            var triggerStore = getRestStore(IRestBotTriggerStore.class);
            Response response = triggerStore.deleteBotTrigger(intent);

            var result = new LinkedHashMap<String, Object>();
            result.put("intent", intent);
            result.put("status", response.getStatus());
            return resultJson("deleted", result);
        } catch (Exception e) {
            LOGGER.error("MCP delete_bot_trigger failed for intent " + intent, e);
            return errorJson("Failed to delete bot trigger: " + e.getMessage());
        }
    }

    // ── Schedule Management ───────────────────────────────────────────────

    @Tool(name = "create_schedule", description = "Create a new scheduled bot trigger (cron job or heartbeat). " +
            "For CRON: provide cronExpression. For HEARTBEAT: provide heartbeatIntervalSeconds. " +
            "Returns the created schedule with human-readable description and next fire time.")
    public String createSchedule(
            @ToolArg(description = "Bot ID to trigger (required)") String botId,
            @ToolArg(description = "Trigger type: 'CRON' (default) or 'HEARTBEAT'") String triggerType,
            @ToolArg(description = "5-field cron expression, e.g. '0 9 * * MON-FRI' (required for CRON)") String cron,
            @ToolArg(description = "Heartbeat interval in seconds, e.g. 300 for 5 min (required for HEARTBEAT)") Long heartbeatIntervalSeconds,
            @ToolArg(description = "Message text to send to the bot on each fire (required for CRON, defaults to 'heartbeat' for HEARTBEAT)") String message,
            @ToolArg(description = "Human-readable name for this schedule (required)") String name,
            @ToolArg(description = "IANA time zone, e.g. 'Europe/Vienna' (default: UTC)") String timeZone,
            @ToolArg(description = "Conversation strategy: 'new' or 'persistent' (CRON defaults to 'new', HEARTBEAT defaults to 'persistent')") String conversationStrategy,
            @ToolArg(description = "User identity for the scheduled message (default: 'system:scheduler')") String userId,
            @ToolArg(description = "Environment: 'production' (default), 'restricted', or 'test'") String environment) {
        if (botId == null || botId.isBlank()) return errorJson("botId is required");
        if (name == null || name.isBlank()) return errorJson("name is required");
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
                if (cron == null || cron.isBlank()) return errorJson("cron expression is required for CRON triggers");
                if (message == null || message.isBlank()) return errorJson("message is required for CRON triggers");
                CronParser.validate(cron);
            } else {
                if (heartbeatIntervalSeconds == null || heartbeatIntervalSeconds <= 0) {
                    return errorJson("heartbeatIntervalSeconds is required and must be > 0 for HEARTBEAT triggers");
                }
            }

            var schedule = new ScheduleConfiguration();
            schedule.setName(name);
            schedule.setBotId(botId);
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
            result.put("botId", botId);
            if (cron != null) result.put("cronExpression", cron);
            if (heartbeatIntervalSeconds != null) result.put("heartbeatIntervalSeconds", heartbeatIntervalSeconds);
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

    @Tool(name = "list_schedules", description = "List all scheduled bot triggers. " +
            "Returns schedules with name, type, bot, cron/interval, status, next fire time, and fire count. " +
            "Optionally filter by botId.")
    public String listSchedules(
            @ToolArg(description = "Filter by bot ID (optional)") String botId) {
        try {
            List<ScheduleConfiguration> schedules;
            if (botId != null && !botId.isBlank()) {
                schedules = scheduleStore.readSchedulesByBotId(botId);
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
                item.put("botId", s.getBotId());
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
        if (scheduleId == null || scheduleId.isBlank()) return errorJson("scheduleId is required");
        try {
            var schedule = scheduleStore.readSchedule(scheduleId);
            var fireLogs = scheduleStore.readFireLogs(scheduleId, 10);

            var result = new LinkedHashMap<String, Object>();
            result.put("scheduleId", schedule.getId());
            result.put("name", schedule.getName());
            result.put("triggerType", schedule.getTriggerType() != null ? schedule.getTriggerType().name() : "CRON");
            result.put("botId", schedule.getBotId());
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

    @Tool(name = "delete_schedule", description = "Delete a scheduled bot trigger.")
    public String deleteSchedule(
            @ToolArg(description = "Schedule ID (required)") String scheduleId) {
        if (scheduleId == null || scheduleId.isBlank()) return errorJson("scheduleId is required");
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
        if (scheduleId == null || scheduleId.isBlank()) return errorJson("scheduleId is required");
        try {
            var schedule = scheduleStore.readSchedule(scheduleId);
            ScheduleFireLog fireLog = scheduleFireExecutor.fire(schedule, schedulePollerService.getInstanceId(), 1);

            var result = new LinkedHashMap<String, Object>();
            result.put("scheduleId", scheduleId);
            result.put("scheduleName", schedule.getName());
            result.put("fireStatus", fireLog.status());
            result.put("conversationId", fireLog.conversationId());
            result.put("duration", fireLog.completedAt() != null && fireLog.startedAt() != null
                    ? (fireLog.completedAt().toEpochMilli() - fireLog.startedAt().toEpochMilli()) + "ms" : null);
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
        if (scheduleId == null || scheduleId.isBlank()) return errorJson("scheduleId is required");
        try {
            scheduleStore.requeueDeadLetter(scheduleId);
            return resultJson("schedule_requeued", Map.of(
                    "scheduleId", scheduleId,
                    "status", "PENDING",
                    "message", "Schedule requeued for next poll cycle"
            ));
        } catch (Exception e) {
            LOGGER.error("MCP retry_failed_schedule failed for " + scheduleId, e);
            return errorJson("Failed to retry schedule: " + e.getMessage());
        }
    }
}
