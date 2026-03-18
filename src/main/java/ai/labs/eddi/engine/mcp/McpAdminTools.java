package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.bots.IRestBotStore;
import ai.labs.eddi.configs.bots.model.BotConfiguration;
import ai.labs.eddi.configs.documentdescriptor.IRestDocumentDescriptorStore;
import ai.labs.eddi.configs.documentdescriptor.model.DocumentDescriptor;
import ai.labs.eddi.configs.packages.IRestPackageStore;
import ai.labs.eddi.configs.patch.PatchInstruction;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.IRestBotAdministration;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.engine.runtime.client.factory.RestInterfaceFactory;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.engine.mcp.McpToolUtils.*;

/**
 * MCP tools for administering EDDI bots and packages.
 * Exposes deploy, undeploy, list packages, create/delete bot
 * as MCP-compliant tools via the Quarkus MCP Server extension.
 *
 * <p>Phase 8a — Item 36: Admin API MCP Server (3 SP)
 *
 * @author ginccc
 */
@ApplicationScoped
public class McpAdminTools {

    private static final Logger LOGGER = Logger.getLogger(McpAdminTools.class);

    private final IRestInterfaceFactory restInterfaceFactory;
    private final IRestBotAdministration botAdmin;
    private final IJsonSerialization jsonSerialization;

    @Inject
    public McpAdminTools(IRestInterfaceFactory restInterfaceFactory,
                         IRestBotAdministration botAdmin,
                         IJsonSerialization jsonSerialization) {
        this.restInterfaceFactory = restInterfaceFactory;
        this.botAdmin = botAdmin;
        this.jsonSerialization = jsonSerialization;
    }

    @Tool(name = "deploy_bot",
            description = "Deploy a bot to an environment. The bot must exist and have a valid configuration. " +
                    "Returns the deployment status.")
    public String deployBot(
            @ToolArg(description = "Bot ID (required)") String botId,
            @ToolArg(description = "Version number to deploy (required)") Integer version,
            @ToolArg(description = "Environment: 'unrestricted' (default), 'restricted', or 'test'")
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
                        result.put("deployed", "READY".equals(deployStatus));
                    }
                } catch (Exception parseError) {
                    result.put("deployed", false);
                    result.put("parseWarning", "Could not read deployment status from response");
                }
            } else if (httpStatus == 202) {
                result.put("deployed", "pending");
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
            @ToolArg(description = "Environment: 'unrestricted' (default), 'restricted', or 'test'")
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
            @ToolArg(description = "Environment: 'unrestricted' (default), 'restricted', or 'test'")
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


    private String resultJson(String action, Map<String, Object> data) {
        try {
            var result = new LinkedHashMap<>(data);
            result.put("action", action);
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            return "{\"action\":\"" + escapeJsonString(action) + "\",\"status\":\"completed\"}";
        }
    }

    private <T> T getRestStore(Class<T> clazz) {
        try {
            return restInterfaceFactory.get(clazz);
        } catch (RestInterfaceFactory.RestInterfaceFactoryException e) {
            throw new RuntimeException("Failed to get REST proxy for " + clazz.getSimpleName(), e);
        }
    }
}
