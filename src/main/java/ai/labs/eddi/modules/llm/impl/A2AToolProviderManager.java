/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.connections.ConnectionException;
import ai.labs.eddi.connections.ConnectionResolver;
import ai.labs.eddi.connections.model.ConnectionReference;
import ai.labs.eddi.engine.httpclient.BoundedBodyReader;
import ai.labs.eddi.modules.llm.governance.RemoteTextGovernor;
import ai.labs.eddi.modules.llm.tools.spi.ToolRequestResolver;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.A2AAgentConfig;
import ai.labs.eddi.modules.llm.tools.UrlValidationUtils;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.utils.LogSanitizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * Discovers remote A2A agents and wraps their skills as
 * {@link ToolSpecification}s, mirroring the {@link McpToolProviderManager}
 * pattern.
 * <p>
 * <b>Protocol versions.</b> The dialect a peer is called in is read off its
 * Agent Card: a card listing a JSON-RPC interface at protocol version 1.x is
 * called with {@code SendMessage} (A2A 1.0, sending {@code A2A-Version: 1.0});
 * a card with a 0.x {@code protocolVersion} with {@code message/send}; a card
 * with neither — an EDDI up to 6.5, or any pre-0.2 peer — with the old
 * {@code tasks/send}. Results are read tolerantly in all three shapes, and a
 * task that did not complete is reported to the model as such rather than as an
 * answer.
 *
 * @author ginccc
 */
@ApplicationScoped
public class A2AToolProviderManager {

    private static final Logger LOGGER = Logger.getLogger(A2AToolProviderManager.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GlobalVariableResolver globalVariableResolver;
    private final SecretResolver secretResolver;

    /**
     * Built on first use, not in the constructor.
     * <p>
     * This bean is {@code @ApplicationScoped}, so an eager client meant every EDDI
     * boot created an HTTP client and its selector thread whether or not a single
     * A2A peer was configured — and made the class impossible to construct at all
     * where a selector cannot be opened, which is every unit test in a sandboxed
     * environment. Deferring it costs one volatile read per call and buys both.
     */
    private volatile HttpClient httpClient;
    private final boolean ssrfProtectionEnabled;
    private final int maxDescriptionChars;

    /**
     * Interrupts a response-body read that stalled after the headers arrived — see
     * {@link BoundedBodyReader}. The JDK request timeout bounds only the wait for
     * the response, not the streaming of an {@code ofInputStream} body, so without
     * this a peer that sends 200 and then trickles (or never completes) the body
     * holds the worker thread. One daemon thread for this
     * {@code @ApplicationScoped} bean.
     */
    private final ScheduledExecutorService bodyReadWatchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "a2a-body-watchdog");
        thread.setDaemon(true);
        return thread;
    });

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

    /** The A2A 0.3+/1.0 well-known Agent Card path. */
    static final String WELL_KNOWN_AGENT_CARD = "/.well-known/agent-card.json";

    /**
     * Default cap for a remote-authored description before it reaches the model.
     * Same value as the MCP manager's: the two read the same kind of text off the
     * same kind of channel, and a peer that is too verbose for one is too verbose
     * for the other.
     */
    static final int DEFAULT_MAX_DESCRIPTION_CHARS = 1024;

    private final Map<String, CircuitState> circuitBreakers = new ConcurrentHashMap<>();

    /**
     * @param requestResolvers
     *            dispatch name → what the call would send, so a gated A2A call can
     *            show its approver a target and be pinned to a fingerprint. Empty,
     *            not absent, for callers that construct a result directly.
     */
    record A2AToolsResult(List<ToolSpecification> toolSpecs, Map<String, ToolExecutor> executors,
            Map<String, ToolRequestResolver> requestResolvers) {

        /** Two-component form, for tests and for callers with nothing to pin. */
        A2AToolsResult(List<ToolSpecification> toolSpecs, Map<String, ToolExecutor> executors) {
            this(toolSpecs, executors, Map.of());
        }
    }

    @Inject
    public A2AToolProviderManager(GlobalVariableResolver globalVariableResolver, SecretResolver secretResolver,
            @ConfigProperty(name = "eddi.security.ssrf-protection.enabled", defaultValue = "false") boolean ssrfProtectionEnabled,
            @ConfigProperty(name = "eddi.a2a.tool-description.max-chars", defaultValue = "1024") int maxDescriptionChars,
            ConnectionResolver connectionResolver) {
        this.globalVariableResolver = globalVariableResolver;
        this.secretResolver = secretResolver;
        this.ssrfProtectionEnabled = ssrfProtectionEnabled;
        this.maxDescriptionChars = maxDescriptionChars > 0 ? maxDescriptionChars : DEFAULT_MAX_DESCRIPTION_CHARS;
        this.connectionResolver = connectionResolver;
    }

    /**
     * The shared outbound client, created on first use.
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
     * Constructor for callers that configure neither the description cap nor a
     * connection-backed peer. Mirrors the MCP manager's, so the two are configured
     * and constructed the same way.
     */
    A2AToolProviderManager(GlobalVariableResolver globalVariableResolver, SecretResolver secretResolver, boolean ssrfProtectionEnabled) {
        this(globalVariableResolver, secretResolver, ssrfProtectionEnabled, DEFAULT_MAX_DESCRIPTION_CHARS, null);
    }

    /**
     * Constructor for callers with connection-backed peers but no configured
     * description cap.
     * <p>
     * The resolver is nullable rather than defaulted to a no-op: a no-op would make
     * a {@code ${connection:…}} apiKey resolve to nothing and be sent as literal
     * text, which the peer answers with an opaque 401. Null produces a message that
     * names the cause.
     */
    A2AToolProviderManager(GlobalVariableResolver globalVariableResolver, SecretResolver secretResolver, boolean ssrfProtectionEnabled,
            ConnectionResolver connectionResolver) {
        this(globalVariableResolver, secretResolver, ssrfProtectionEnabled, DEFAULT_MAX_DESCRIPTION_CHARS, connectionResolver);
    }

    /**
     * Discover tools from configured A2A agents.
     */
    public A2AToolsResult discoverTools(List<A2AAgentConfig> a2aAgents) {
        List<ToolSpecification> toolSpecs = new ArrayList<>();
        Map<String, ToolExecutor> executors = new HashMap<>();
        Map<String, ToolRequestResolver> requestResolvers = new HashMap<>();

        if (a2aAgents == null || a2aAgents.isEmpty()) {
            return new A2AToolsResult(toolSpecs, executors, requestResolvers);
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

                discoverAgentTools(config, toolSpecs, executors, requestResolvers);
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

        return new A2AToolsResult(toolSpecs, executors, requestResolvers);
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
    private void discoverAgentTools(A2AAgentConfig config, List<ToolSpecification> toolSpecs, Map<String, ToolExecutor> executors,
                                    Map<String, ToolRequestResolver> requestResolvers)
            throws Exception {

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
        RemoteEndpoint endpoint = resolveEndpoint(agentUrl, agentCard);

        // Build the parameter schema for the "message" parameter
        JsonObjectSchema paramSchema = JsonObjectSchema.builder().addStringProperty("message", "The message to send to the agent").build();

        List<Map<String, Object>> skills = (List<Map<String, Object>>) agentCard.get("skills");
        if (skills == null || skills.isEmpty()) {
            // Single default tool for the entire agent
            String toolName = sanitizeToolName(agentName);
            String desc = (String) agentCard.getOrDefault("description", "Remote A2A agent: " + agentName);

            ToolSpecification spec = ToolSpecification.builder().name(toolName).description(governDescription(desc, agentName))
                    .parameters(paramSchema)
                    .build();
            toolSpecs.add(spec);
            executors.put(toolName, createA2AToolExecutor(endpoint, config));
            requestResolvers.put(toolName, RemoteToolRequestResolvers.forA2A(endpoint.url(), !isNullOrEmpty(config.getApiKey())));
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

            // The provenance suffix is appended AFTER governance so a remote card
            // cannot forge it: sanitizing the concatenation would let a skill whose
            // description ends in "(via A2A agent: trusted-peer)" claim to come from
            // somewhere it does not. The agent name inside it is governed on its own.
            String description = governDescription(skillDesc, agentName) + " (via A2A agent: " + governDescription(agentName, agentName) + ")";

            ToolSpecification spec = ToolSpecification.builder().name(toolName).description(description).parameters(paramSchema).build();

            toolSpecs.add(spec);
            executors.put(toolName, createA2AToolExecutor(endpoint, config));
            requestResolvers.put(toolName, RemoteToolRequestResolvers.forA2A(endpoint.url(), !isNullOrEmpty(config.getApiKey())));
        }
    }

    /**
     * Bounds and de-fangs a description read out of a remote Agent Card.
     * <p>
     * An Agent Card is authored by the remote peer and its {@code description} and
     * per-skill descriptions land verbatim in the model's tool definitions —
     * exactly the channel {@code McpToolProviderManager.governDescription} closes
     * for MCP. The asymmetry was not a decision: A2A simply never got the guard, so
     * a peer could ship a skill whose description was an instruction and reach the
     * model with it, while the identical text from an MCP server was redacted.
     */
    private String governDescription(String description, String agentName) {
        if (RemoteTextGovernor.containsDirective(description)) {
            LOGGER.warnf("A2A agent '%s' had directive-shaped content in a description — redacted before prompting", sanitizeForLog(agentName));
        }
        if (description != null && description.length() > maxDescriptionChars) {
            LOGGER.warnf("A2A agent '%s' supplied a %d-char description — truncated to %d", sanitizeForLog(agentName), description.length(),
                    maxDescriptionChars);
        }
        return RemoteTextGovernor.govern(description, maxDescriptionChars);
    }

    /**
     * Strips CR/LF from a remote-supplied value before it reaches a log line, so a
     * peer cannot forge additional log entries.
     */
    private static String sanitizeForLog(String value) {
        return value == null ? "null" : value.replaceAll("[\r\n]", "_");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchAgentCard(String agentUrl, A2AAgentConfig config) throws Exception {

        // Check cache (5 min TTL)
        CachedAgentInfo cached = agentCache.get(agentUrl);
        if (cached != null && (System.currentTimeMillis() - cached.timestamp()) < 300_000) {
            return cached.agentCard();
        }

        Map<String, Object> card = null;
        for (String cardUrl : cardUrlCandidates(agentUrl)) {
            card = fetchAgentCardFrom(cardUrl, agentUrl, config);
            if (card != null) {
                break;
            }
        }
        if (card == null) {
            return null;
        }

        agentCache.put(agentUrl, new CachedAgentInfo(card, System.currentTimeMillis()));
        return card;
    }

    /**
     * Where an Agent Card may live, in the order they are tried: the URL itself
     * when it names a card document; otherwise {@code {url}/agent.json} (EDDI's
     * per-agent card), then the A2A 0.3+/1.0 well-known path and the older one
     * below the URL — which is where an agent that is not EDDI publishes it.
     */
    static List<String> cardUrlCandidates(String agentUrl) {
        if (agentUrl.endsWith(".json")) {
            return List.of(agentUrl);
        }
        return List.of(agentUrl + "/agent.json", agentUrl + WELL_KNOWN_AGENT_CARD, agentUrl + "/.well-known/agent.json");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchAgentCardFrom(String cardUrl, String agentUrl, A2AAgentConfig config) throws Exception {
        if (ssrfProtectionEnabled) {
            UrlValidationUtils.validateUrl(cardUrl);
        } else {
            UrlValidationUtils.rejectCloudMetadataTarget(cardUrl);
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder().uri(URI.create(cardUrl))
                .timeout(Duration.ofMillis(config.getTimeoutMs() != null ? config.getTimeoutMs() : 30000)).GET();

        applyCredential(requestBuilder, config, agentUrl, true);

        HttpRequest cardRequest = requestBuilder.build();
        HttpResponse<InputStream> response = httpClient().send(cardRequest, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            LOGGER.warnf("Agent Card fetch returned %d from %s", response.statusCode(), cardUrl);
            return null;
        }

        // Bounded read: the body is capped as it arrives rather than buffered whole
        // and measured afterwards. A body over the cap is rejected outright.
        BoundedBodyReader.Bounded bounded = BoundedBodyReader.read(response.body(), MAX_RESPONSE_SIZE_BYTES,
                cardRequest.timeout().orElse(null), bodyReadWatchdog);
        if (bounded.truncated()) {
            LOGGER.warnf("Agent Card response from %s exceeds %d bytes — rejecting", cardUrl, MAX_RESPONSE_SIZE_BYTES);
            return null;
        }

        Map<String, Object> card = MAPPER.readValue(new String(bounded.bytes(), StandardCharsets.UTF_8), Map.class);

        // Basic schema validation — must have "name" at minimum
        if (!card.containsKey("name")) {
            LOGGER.warnf("Agent Card from %s missing 'name' field — rejecting", cardUrl);
            return null;
        }
        return card;
    }

    /** Which wire dialect a remote agent is called in. */
    enum RemoteDialect {
        /** A2A 1.0: {@code SendMessage}, {@code A2A-Version: 1.0}. */
        V1_0,
        /** A2A 0.2/0.3: {@code message/send}. */
        V0_3,
        /** Pre-0.2: {@code tasks/send} — EDDI up to 6.5. */
        LEGACY
    }

    /** Where, and in which dialect, a remote agent's JSON-RPC calls go. */
    record RemoteEndpoint(String url, RemoteDialect dialect) {
    }

    /**
     * Reads the dialect off the Agent Card, and the endpoint too when the
     * configured URL named the card document itself.
     * <p>
     * Otherwise the configured URL stays the endpoint, whatever the card says: it
     * is what an operator approved and what a HITL fingerprint pins, and a card —
     * authored by the peer — must not be able to redirect the call somewhere else.
     */
    @SuppressWarnings("unchecked")
    static RemoteEndpoint resolveEndpoint(String agentUrl, Map<String, Object> card) {
        String v1Url = null;
        String v03Url = null;
        if (card.get("supportedInterfaces") instanceof List<?> interfaces) {
            for (Object entry : interfaces) {
                if (!(entry instanceof Map<?, ?> iface)) {
                    continue;
                }
                Object binding = iface.get("protocolBinding") != null ? iface.get("protocolBinding") : iface.get("transport");
                if (binding != null && !"JSONRPC".equalsIgnoreCase(binding.toString())) {
                    continue;
                }
                String version = iface.get("protocolVersion") == null ? "" : iface.get("protocolVersion").toString();
                String url = iface.get("url") == null ? null : iface.get("url").toString();
                if (version.startsWith("0.")) {
                    v03Url = v03Url == null ? url : v03Url;
                } else if (v1Url == null) {
                    v1Url = url;
                }
            }
        }
        RemoteDialect dialect;
        String cardUrl;
        if (v1Url != null) {
            dialect = RemoteDialect.V1_0;
            cardUrl = v1Url;
        } else if (v03Url != null || card.get("protocolVersion") != null) {
            dialect = RemoteDialect.V0_3;
            cardUrl = v03Url != null ? v03Url : (card.get("url") instanceof String url ? url : null);
        } else {
            dialect = RemoteDialect.LEGACY;
            cardUrl = card.get("url") instanceof String url ? url : null;
        }
        String endpointUrl = agentUrl;
        if (agentUrl.endsWith(".json") && !isNullOrEmpty(cardUrl)) {
            // The configured URL names a card document, so the card is the only thing
            // that can say where to call. It may say a path on the same origin, never
            // another host: the operator's credential is sent to the endpoint, and a
            // card the peer authors must not be able to route it elsewhere.
            if (!sameOrigin(agentUrl, cardUrl)) {
                throw new IllegalArgumentException("The Agent Card at " + agentUrl + " names an endpoint on a different origin; refusing to call it");
            }
            endpointUrl = cardUrl;
        }
        return new RemoteEndpoint(endpointUrl, dialect);
    }

    /** Same scheme, host and port (default ports resolved). */
    static boolean sameOrigin(String a, String b) {
        try {
            URI left = URI.create(a);
            URI right = URI.create(b);
            return left.getScheme() != null && left.getHost() != null && left.getScheme().equalsIgnoreCase(right.getScheme())
                    && left.getHost().equalsIgnoreCase(right.getHost()) && effectivePort(left) == effectivePort(right);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private ToolExecutor createA2AToolExecutor(RemoteEndpoint endpoint, A2AAgentConfig config) {
        return (request, memoryId) -> {
            try {
                return executeA2ATask(endpoint, config, request);
            } catch (Exception e) {
                LOGGER.errorf("A2A tool execution failed for %s: %s", endpoint.url(), e.getMessage());
                // The operator gets the detail, in the log above. The MODEL gets a
                // bounded sentence: an exception from an outbound call can quote a URL
                // with a token in its query, or a provider body echoing the request,
                // and whatever it quotes lands in the transcript.
                return "Error calling A2A agent: the request could not be completed. See the server log for details.";
            }
        };
    }

    /** The JSON-RPC request for one message, in the peer's dialect. */
    static Map<String, Object> buildSendRequest(RemoteDialect dialect, String message) {
        Map<String, Object> jsonRpc = new LinkedHashMap<>();
        jsonRpc.put("jsonrpc", "2.0");
        jsonRpc.put("id", UUID.randomUUID().toString());

        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> msg = new LinkedHashMap<>();
        switch (dialect) {
            case V1_0 -> {
                jsonRpc.put("method", "SendMessage");
                msg.put("messageId", UUID.randomUUID().toString());
                msg.put("role", "ROLE_USER");
                msg.put("parts", List.of(Map.of("text", message)));
            }
            case V0_3 -> {
                jsonRpc.put("method", "message/send");
                msg.put("kind", "message");
                msg.put("messageId", UUID.randomUUID().toString());
                msg.put("role", "user");
                msg.put("parts", List.of(Map.of("kind", "text", "text", message)));
                params.put("configuration", Map.of("blocking", true));
            }
            case LEGACY -> {
                jsonRpc.put("method", "tasks/send");
                params.put("id", UUID.randomUUID().toString());
                msg.put("role", "user");
                msg.put("parts", List.of(Map.of("type", "text", "text", message)));
            }
        }
        params.put("message", msg);
        jsonRpc.put("params", params);
        return jsonRpc;
    }

    @SuppressWarnings("unchecked")
    private String executeA2ATask(RemoteEndpoint endpoint, A2AAgentConfig config, ToolExecutionRequest request) throws Exception {

        Map<String, Object> args = MAPPER.readValue(request.arguments(), Map.class);
        String message = (String) args.getOrDefault("message", "");
        String agentUrl = endpoint.url();

        String body = MAPPER.writeValueAsString(buildSendRequest(endpoint.dialect(), message));

        if (ssrfProtectionEnabled) {
            UrlValidationUtils.validateUrl(agentUrl);
        } else {
            UrlValidationUtils.rejectCloudMetadataTarget(agentUrl);
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder().uri(URI.create(agentUrl))
                .timeout(Duration.ofMillis(config.getTimeoutMs() != null ? config.getTimeoutMs() : 30000)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (endpoint.dialect() == RemoteDialect.V1_0) {
            requestBuilder.header("A2A-Version", "1.0");
        }

        applyCredential(requestBuilder, config, agentUrl);

        HttpRequest taskRequest = requestBuilder.build();
        HttpResponse<InputStream> response = httpClient().send(taskRequest, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            return "A2A agent returned HTTP " + response.statusCode();
        }

        // Bounded read: the body is capped as it arrives rather than buffered whole
        // and measured afterwards. A body over the cap is rejected outright.
        BoundedBodyReader.Bounded bounded = BoundedBodyReader.read(response.body(), MAX_RESPONSE_SIZE_BYTES,
                taskRequest.timeout().orElse(null), bodyReadWatchdog);
        if (bounded.truncated()) {
            return "A2A agent response exceeds size limit (" + MAX_RESPONSE_SIZE_BYTES + " bytes)";
        }

        // Validate JSON-RPC response schema
        Map<String, Object> rpcResponse = MAPPER.readValue(new String(bounded.bytes(), StandardCharsets.UTF_8), Map.class);
        if (!rpcResponse.containsKey("jsonrpc") || !"2.0".equals(rpcResponse.get("jsonrpc"))) {
            return "Invalid A2A response: not a valid JSON-RPC 2.0 response";
        }

        if (!(rpcResponse.get("result") instanceof Map<?, ?> rawResult)) {
            Map<String, Object> error = (Map<String, Object>) rpcResponse.get("error");
            if (error != null) {
                return "A2A error: " + error.getOrDefault("message", "unknown");
            }
            return "No result from A2A agent";
        }
        return readResult((Map<String, Object>) rawResult);
    }

    /**
     * Turns a send result into the text the model sees, in any of the three shapes:
     * A2A 1.0 wraps it ({@code {"task": …}} or {@code {"message": …}}), 0.3 marks
     * it with {@code kind}, pre-0.2 does neither.
     * <p>
     * A task that did not complete is reported as such: a failed or rejected task's
     * status message is an explanation, not an answer, and handing it to the model
     * as one is how a failure turned into a confident wrong reply.
     */
    @SuppressWarnings("unchecked")
    static String readResult(Map<String, Object> result) throws JsonProcessingException {
        Map<String, Object> payload = result;
        if (result.get("task") instanceof Map<?, ?> task) {
            payload = (Map<String, Object>) task;
        } else if (result.get("message") instanceof Map<?, ?> msg && !result.containsKey("status")) {
            payload = (Map<String, Object>) msg;
        }

        // A direct message reply (no task at all).
        if (payload.get("parts") instanceof List<?> parts && !payload.containsKey("status")) {
            String text = partsText(parts);
            if (text != null) {
                return text;
            }
        }

        String state = null;
        String statusText = null;
        Object status = payload.get("status");
        if (status instanceof Map<?, ?> statusMap) {
            state = statusMap.get("state") == null ? null : statusMap.get("state").toString();
            if (statusMap.get("message") instanceof Map<?, ?> statusMessage) {
                statusText = partsText(statusMessage.get("parts"));
            }
        } else if (status instanceof String statusString) {
            state = statusString;
        }
        String normalized = normalizeState(state);

        switch (normalized) {
            case "failed", "rejected", "canceled" -> {
                return "A2A agent task " + normalized + (statusText == null ? "" : ": " + statusText);
            }
            case "input_required", "auth_required" -> {
                return "A2A agent task needs " + (normalized.equals("auth_required") ? "authentication" : "more input")
                        + (statusText == null ? "" : ": " + statusText);
            }
            case "submitted", "working" -> {
                return "A2A agent task is still " + normalized + " (task id " + payload.get("id") + "); no result yet";
            }
            default -> {
                // completed, or a shape without a state
            }
        }

        // Extract artifacts → parts → text
        if (payload.get("artifacts") instanceof List<?> artifacts) {
            List<String> texts = new ArrayList<>();
            for (Object artifact : artifacts) {
                if (artifact instanceof Map<?, ?> artifactMap) {
                    String text = partsText(artifactMap.get("parts"));
                    if (text != null) {
                        texts.add(text);
                    }
                }
            }
            if (!texts.isEmpty()) {
                return String.join("\n", texts);
            }
        }

        // Fallback: the last agent message of the history
        if (payload.get("history") instanceof List<?> history && !history.isEmpty()) {
            for (int i = history.size() - 1; i >= 0; i--) {
                if (history.get(i) instanceof Map<?, ?> msg) {
                    Object role = msg.get("role");
                    boolean fromAgent = role == null || role.toString().toLowerCase().contains("agent");
                    String text = fromAgent ? partsText(msg.get("parts")) : null;
                    if (text != null) {
                        return text;
                    }
                }
            }
        }

        if (statusText != null) {
            return statusText;
        }
        return MAPPER.writeValueAsString(result);
    }

    /**
     * {@code TASK_STATE_INPUT_REQUIRED}, {@code input-required} →
     * {@code input_required}.
     */
    static String normalizeState(String state) {
        if (state == null) {
            return "";
        }
        String normalized = state.trim().toLowerCase();
        if (normalized.startsWith("task_state_")) {
            normalized = normalized.substring("task_state_".length());
        }
        normalized = normalized.replace('-', '_');
        return "cancelled".equals(normalized) ? "canceled" : normalized;
    }

    /**
     * The text of a part list, joined; null when none of its parts carries text.
     */
    private static String partsText(Object partsObj) {
        if (!(partsObj instanceof List<?> parts)) {
            return null;
        }
        List<String> texts = new ArrayList<>();
        for (Object part : parts) {
            if (part instanceof Map<?, ?> partMap && partMap.get("text") != null) {
                texts.add(partMap.get("text").toString());
            }
        }
        return texts.isEmpty() ? null : String.join("\n", texts);
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
                var credential = connectionResolver.resolveForDiscovery(apiKey, URI.create(agentUrl));
                if (credential.isEmpty()) {
                    // Same rule and same warning as the MCP handshake: the agent card is
                    // fetched once and reused, so a per-caller credential must not pin one
                    // caller's authority onto everybody after them — but a peer that
                    // requires a token then answers 401, and without this line nothing
                    // names the cause.
                    String binding = connectionResolver.bindingOf(apiKey).map(Enum::name).orElse("PER_USER or CALLER_SUPPLIED");
                    LOGGER.warnf("A2A agent at %s is bound to a %s connection, so agent-card discovery is sent unauthenticated. If the peer "
                            + "requires a token to serve its agent card, bind it to a SERVICE connection instead.", LogSanitizer.sanitize(agentUrl),
                            binding);
                    return;
                }
                requestBuilder.header(credential.get().headerName(), credential.get().headerValue());
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
