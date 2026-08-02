/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.llm.tools.spi.ToolAssemblyContext;
import ai.labs.eddi.modules.llm.tools.spi.ToolContribution;
import ai.labs.eddi.modules.llm.tools.spi.ToolSourceProvider;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Discovers httpcall configurations from the agent's workflow and builds a
 * {@link ToolSpecification}/{@link ToolExecutor} pair for each {@link ApiCall}
 * (R2 step 2). Extracted from {@code AgentOrchestrator} as a pure move — no
 * behavior change; {@code AgentOrchestrator.discoverHttpCallTools} is kept as a
 * declared delegator (adapting {@link ToolContribution} back to the legacy
 * {@code HttpCallToolsResult} shape) since {@code buildToolSetup}'s merge flow
 * is not yet rewired to iterate {@link ToolSourceProvider}s — that is a
 * separate, later step.
 * <p>
 * Lives in {@code ai.labs.eddi.modules.llm.impl} — the same package as
 * {@code AgentOrchestrator} — rather than a new subpackage, deliberately unlike
 * Wave R's {@code groups} convention: this class depends on
 * {@link WorkflowTraversal}, a package-private shared utility also used by MCP
 * discovery and RAG. Moving to a new package would force widening a utility
 * several unrelated call sites share, for no benefit; staying in-package keeps
 * every dependency at its original visibility.
 */
class HttpCallToolsProvider implements ToolSourceProvider {

    private static final Logger LOGGER = Logger.getLogger(HttpCallToolsProvider.class);
    private static final String HTTPCALLS_TYPE = "eddi://ai.labs.httpcalls";

    /**
     * Keys produced by {@link IMemoryItemConverter#convert} that carry
     * authenticated identity and session state. LLM-provided tool arguments must
     * never override these — a prompt-injection attack could otherwise manipulate
     * userId/agentId in HTTP call templates.
     * <p>
     * This must stay in sync with every top-level namespace
     * {@code MemoryItemConverter#convert} writes. {@code snippets} and {@code vars}
     * were missing (flagged on PR #626): both are added by
     * {@code addSnippetsAndVars}, and {@code vars} in particular carries
     * deployment-wide configuration that httpcall templates read as
     * {@code {vars.<key>}} — leaving it unreserved let a prompt-injected tool
     * argument named {@code vars} shadow the whole namespace for that call.
     */
    private static final Set<String> RESERVED_TEMPLATE_KEYS = Set.of(
            "context", "properties", "memory",
            "snippets", "vars",
            "userInfo", "conversationInfo", "conversationLog");

    private final IRestAgentStore restAgentStore;
    private final IRestWorkflowStore restWorkflowStore;
    private final IResourceClientLibrary resourceClientLibrary;
    private final IApiCallExecutor apiCallExecutor;
    private final IJsonSerialization jsonSerialization;
    private final IMemoryItemConverter memoryItemConverter;

    HttpCallToolsProvider(IRestAgentStore restAgentStore, IRestWorkflowStore restWorkflowStore,
            IResourceClientLibrary resourceClientLibrary, IApiCallExecutor apiCallExecutor,
            IJsonSerialization jsonSerialization, IMemoryItemConverter memoryItemConverter) {
        this.restAgentStore = restAgentStore;
        this.restWorkflowStore = restWorkflowStore;
        this.resourceClientLibrary = resourceClientLibrary;
        this.apiCallExecutor = apiCallExecutor;
        this.jsonSerialization = jsonSerialization;
        this.memoryItemConverter = memoryItemConverter;
    }

    @Override
    public String source() {
        return "http";
    }

    @Override
    public ToolContribution contribute(ToolAssemblyContext ctx) {
        boolean enabled = ctx.task().getEnableHttpCallTools() == null || ctx.task().getEnableHttpCallTools();
        if (!enabled) {
            return ToolContribution.empty();
        }
        return discover(ctx.memory());
    }

    /**
     * Discovers httpcall configurations from the workflow and creates
     * ToolSpecification + ToolExecutor for each ApiCall.
     * <p>
     * Traverses: memory → agentId/version → AgentConfiguration → workflows →
     * WorkflowConfiguration → filter httpcall steps → load ApiCallsConfiguration →
     * create tools from each ApiCall.
     * <p>
     * {@code toolSources} is deliberately empty in the returned contribution —
     * source tagging happens at merge time in {@code AgentOrchestrator
     * .mergeExternalTools}, not at discovery time, exactly as before this move.
     */
    ToolContribution discover(IConversationMemory memory) {
        List<ToolSpecification> toolSpecs = new ArrayList<>();
        Map<String, ToolExecutor> executors = new HashMap<>();
        Map<String, String> endpoints = new HashMap<>();

        try {
            LOGGER.infof("Discovering httpcall tools for agent: %s v%s", memory.getAgentId(), memory.getAgentVersion());

            var stepConfigs = WorkflowTraversal.discoverConfigs(memory, HTTPCALLS_TYPE, ApiCallsConfiguration.class, restAgentStore,
                    restWorkflowStore, resourceClientLibrary);

            for (var stepConfig : stepConfigs) {
                ApiCallsConfiguration httpCallsConfig = stepConfig.config();
                String targetServerUrl = httpCallsConfig.getTargetServerUrl();

                for (ApiCall apiCall : httpCallsConfig.getHttpCalls()) {
                    if (apiCall.getName() == null || apiCall.getName().isBlank()) {
                        continue;
                    }

                    ToolSpecification.Builder specBuilder = ToolSpecification.builder().name(apiCall.getName())
                            .description(apiCall.getDescription() != null ? apiCall.getDescription() : "Execute " + apiCall.getName());

                    if (apiCall.getParameters() != null && !apiCall.getParameters().isEmpty()) {
                        var schemaBuilder = dev.langchain4j.model.chat.request.json.JsonObjectSchema.builder();
                        for (var param : apiCall.getParameters().entrySet()) {
                            schemaBuilder.addStringProperty(param.getKey(), param.getValue() != null ? param.getValue() : param.getKey());
                        }
                        schemaBuilder.required(new ArrayList<>(apiCall.getParameters().keySet()));
                        specBuilder.parameters(schemaBuilder.build());
                    }

                    toolSpecs.add(specBuilder.build());

                    // Record what this tool actually calls, so approval patterns can be
                    // written against the endpoint rather than the generated name.
                    var apiRequest = apiCall.getRequest();
                    if (apiRequest != null && apiRequest.getMethod() != null && apiRequest.getPath() != null) {
                        endpoints.put(apiCall.getName(),
                                apiRequest.getMethod().toLowerCase(Locale.ROOT) + ":" + normalizeEndpointPath(apiRequest.getPath()));
                    }

                    executors.put(apiCall.getName(), (toolRequest, memoryId) -> {
                        try {
                            Map<String, Object> templateData = memoryItemConverter.convert(memory);

                            if (toolRequest.arguments() != null && !toolRequest.arguments().isBlank()) {
                                try {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> args = jsonSerialization.deserialize(toolRequest.arguments(), Map.class);
                                    safeTemplateMerge(templateData, args);
                                } catch (IOException e) {
                                    // The argument payload itself is deliberately not logged: it is
                                    // model-generated from conversation content and routinely carries
                                    // user identifiers, free text, or credentials destined for an
                                    // httpcall body. Length is enough to tell truncation from
                                    // malformed JSON, which is what this warning is actually for.
                                    LOGGER.warnf("Failed to parse tool arguments for httpcall tool '%s' (%d chars): %s",
                                            apiCall.getName(), toolRequest.arguments().length(), e.getMessage());
                                }
                            }

                            Map<String, Object> result = apiCallExecutor.execute(apiCall, memory, templateData, targetServerUrl);

                            String serialized = jsonSerialization.serialize(result);
                            LOGGER.info("Httpcall tool '" + apiCall.getName() + "' result: keys=" + result.keySet() + " size=" + serialized.length());
                            return serialized;
                        } catch (Exception e) {
                            LOGGER.error("Error executing httpcall tool '" + apiCall.getName() + "'", e);
                            String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
                            String escaped = new String(JsonStringEncoder.getInstance().quoteAsString(errorMsg));
                            return "{\"error\": \"" + escaped + "\"}";
                        }
                    });
                }
            }

            LOGGER.info("Discovered " + toolSpecs.size() + " httpcall tools from workflow");
        } catch (Exception e) {
            LOGGER.warn("Failed to discover httpcall tools from workflow", e);
        }

        return new ToolContribution(toolSpecs, executors, Map.of(), endpoints);
    }

    /**
     * Normalise a configured path to the shape an approval pattern is written in.
     * <p>
     * The config accepts three shapes for the same endpoint — {@code /a/b},
     * {@code a/b}, and an absolute {@code https://host/a/b} (see
     * {@code ApiCallExecutor#buildRequest}, which applies the same leading-slash
     * rule). Storing the raw value would make {@code http.post:/a/b} miss two of
     * them, and a require-pattern that misses is an ungated write.
     * <p>
     * An absolute URL keeps only its path, so the same pattern matches however the
     * target server was configured.
     */
    static String normalizeEndpointPath(String rawPath) {
        String path = rawPath.trim();
        // Request.path defaults to "", and an empty path means the target server's
        // root — ApiCallExecutor sends the request to targetServerUrl unchanged.
        // Returning "" here would make the key "post:", which no pattern can match,
        // so a root endpoint would be silently ungateable.
        if (path.isEmpty()) {
            return "/";
        }
        // Only a real absolute URL, not merely a path that happens to begin "http".
        // ApiCallExecutor tests startsWith("http"), which is loose enough that a
        // relative path like "httpcalls/agents" would be parsed as a URI here — and an
        // opaque one collapses to an empty path, losing the endpoint entirely.
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            try {
                String extracted = URI.create(path).getPath();
                // A URL with no path ("https://host", "https://host?x=1") yields an
                // empty path. Left empty the endpoint key would be "post:", which no
                // pattern expecting a leading slash can match — and an unmatched
                // require-pattern is an ungated call. A root endpoint is "/".
                path = extracted == null || extracted.isEmpty() ? "/" : extracted;
            } catch (IllegalArgumentException e) {
                // Not parseable as a URI — a templated host, most likely. Leave it be:
                // matching something odd is better than throwing during discovery.
                LOGGER.debugf("Could not normalise endpoint path '%s' for approval matching", sanitize(rawPath));
                return path;
            }
        }
        if (!path.isEmpty() && !path.startsWith("/")) {
            path = "/" + path;
        }
        return path;
    }

    /**
     * Merge LLM tool arguments into template data, blocking any keys that collide
     * with internal pipeline data. Blocked keys are logged as warnings so config
     * authors can rename their parameters if needed.
     * <p>
     * Package-private (not private) so {@code AgentOrchestrator}'s declared
     * delegator — kept for the reflection-based characterization test — can call
     * it.
     */
    static void safeTemplateMerge(Map<String, Object> templateData, Map<String, Object> args) {
        for (var entry : args.entrySet()) {
            if (RESERVED_TEMPLATE_KEYS.contains(entry.getKey())) {
                LOGGER.warnf("Blocked LLM tool argument '%s' — collides with reserved template key. " +
                        "Rename the httpcall parameter to avoid this conflict.", entry.getKey());
                continue;
            }
            templateData.put(entry.getKey(), entry.getValue());
        }
    }
}
