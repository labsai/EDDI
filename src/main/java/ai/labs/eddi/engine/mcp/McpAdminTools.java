package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.bots.IRestBotStore;
import ai.labs.eddi.configs.bots.model.BotConfiguration;
import ai.labs.eddi.configs.documentdescriptor.model.DocumentDescriptor;
import ai.labs.eddi.configs.packages.IRestPackageStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.IRestBotAdministration;
import ai.labs.eddi.engine.model.Deployment.Environment;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    private final IRestBotAdministration botAdmin;
    private final IRestBotStore botStore;
    private final IRestPackageStore packageStore;
    private final IJsonSerialization jsonSerialization;

    @Inject
    public McpAdminTools(IRestBotAdministration botAdmin,
                         IRestBotStore botStore,
                         IRestPackageStore packageStore,
                         IJsonSerialization jsonSerialization) {
        this.botAdmin = botAdmin;
        this.botStore = botStore;
        this.packageStore = packageStore;
        this.jsonSerialization = jsonSerialization;
    }

    @Tool(description = "Deploy a bot to an environment. The bot must exist and have a valid configuration. " +
            "Returns the deployment status.")
    public String deployBot(
            @ToolArg(description = "Bot ID (required)") String botId,
            @ToolArg(description = "Version number to deploy (required)") String version,
            @ToolArg(description = "Environment: 'unrestricted' (default), 'restricted', or 'test'")
            String environment) {
        try {
            var env = parseEnvironment(environment);
            int ver = Integer.parseInt(version.trim());
            Response response = botAdmin.deployBot(env, botId, ver, true);
            return resultJson("deployed", Map.of(
                    "botId", botId,
                    "version", ver,
                    "environment", env.name(),
                    "status", response.getStatus()
            ));
        } catch (Exception e) {
            LOGGER.error("MCP deployBot failed for bot " + botId, e);
            return errorJson("Failed to deploy bot: " + e.getMessage());
        }
    }

    @Tool(description = "Undeploy a bot from an environment. Optionally end all active conversations.")
    public String undeployBot(
            @ToolArg(description = "Bot ID (required)") String botId,
            @ToolArg(description = "Version number to undeploy (required)") String version,
            @ToolArg(description = "Environment: 'unrestricted' (default), 'restricted', or 'test'")
            String environment,
            @ToolArg(description = "End all active conversations? 'true' or 'false' (default: false)")
            String endConversations) {
        try {
            var env = parseEnvironment(environment);
            int ver = Integer.parseInt(version.trim());
            boolean endAll = "true".equalsIgnoreCase(endConversations);
            Response response = botAdmin.undeployBot(env, botId, ver, endAll, false);
            return resultJson("undeployed", Map.of(
                    "botId", botId,
                    "version", ver,
                    "environment", env.name(),
                    "endedConversations", endAll,
                    "status", response.getStatus()
            ));
        } catch (Exception e) {
            LOGGER.error("MCP undeployBot failed for bot " + botId, e);
            return errorJson("Failed to undeploy bot: " + e.getMessage());
        }
    }

    @Tool(description = "Get the deployment status of a specific bot version in an environment.")
    public String getDeploymentStatus(
            @ToolArg(description = "Bot ID (required)") String botId,
            @ToolArg(description = "Version number (required)") String version,
            @ToolArg(description = "Environment: 'unrestricted' (default), 'restricted', or 'test'")
            String environment) {
        try {
            var env = parseEnvironment(environment);
            int ver = Integer.parseInt(version.trim());
            Response response = botAdmin.getDeploymentStatus(env, botId, ver, "json");
            Object entity = response.getEntity();
            return jsonSerialization.serialize(entity);
        } catch (Exception e) {
            LOGGER.error("MCP getDeploymentStatus failed for bot " + botId, e);
            return errorJson("Failed to get deployment status: " + e.getMessage());
        }
    }

    @Tool(description = "List all packages (pipeline configurations). " +
            "Returns a JSON array of package descriptors with name, description, and IDs.")
    public String listPackages(
            @ToolArg(description = "Optional filter string to search package names") String filter,
            @ToolArg(description = "Maximum number of results (default 20)") String limit) {
        try {
            int limitInt = parseIntOrDefault(limit, 20);
            String filterStr = filter != null ? filter : "";
            List<DocumentDescriptor> descriptors = packageStore.readPackageDescriptors(filterStr, 0, limitInt);
            return jsonSerialization.serialize(descriptors);
        } catch (Exception e) {
            LOGGER.error("MCP listPackages failed", e);
            return errorJson("Failed to list packages: " + e.getMessage());
        }
    }

    @Tool(description = "Create a new bot with a given name and optional packages. " +
            "Returns the Location URI of the newly created bot.")
    public String createBot(
            @ToolArg(description = "Bot name (required)") String name,
            @ToolArg(description = "Bot description (optional)") String description,
            @ToolArg(description = "Comma-separated list of package URIs to include (optional)") String packageUris) {
        try {
            var botConfig = new BotConfiguration();
            // Set packages if provided (package URIs like: eddi://ai.labs.package/packagestore/packages/ID?version=1)
            if (packageUris != null && !packageUris.isBlank()) {
                var uris = new ArrayList<java.net.URI>();
                for (String uri : packageUris.split(",")) {
                    uris.add(java.net.URI.create(uri.trim()));
                }
                botConfig.setPackages(uris);
            }

            Response response = botStore.createBot(botConfig);
            String location = response.getHeaderString("Location");
            return resultJson("created", Map.of(
                    "name", name != null ? name : "",
                    "location", location != null ? location : "unknown",
                    "status", response.getStatus()
            ));
        } catch (Exception e) {
            LOGGER.error("MCP createBot failed", e);
            return errorJson("Failed to create bot: " + e.getMessage());
        }
    }

    @Tool(description = "Delete a bot. Optionally cascade-delete all referenced packages and resources.")
    public String deleteBot(
            @ToolArg(description = "Bot ID (required)") String botId,
            @ToolArg(description = "Version number (required)") String version,
            @ToolArg(description = "Permanently delete? 'true' or 'false' (default: false)")
            String permanent,
            @ToolArg(description = "Cascade-delete packages and resources? 'true' or 'false' (default: false)")
            String cascade) {
        try {
            int ver = Integer.parseInt(version.trim());
            boolean isPermanent = "true".equalsIgnoreCase(permanent);
            boolean isCascade = "true".equalsIgnoreCase(cascade);
            Response response = botStore.deleteBot(botId, ver, isPermanent, isCascade);
            return resultJson("deleted", Map.of(
                    "botId", botId,
                    "version", ver,
                    "permanent", isPermanent,
                    "cascade", isCascade,
                    "status", response.getStatus()
            ));
        } catch (Exception e) {
            LOGGER.error("MCP deleteBot failed for bot " + botId, e);
            return errorJson("Failed to delete bot: " + e.getMessage());
        }
    }

    // --- helpers ---

    private Environment parseEnvironment(String environment) {
        if (environment == null || environment.isBlank()) {
            return Environment.unrestricted;
        }
        try {
            return Environment.valueOf(environment.trim().toLowerCase());
        } catch (IllegalArgumentException e) {
            return Environment.unrestricted;
        }
    }

    private int parseIntOrDefault(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String resultJson(String action, Map<String, Object> data) {
        try {
            var result = new java.util.LinkedHashMap<>(data);
            result.put("action", action);
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            return "{\"action\":\"" + action + "\",\"status\":\"completed\"}";
        }
    }

    private String errorJson(String message) {
        return "{\"error\":\"" + message.replace("\"", "'").replace("\n", " ") + "\"}";
    }
}
