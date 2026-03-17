package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.behavior.IRestBehaviorStore;
import ai.labs.eddi.configs.behavior.model.BehaviorConfiguration;
import ai.labs.eddi.configs.behavior.model.BehaviorGroupConfiguration;
import ai.labs.eddi.configs.behavior.model.BehaviorRuleConditionConfiguration;
import ai.labs.eddi.configs.behavior.model.BehaviorRuleConfiguration;
import ai.labs.eddi.configs.bots.IRestBotStore;
import ai.labs.eddi.configs.bots.model.BotConfiguration;
import ai.labs.eddi.configs.documentdescriptor.IRestDocumentDescriptorStore;
import ai.labs.eddi.configs.documentdescriptor.model.DocumentDescriptor;
import ai.labs.eddi.configs.patch.PatchInstruction;
import ai.labs.eddi.configs.langchain.IRestLangChainStore;
import ai.labs.eddi.configs.output.IRestOutputStore;
import ai.labs.eddi.configs.output.model.OutputConfiguration;
import ai.labs.eddi.configs.output.model.OutputConfigurationSet;
import ai.labs.eddi.configs.packages.IRestPackageStore;
import ai.labs.eddi.configs.packages.model.PackageConfiguration;
import ai.labs.eddi.engine.IRestBotAdministration;
import ai.labs.eddi.modules.langchain.model.LangChainConfiguration;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.*;

import static ai.labs.eddi.engine.mcp.McpToolUtils.*;

/**
 * MCP composite tool for setting up a fully working bot in a single call.
 * Codifies the Bot Father's 12-step pipeline as a programmatic Java operation.
 *
 * @author ginccc
 */
@ApplicationScoped
public class McpSetupTools {

    private static final Logger LOGGER = Logger.getLogger(McpSetupTools.class);

    private final IRestBehaviorStore behaviorStore;
    private final IRestLangChainStore langchainStore;
    private final IRestOutputStore outputStore;
    private final IRestPackageStore packageStore;
    private final IRestBotStore botStore;
    private final IRestDocumentDescriptorStore descriptorStore;
    private final IRestBotAdministration botAdmin;
    private final IJsonSerialization jsonSerialization;

    @Inject
    public McpSetupTools(IRestBehaviorStore behaviorStore,
                         IRestLangChainStore langchainStore,
                         IRestOutputStore outputStore,
                         IRestPackageStore packageStore,
                         IRestBotStore botStore,
                         IRestDocumentDescriptorStore descriptorStore,
                         IRestBotAdministration botAdmin,
                         IJsonSerialization jsonSerialization) {
        this.behaviorStore = behaviorStore;
        this.langchainStore = langchainStore;
        this.outputStore = outputStore;
        this.packageStore = packageStore;
        this.botStore = botStore;
        this.descriptorStore = descriptorStore;
        this.botAdmin = botAdmin;
        this.jsonSerialization = jsonSerialization;
    }

    @Tool(name = "setup_bot",
            description = "Create a fully working, deployed bot in a single call. " +
                    "This creates all necessary resources (behavior rules, LLM connection, " +
                    "output set, package, bot), names them, and optionally deploys the bot. " +
                    "This is the fastest way to get a new bot running — equivalent to the Bot Father workflow.")
    public String setupBot(
            @ToolArg(description = "Bot name (required)") String name,
            @ToolArg(description = "System prompt / role for the LLM (required). " +
                    "Describes the bot's personality and purpose.") String systemPrompt,
            @ToolArg(description = "LLM provider type: 'openai' (default), 'anthropic', or 'gemini'")
            String model,
            @ToolArg(description = "Model name, e.g. 'gpt-4o' (default), 'claude-3-5-sonnet', 'gemini-1.5-pro'")
            String modelName,
            @ToolArg(description = "API key for the LLM provider (required). " +
                    "Can be a vault reference like '${vault:openai-key}'") String apiKey,
            @ToolArg(description = "Greeting message shown when a conversation starts (optional)")
            String introMessage,
            @ToolArg(description = "Enable built-in tools like calculator, datetime, websearch? (default: false)")
            Boolean enableBuiltInTools,
            @ToolArg(description = "Comma-separated list of specific built-in tools to enable " +
                    "(e.g. 'calculator,datetime,websearch'). Only used if enableBuiltInTools is true.")
            String builtInToolsWhitelist,
            @ToolArg(description = "Automatically deploy the bot after creation? (default: true)")
            Boolean deploy,
            @ToolArg(description = "Environment: 'unrestricted' (default), 'restricted', or 'test'")
            String environment) {
        try {
            // Validate required params
            if (name == null || name.isBlank()) {
                return errorJson("Bot name is required");
            }
            if (systemPrompt == null || systemPrompt.isBlank()) {
                return errorJson("System prompt is required");
            }
            if (apiKey == null || apiKey.isBlank()) {
                return errorJson("API key is required");
            }

            String modelType = model != null && !model.isBlank() ? model.trim().toLowerCase() : "openai";
            String modelId = modelName != null && !modelName.isBlank() ? modelName.trim() : "gpt-4o";
            boolean shouldDeploy = deploy == null || deploy;
            boolean toolsEnabled = enableBuiltInTools != null && enableBuiltInTools;
            var env = parseEnvironment(environment);

            var createdResources = new LinkedHashMap<String, String>(); // track for result

            // --- Step 1: Create Behavior Rules ---
            var behaviorConfig = createBehaviorConfig();
            Response behaviorResponse = behaviorStore.createBehaviorRuleSet(behaviorConfig);
            String behaviorLocation = behaviorResponse.getHeaderString("Location");
            String behaviorId = extractIdFromLocation(behaviorLocation);
            int behaviorVersion = extractVersionFromLocation(behaviorLocation);
            createdResources.put("behaviorLocation", behaviorLocation);
            patchDescriptor(behaviorId, behaviorVersion, name);

            // --- Step 2: Create LangChain Configuration ---
            var langchainConfig = createLangchainConfig(
                    modelType, modelId, apiKey, systemPrompt, toolsEnabled, builtInToolsWhitelist);
            Response langchainResponse = langchainStore.createLangChain(langchainConfig);
            String langchainLocation = langchainResponse.getHeaderString("Location");
            String langchainId = extractIdFromLocation(langchainLocation);
            int langchainVersion = extractVersionFromLocation(langchainLocation);
            createdResources.put("langchainLocation", langchainLocation);
            patchDescriptor(langchainId, langchainVersion, name);

            // --- Step 3: Create Output Set (if intro message provided) ---
            String outputLocation = null;
            if (introMessage != null && !introMessage.isBlank()) {
                var outputConfig = createOutputConfig(introMessage);
                Response outputResponse = outputStore.createOutputSet(outputConfig);
                outputLocation = outputResponse.getHeaderString("Location");
                String outputId = extractIdFromLocation(outputLocation);
                int outputVersion = extractVersionFromLocation(outputLocation);
                createdResources.put("outputLocation", outputLocation);
                patchDescriptor(outputId, outputVersion, name);
            }

            // --- Step 4: Create Package ---
            var packageConfig = createPackageConfig(behaviorLocation, langchainLocation, outputLocation);
            Response packageResponse = packageStore.createPackage(packageConfig);
            String packageLocation = packageResponse.getHeaderString("Location");
            String packageId = extractIdFromLocation(packageLocation);
            int packageVersion = extractVersionFromLocation(packageLocation);
            createdResources.put("packageLocation", packageLocation);
            patchDescriptor(packageId, packageVersion, name);

            // --- Step 5: Create Bot ---
            var botConfig = new BotConfiguration();
            botConfig.setPackages(List.of(URI.create(packageLocation)));
            Response botResponse = botStore.createBot(botConfig);
            String botLocation = botResponse.getHeaderString("Location");
            String botId = extractIdFromLocation(botLocation);
            int botVersion = extractVersionFromLocation(botLocation);
            createdResources.put("botLocation", botLocation);
            patchDescriptor(botId, botVersion, name);

            // --- Step 6: Deploy ---
            if (shouldDeploy && botId != null) {
                try {
                    botAdmin.deployBot(env, botId, botVersion, true);
                    createdResources.put("deployed", "true");
                    createdResources.put("environment", env.name());
                } catch (Exception deployError) {
                    LOGGER.warn("MCP setup_bot: bot created but deploy failed for " + botId, deployError);
                    createdResources.put("deployed", "false");
                    createdResources.put("deployError", deployError.getMessage());
                }
            }

            var result = new LinkedHashMap<String, Object>();
            result.put("action", "setup_complete");
            result.put("botId", botId != null ? botId : "unknown");
            result.put("botName", name);
            result.put("model", modelType + "/" + modelId);
            result.put("resources", createdResources);
            return jsonSerialization.serialize(result);

        } catch (Exception e) {
            LOGGER.error("MCP setup_bot failed", e);
            return errorJson("Failed to set up bot: " + e.getMessage());
        }
    }

    // --- Config Builders ---

    /**
     * Create behavior rules: catch-all inputmatcher(*) → send_message action.
     * This is the standard Bot Father pattern.
     */
    BehaviorConfiguration createBehaviorConfig() {
        var condition = new BehaviorRuleConditionConfiguration();
        condition.setType("inputmatcher");
        condition.setConfigs(Map.of("expressions", "*"));

        var rule = new BehaviorRuleConfiguration();
        rule.setName("Send Message to LLM");
        rule.setActions(List.of("send_message"));
        rule.setConditions(List.of(condition));

        var group = new BehaviorGroupConfiguration();
        group.setBehaviorRules(List.of(rule));

        var config = new BehaviorConfiguration();
        config.setExpressionsAsActions(true);
        config.setBehaviorGroups(List.of(group));
        return config;
    }

    /**
     * Create LangChain config with the specified model, system prompt, and tool settings.
     */
    LangChainConfiguration createLangchainConfig(String modelType, String modelId,
                                                   String apiKey, String systemPrompt,
                                                   boolean enableTooling, String toolsWhitelist) {
        var task = new LangChainConfiguration.Task();
        task.setActions(List.of("send_message"));
        task.setId(modelType);
        task.setType(modelType);
        task.setDescription("LLM integration via " + modelType);

        var params = new LinkedHashMap<String, String>();
        params.put("systemMessage", systemPrompt);
        params.put("addToOutput", "true");
        params.put("apiKey", apiKey);
        params.put("modelName", modelId);
        params.put("timeout", "60");
        params.put("temperature", "0.3");
        params.put("logRequests", "true");
        params.put("logResponses", "true");
        task.setParameters(params);

        if (enableTooling) {
            task.setEnableBuiltInTools(true);
            if (toolsWhitelist != null && !toolsWhitelist.isBlank()) {
                task.setBuiltInToolsWhitelist(
                        List.of(toolsWhitelist.split(",")).stream()
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .toList());
            }
        }

        task.setConversationHistoryLimit(10);
        return new LangChainConfiguration(List.of(task));
    }

    /**
     * Create output set with a CONVERSATION_START intro message.
     */
    OutputConfigurationSet createOutputConfig(String introMessage) {
        var textItem = new TextOutputItem(introMessage, 0);

        var output = new OutputConfiguration.Output();
        output.setValueAlternatives(List.of(textItem));

        var outputEntry = new OutputConfiguration();
        outputEntry.setAction("CONVERSATION_START");
        outputEntry.setTimesOccurred(0);
        outputEntry.setOutputs(List.of(output));

        var outputSet = new OutputConfigurationSet();
        outputSet.setOutputSet(List.of(outputEntry));
        return outputSet;
    }

    /**
     * Create package with parser + behavior + langchain [+ output] pipeline.
     */
    PackageConfiguration createPackageConfig(String behaviorLocation,
                                              String langchainLocation,
                                              String outputLocation) {
        var extensions = new ArrayList<PackageConfiguration.PackageExtension>();

        // Parser (always first in pipeline)
        var parser = new PackageConfiguration.PackageExtension();
        parser.setType(URI.create("eddi://ai.labs.parser"));
        extensions.add(parser);

        // Behavior rules
        var behavior = new PackageConfiguration.PackageExtension();
        behavior.setType(URI.create("eddi://ai.labs.behavior"));
        behavior.setConfig(Map.of("uri", behaviorLocation));
        extensions.add(behavior);

        // LangChain
        var langchain = new PackageConfiguration.PackageExtension();
        langchain.setType(URI.create("eddi://ai.labs.langchain"));
        langchain.setConfig(Map.of("uri", langchainLocation));
        extensions.add(langchain);

        // Output (optional)
        if (outputLocation != null) {
            var output = new PackageConfiguration.PackageExtension();
            output.setType(URI.create("eddi://ai.labs.output"));
            output.setConfig(Map.of("uri", outputLocation));
            extensions.add(output);
        }

        var config = new PackageConfiguration();
        config.setPackageExtensions(extensions);
        return config;
    }

    /**
     * Patch a resource descriptor with the bot name.
     */
    private void patchDescriptor(String id, int version, String name) {
        if (id == null) return;
        try {
            var descriptor = new DocumentDescriptor();
            descriptor.setName(name);

            var patch = new PatchInstruction<DocumentDescriptor>();
            patch.setOperation(PatchInstruction.PatchOperation.SET);
            patch.setDocument(descriptor);
            descriptorStore.patchDescriptor(id, version, patch);
        } catch (Exception e) {
            LOGGER.warn("MCP setup_bot: failed to patch descriptor for " + id, e);
        }
    }
}
