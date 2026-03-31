package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.model.LlmConfiguration.A2AAgentConfig;
import ai.labs.eddi.secrets.SecretResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * Discovers remote A2A agents and wraps their skills as
 * {@link ToolSpecification}s, mirroring the {@link McpToolProviderManager}
 * pattern. Remote agents are called via A2A {@code tasks/send} JSON-RPC.
 *
 * @author ginccc
 */
@ApplicationScoped
public class A2AToolProviderManager {

    private static final Logger LOGGER = Logger.getLogger(A2AToolProviderManager.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SecretResolver secretResolver;
    private final HttpClient httpClient;

    /** Cached Agent Card data per URL to avoid re-fetching on every request. */
    private final Map<String, CachedAgentInfo> agentCache = new ConcurrentHashMap<>();

    record CachedAgentInfo(Map<String, Object> agentCard, long timestamp) {
    }

    /** Circuit breaker state — tracks consecutive failures per agent URL. */
    record CircuitState(int failures, long lastFailure) {
    }

    private static final int CIRCUIT_BREAKER_THRESHOLD = 3;
    private static final long CIRCUIT_BREAKER_COOLDOWN_MS = 60_000;
    private static final int MAX_RESPONSE_SIZE_BYTES = 1_048_576; // 1MB

    private final Map<String, CircuitState> circuitBreakers = new ConcurrentHashMap<>();

    record A2AToolsResult(List<ToolSpecification> toolSpecs, Map<String, ToolExecutor> executors) {
    }

    @Inject
    public A2AToolProviderManager(SecretResolver secretResolver) {
        this.secretResolver = secretResolver;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /**
     * Discover tools from configured A2A agents.
     */
    public A2AToolsResult discoverTools(List<A2AAgentConfig> a2aAgents) {
        List<ToolSpecification> toolSpecs = new ArrayList<>();
        Map<String, ToolExecutor> executors = new HashMap<>();

        if (a2aAgents == null || a2aAgents.isEmpty()) {
            return new A2AToolsResult(toolSpecs, executors);
        }

        for (A2AAgentConfig config : a2aAgents) {
            if (isNullOrEmpty(config.getUrl())) {
                LOGGER.warnf("Skipping A2A agent config with empty URL");
                continue;
            }

            try {
                // Circuit breaker check
                if (isCircuitOpen(config.getUrl())) {
                    LOGGER.warnf("Circuit breaker open for A2A agent at %s — skipping discovery", config.getUrl());
                    continue;
                }

                discoverAgentTools(config, toolSpecs, executors);
                // Reset circuit on success
                circuitBreakers.remove(config.getUrl());
            } catch (Exception e) {
                recordFailure(config.getUrl());
                LOGGER.warnf("Failed to discover tools from A2A agent at %s: %s", config.getUrl(), e.getMessage());
            }
        }

        return new A2AToolsResult(toolSpecs, executors);
    }

    /** Number of cached agent connections. */
    public int getActiveConnectionCount() {
        return agentCache.size();
    }

    /** Clear cached agent info. */
    public void shutdown() {
        agentCache.clear();
    }

    // === Internal ===

    @SuppressWarnings("unchecked")
    private void discoverAgentTools(A2AAgentConfig config, List<ToolSpecification> toolSpecs, Map<String, ToolExecutor> executors) throws Exception {

        // Warn once at discovery time if raw key is used
        if (!isNullOrEmpty(config.getApiKey())) {
            warnIfRawKey(config.getApiKey(), config.getUrl());
        }

        String agentUrl = config.getUrl().endsWith("/") ? config.getUrl().substring(0, config.getUrl().length() - 1) : config.getUrl();

        Map<String, Object> agentCard = fetchAgentCard(agentUrl, config);
        if (agentCard == null) {
            LOGGER.warnf("No Agent Card found at %s", agentUrl);
            return;
        }

        String agentName = config.getName() != null ? config.getName() : (String) agentCard.getOrDefault("name", "a2a-agent");

        // Build the parameter schema for the "message" parameter
        JsonObjectSchema paramSchema = JsonObjectSchema.builder().addStringProperty("message", "The message to send to the agent").build();

        List<Map<String, Object>> skills = (List<Map<String, Object>>) agentCard.get("skills");
        if (skills == null || skills.isEmpty()) {
            // Single default tool for the entire agent
            String toolName = sanitizeToolName(agentName);
            String desc = (String) agentCard.getOrDefault("description", "Remote A2A agent: " + agentName);

            ToolSpecification spec = ToolSpecification.builder().name(toolName).description(desc).parameters(paramSchema).build();
            toolSpecs.add(spec);
            executors.put(toolName, createA2AToolExecutor(agentUrl, config));
            return;
        }

        // Create a tool for each skill
        for (Map<String, Object> skill : skills) {
            String skillId = (String) skill.getOrDefault("id", "skill");
            String skillName = (String) skill.getOrDefault("name", skillId);
            String skillDesc = (String) skill.getOrDefault("description", "Skill: " + skillName);

            // Apply skills filter if configured
            if (config.getSkillsFilter() != null && !config.getSkillsFilter().isEmpty()) {
                if (!config.getSkillsFilter().contains(skillId) && !config.getSkillsFilter().contains(skillName)) {
                    continue;
                }
            }

            String toolName = sanitizeToolName(agentName + "_" + skillId);

            ToolSpecification spec = ToolSpecification.builder().name(toolName).description(skillDesc + " (via A2A agent: " + agentName + ")")
                    .parameters(paramSchema).build();

            toolSpecs.add(spec);
            executors.put(toolName, createA2AToolExecutor(agentUrl, config));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchAgentCard(String agentUrl, A2AAgentConfig config) throws Exception {

        // Check cache (5 min TTL)
        CachedAgentInfo cached = agentCache.get(agentUrl);
        if (cached != null && (System.currentTimeMillis() - cached.timestamp()) < 300_000) {
            return cached.agentCard();
        }

        String cardUrl = agentUrl + "/agent.json";

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder().uri(URI.create(cardUrl))
                .timeout(Duration.ofMillis(config.getTimeoutMs() != null ? config.getTimeoutMs() : 30000)).GET();

        String apiKey = config.getApiKey();
        if (!isNullOrEmpty(apiKey)) {
            apiKey = secretResolver.resolveValue(apiKey);
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }

        HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            LOGGER.warnf("Agent Card fetch returned %d from %s", response.statusCode(), cardUrl);
            return null;
        }

        // Response size limit
        if (response.body() != null && response.body().length() > MAX_RESPONSE_SIZE_BYTES) {
            LOGGER.warnf("Agent Card response from %s exceeds %d bytes — rejecting", cardUrl, MAX_RESPONSE_SIZE_BYTES);
            return null;
        }

        Map<String, Object> card = MAPPER.readValue(response.body(), Map.class);

        // Basic schema validation — must have "name" at minimum
        if (!card.containsKey("name")) {
            LOGGER.warnf("Agent Card from %s missing 'name' field — rejecting", cardUrl);
            return null;
        }

        agentCache.put(agentUrl, new CachedAgentInfo(card, System.currentTimeMillis()));
        return card;
    }

    private ToolExecutor createA2AToolExecutor(String agentUrl, A2AAgentConfig config) {
        return (request, memoryId) -> {
            try {
                return executeA2ATask(agentUrl, config, request);
            } catch (Exception e) {
                LOGGER.errorf("A2A tool execution failed for %s: %s", agentUrl, e.getMessage());
                return "Error calling A2A agent: " + e.getMessage();
            }
        };
    }

    @SuppressWarnings("unchecked")
    private String executeA2ATask(String agentUrl, A2AAgentConfig config, ToolExecutionRequest request) throws Exception {

        Map<String, Object> args = MAPPER.readValue(request.arguments(), Map.class);
        String message = (String) args.getOrDefault("message", "");

        // Build JSON-RPC request
        Map<String, Object> jsonRpc = new LinkedHashMap<>();
        jsonRpc.put("jsonrpc", "2.0");
        jsonRpc.put("method", "tasks/send");
        jsonRpc.put("id", UUID.randomUUID().toString());

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", UUID.randomUUID().toString());
        params.put("message", Map.of("role", "user", "parts", List.of(Map.of("type", "text", "text", message))));
        jsonRpc.put("params", params);

        String body = MAPPER.writeValueAsString(jsonRpc);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder().uri(URI.create(agentUrl))
                .timeout(Duration.ofMillis(config.getTimeoutMs() != null ? config.getTimeoutMs() : 30000)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));

        String apiKey = config.getApiKey();
        if (!isNullOrEmpty(apiKey)) {
            apiKey = secretResolver.resolveValue(apiKey);
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }

        HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            return "A2A agent returned HTTP " + response.statusCode();
        }

        // Response size limit
        if (response.body() != null && response.body().length() > MAX_RESPONSE_SIZE_BYTES) {
            return "A2A agent response exceeds size limit (" + MAX_RESPONSE_SIZE_BYTES + " bytes)";
        }

        // Validate JSON-RPC response schema
        Map<String, Object> rpcResponse = MAPPER.readValue(response.body(), Map.class);
        if (!rpcResponse.containsKey("jsonrpc") || !"2.0".equals(rpcResponse.get("jsonrpc"))) {
            return "Invalid A2A response: not a valid JSON-RPC 2.0 response";
        }

        Map<String, Object> result = (Map<String, Object>) rpcResponse.get("result");
        if (result == null) {
            Map<String, Object> error = (Map<String, Object>) rpcResponse.get("error");
            if (error != null) {
                return "A2A error: " + error.getOrDefault("message", "unknown");
            }
            return "No result from A2A agent";
        }

        // Extract artifacts → parts → text
        List<Map<String, Object>> artifacts = (List<Map<String, Object>>) result.get("artifacts");
        if (artifacts != null && !artifacts.isEmpty()) {
            var firstArtifact = artifacts.get(0);
            List<Map<String, Object>> parts = (List<Map<String, Object>>) firstArtifact.get("parts");
            if (parts != null && !parts.isEmpty()) {
                Object text = parts.get(0).get("text");
                if (text != null) {
                    return text.toString();
                }
            }
        }

        // Fallback: try history
        List<Map<String, Object>> history = (List<Map<String, Object>>) result.get("history");
        if (history != null && !history.isEmpty()) {
            var lastMsg = history.get(history.size() - 1);
            List<Map<String, Object>> parts = (List<Map<String, Object>>) lastMsg.get("parts");
            if (parts != null && !parts.isEmpty()) {
                Object text = parts.get(0).get("text");
                if (text != null) {
                    return text.toString();
                }
            }
        }

        return MAPPER.writeValueAsString(result);
    }

    private void warnIfRawKey(String apiKey, String url) {
        if (!apiKey.startsWith("${vault:") && !apiKey.startsWith("${eddivault:")) {
            LOGGER.warnf("A2A agent at %s uses a raw API key instead of a vault " + "reference (e.g., ${eddivault:my-key}). Raw keys risk secret "
                    + "leakage in config exports — migrate to vault references.", url);
        }
    }

    private String sanitizeToolName(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9_]", "_").replaceAll("_+", "_").replaceAll("^_|_$", "");
    }

    // === Circuit Breaker ===

    private boolean isCircuitOpen(String url) {
        CircuitState state = circuitBreakers.get(url);
        if (state == null)
            return false;
        if (state.failures() >= CIRCUIT_BREAKER_THRESHOLD) {
            // Auto-reset after cooldown
            if (System.currentTimeMillis() - state.lastFailure() > CIRCUIT_BREAKER_COOLDOWN_MS) {
                circuitBreakers.remove(url);
                return false;
            }
            return true;
        }
        return false;
    }

    private void recordFailure(String url) {
        circuitBreakers.compute(url, (k, v) -> {
            int failures = (v != null) ? v.failures() + 1 : 1;
            return new CircuitState(failures, System.currentTimeMillis());
        });
    }
}
