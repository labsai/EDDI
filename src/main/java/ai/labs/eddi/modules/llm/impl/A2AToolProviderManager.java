/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.connections.ConnectionException;
import ai.labs.eddi.connections.ConnectionResolver;
import ai.labs.eddi.connections.model.ConnectionReference;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.A2AAgentConfig;
import ai.labs.eddi.modules.llm.tools.UrlValidationUtils;
import ai.labs.eddi.secrets.SecretResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
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

    private final GlobalVariableResolver globalVariableResolver;
    private final SecretResolver secretResolver;
    private volatile HttpClient httpClient;
    private final boolean ssrfProtectionEnabled;

    /**
     * Resolves a {@code ${connection:name}} apiKey per call. Nullable, because two
     * back-compat constructors build this manager without a container.
     */
    private final ConnectionResolver connectionResolver;

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
    public A2AToolProviderManager(GlobalVariableResolver globalVariableResolver, SecretResolver secretResolver,
            @ConfigProperty(name = "eddi.security.ssrf-protection.enabled", defaultValue = "false") boolean ssrfProtectionEnabled,
            ConnectionResolver connectionResolver) {
        this.connectionResolver = connectionResolver;
        this.globalVariableResolver = globalVariableResolver;
        this.secretResolver = secretResolver;
        this.ssrfProtectionEnabled = ssrfProtectionEnabled;
    }

    /**
     * The shared outbound client, created on first use.
     * <p>
     * Not in the constructor. Building a {@code HttpClient} starts a selector
     * thread and opens a loopback socket, so a bean that is merely INJECTED — in a
     * unit test, or in any conversation that never talks to an A2A peer — paid for
     * a connection pool it never used, and failed outright in environments where
     * loopback is unavailable.
     * <p>
     * Double-checked locking on a volatile field: two concurrent first calls must
     * not each build a client, because the loser's would be dropped with its
     * selector thread still running.
     */
    private HttpClient httpClient() {
        HttpClient client = httpClient;
        if (client == null) {
            synchronized (this) {
                client = httpClient;
                if (client == null) {
                    // JDK HttpClient defaults to Redirect.NEVER, so validating the
                    // target URL is sufficient — there is no redirect hop to
                    // re-validate.
                    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
                    httpClient = client;
                }
            }
        }
        return client;
    }

    /**
     * Constructor for tests and for callers with no connection-backed peers.
     * <p>
     * The resolver is nullable rather than defaulted to a no-op: a no-op would make
     * a {@code ${connection:…}} apiKey resolve to nothing and be sent as literal
     * text, which the peer answers with an opaque 401. Null produces a message that
     * names the cause.
     */
    A2AToolProviderManager(GlobalVariableResolver globalVariableResolver, SecretResolver secretResolver, boolean ssrfProtectionEnabled) {
        this(globalVariableResolver, secretResolver, ssrfProtectionEnabled, null);
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
            } catch (ConnectionException | IllegalArgumentException | IllegalStateException e) {
                // A credential or a malformed config is deliberately NOT fed to the
                // breaker. The breaker exists to stop hammering a flaky peer, and
                // neither of these is healed by waiting — while opening it suppresses
                // discovery for EVERY user because one of them has no grant, and tells
                // the operator the peer is unreachable when it is fine.
                LOGGER.warnf("A2A agent at %s could not be given a usable credential: %s", config.getUrl(), e.getMessage());
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

        if (ssrfProtectionEnabled) {
            UrlValidationUtils.validateUrl(cardUrl);
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder().uri(URI.create(cardUrl))
                .timeout(Duration.ofMillis(config.getTimeoutMs() != null ? config.getTimeoutMs() : 30000)).GET();

        applyCredential(requestBuilder, config, agentUrl, true);

        HttpResponse<String> response = httpClient().send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

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
                // The operator gets the detail, in the log above. The MODEL gets a
                // bounded sentence: an exception from an outbound call can quote a URL
                // with a token in its query, or a provider body echoing the request,
                // and whatever it quotes lands in the transcript.
                return "Error calling A2A agent: the request could not be completed. See the server log for details.";
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

        if (ssrfProtectionEnabled) {
            UrlValidationUtils.validateUrl(agentUrl);
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder().uri(URI.create(agentUrl))
                .timeout(Duration.ofMillis(config.getTimeoutMs() != null ? config.getTimeoutMs() : 30000)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));

        applyCredential(requestBuilder, config, agentUrl);

        HttpResponse<String> response = httpClient().send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

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

    /**
     * Puts the configured credential on an outbound A2A request - agent-card fetch
     * and task call alike.
     * <p>
     * One method on purpose. The two paths held identical copies of this block, and
     * they had already drifted: the card fetch understood {@code ${connection:...}}
     * and the task call did not, so an agent configured against a connection
     * discovered its skills correctly and then sent the literal string
     * {@code Bearer ${connection:salesforce}} as its bearer token on every actual
     * call. Two copies of a credential rule is one copy too many.
     * <p>
     * This form is the task call; the overload below is the same rule with the one
     * distinction the two paths genuinely have.
     */
    // Package-private so a test can assert what actually lands on the request.
    void applyCredential(HttpRequest.Builder requestBuilder, A2AAgentConfig config, String agentUrl) {
        applyCredential(requestBuilder, config, agentUrl, false);
    }

    /**
     * The same credential rule, told whether it is serving discovery.
     *
     * @param discovery
     *            whether this is the agent-card fetch rather than a task call. Its
     *            result is CACHED for five minutes and served to every conversation
     *            that follows, so a {@code PER_USER} connection must not establish
     *            it — the first caller's authority would answer for everybody after
     *            them, and a caller who is not bound at all would fail discovery
     *            for all of them. {@code ConnectionResolver#resolveForDiscovery}
     *            draws that line, exactly as the MCP handshake does; empty means
     *            send the request unauthenticated and let the peer decide.
     *            <p>
     *            A task call is the opposite: it belongs to one conversation, so a
     *            {@code PER_USER} connection resolves against the
     *            {@code ResolutionPrincipal} bound to the turn — the conversation's
     *            owner and whether anybody authenticated them. Nothing is passed
     *            from here because nothing here knows better; and the thread's
     *            CALLER is deliberately not consulted, since on a HITL resume that
     *            is the approver rather than the user whose call was approved.
     */
    void applyCredential(HttpRequest.Builder requestBuilder, A2AAgentConfig config, String agentUrl, boolean discovery) {
        String apiKey = config.getApiKey();
        if (isNullOrEmpty(apiKey)) {
            return;
        }
        // A connection resolves per CALL - it may be refreshed between two calls a
        // second apart - so it is checked before the static resolution chain rather
        // than after it, which would first mangle the reference.
        if (ConnectionResolver.containsReference(apiKey)) {
            if (connectionResolver == null) {
                throw new IllegalStateException("A2A agent at " + agentUrl + " uses a ${connection:…} apiKey, but this manager was "
                        + "constructed without a ConnectionResolver.");
            }
            ConnectionReference.requireSole(apiKey, "The apiKey of the A2A agent at " + agentUrl);
            if (discovery) {
                connectionResolver.resolveForDiscovery(apiKey, URI.create(agentUrl))
                        .ifPresent(credential -> requestBuilder.header(credential.headerName(), credential.headerValue()));
                return;
            }
            var credential = connectionResolver.resolve(apiKey, URI.create(agentUrl), null);
            requestBuilder.header(credential.headerName(), credential.headerValue());
            return;
        }
        String resolved = secretResolver.resolveValue(globalVariableResolver.resolveValue(apiKey));
        requestBuilder.header("Authorization", "Bearer " + resolved);
    }

    private void warnIfRawKey(String apiKey, String url) {
        // ${connection:...} belongs in this list: it is the MOST managed of the
        // forms, and omitting it told authors who had done exactly the right thing
        // that they were risking a leak.
        if (!apiKey.startsWith("${vault:") && !apiKey.startsWith("${eddivault:") && !apiKey.startsWith("${vars:")
                && !ConnectionResolver.containsReference(apiKey)) {
            LOGGER.warnf("A2A agent at %s uses a raw API key instead of a vault " + "reference (e.g., ${vault:my-key}). Raw keys risk secret "
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
