/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.CallerIdentityResolver;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.McpServerConfig;
import ai.labs.eddi.modules.llm.tools.UrlValidationUtils;
import ai.labs.eddi.secrets.SecretResolver;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpCallContext;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProviderResult;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import java.net.URI;
import java.security.NoSuchAlgorithmException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * Manages MCP client connections for agents that consume external MCP servers
 * as tool providers.
 * <p>
 * Responsibilities:
 * <ul>
 * <li>Create and cache {@link McpClient} connections by URL</li>
 * <li>Discover tools from remote MCP servers via {@link McpToolProvider}</li>
 * <li>Return tool specifications and executors for the
 * {@link AgentOrchestrator}</li>
 * <li>Resolve vault references in API keys via {@link SecretResolver}</li>
 * <li>Graceful cleanup on shutdown via {@code @PreDestroy}</li>
 * </ul>
 */
@ApplicationScoped
public class McpToolProviderManager {

    private static final Logger LOGGER = Logger.getLogger(McpToolProviderManager.class);

    private final GlobalVariableResolver globalVariableResolver;
    private final SecretResolver secretResolver;
    private final CallerIdentityResolver callerIdentityResolver;
    private final CallerIdentityContext callerIdentityContext;
    private final boolean ssrfProtectionEnabled;
    private final int maxDescriptionChars;
    private final long toolCacheTtlMillis;

    /**
     * Cache of active MCP clients, keyed by server URL. Connections are reused
     * across conversation turns to avoid reconnect overhead.
     */
    private final Map<String, McpClient> clientCache = new ConcurrentHashMap<>();

    /**
     * Cache of discovered tool specs/executors per server URL. Finding F12: without
     * this, every conversation turn issued a live {@code tools/list} RPC to every
     * configured MCP server (only the connection was cached). Mirrors the 5-minute
     * Agent Card cache in {@link A2AToolProviderManager}.
     */
    private final Map<String, CachedTools> toolCache = new ConcurrentHashMap<>();

    /** Cached discovery result for one server, with the time it was captured. */
    record CachedTools(List<ToolSpecification> toolSpecs, Map<String, ToolExecutor> executors, long timestamp) {
    }

    /** Default TTL for the discovered-tools cache (5 minutes), as for A2A cards. */
    static final long DEFAULT_TOOL_CACHE_TTL_MILLIS = 300_000L;

    /** Default cap for a remote tool description before it reaches the model. */
    static final int DEFAULT_MAX_DESCRIPTION_CHARS = 1024;

    /**
     * Directive-shaped content that a remote MCP server must not be able to inject
     * into the model's tool definitions (finding F16). Matched case-insensitively
     * and replaced with {@code [redacted]} — the tool stays usable, the instruction
     * does not survive.
     */
    private static final Pattern DIRECTIVE_PATTERN = Pattern.compile(
            "(?i)(ignore\\s+(all\\s+|any\\s+)?(previous|prior|above|earlier)\\s+instructions?"
                    + "|disregard\\s+(all\\s+|any\\s+)?(previous|prior|above|earlier)\\s+instructions?"
                    + "|you\\s+are\\s+now\\s+"
                    + "|system\\s*(prompt|message)\\s*[:=]"
                    + "|</?(system|assistant|user)>"
                    + "|\\[/?(INST|SYSTEM)\\]"
                    + "|<\\|im_(start|end)\\|>)");

    // ----- Circuit breaker state -----
    /** Maximum failures within the window before the circuit opens. */
    private static final int CIRCUIT_FAILURE_THRESHOLD = 3;
    /** Time window for counting failures (seconds). */
    private static final long CIRCUIT_WINDOW_SECONDS = 60;
    /** Recent failure timestamps per server URL. */
    private final Map<String, List<Instant>> failureTimestamps = new ConcurrentHashMap<>();

    /**
     * Field-injected so a directly constructed instance (tests, non-CDI callers)
     * keeps working without one — {@link #count} is null-safe.
     */
    @Inject
    MeterRegistry meterRegistry;

    /**
     * Count an MCP discovery outcome.
     * <p>
     * Tags are a fixed vocabulary and never carry a URL or a credential: a server
     * URL is unbounded cardinality, and the credential is the thing the cache key
     * goes to lengths to hash. A rising {@code circuit_open} or {@code failure}
     * count is the signal worth alerting on.
     */
    private void count(String outcome) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter("eddi.mcp.discovery", "outcome", outcome).increment();
    }

    @Inject
    public McpToolProviderManager(GlobalVariableResolver globalVariableResolver, SecretResolver secretResolver,
            CallerIdentityResolver callerIdentityResolver, CallerIdentityContext callerIdentityContext,
            @ConfigProperty(name = "eddi.security.ssrf-protection.enabled", defaultValue = "false") boolean ssrfProtectionEnabled,
            @ConfigProperty(name = "eddi.mcp.tool-description.max-chars", defaultValue = "1024") int maxDescriptionChars,
            @ConfigProperty(name = "eddi.mcp.tool-cache.ttl-ms", defaultValue = "300000") long toolCacheTtlMillis) {
        this.globalVariableResolver = globalVariableResolver;
        this.secretResolver = secretResolver;
        this.callerIdentityResolver = callerIdentityResolver;
        this.callerIdentityContext = callerIdentityContext;
        this.ssrfProtectionEnabled = ssrfProtectionEnabled;
        this.maxDescriptionChars = maxDescriptionChars > 0 ? maxDescriptionChars : DEFAULT_MAX_DESCRIPTION_CHARS;
        this.toolCacheTtlMillis = toolCacheTtlMillis >= 0 ? toolCacheTtlMillis : DEFAULT_TOOL_CACHE_TTL_MILLIS;
    }

    /**
     * A caller context for the back-compat constructor below.
     * <p>
     * Safe to share: the binding {@code CallerIdentityContext} reads lives in a
     * static {@link ThreadLocal}, so every instance observes the same per-thread
     * caller. One instance keeps the two constructors consistent.
     */
    private static final CallerIdentityContext UNBOUND_CALLER_CONTEXT = new CallerIdentityContext(null, null);

    /**
     * Test/back-compat constructor for callers that set the operational knobs but
     * have no caller context — the caller falls back to whatever is bound to the
     * calling thread, which is nothing in a unit test.
     */
    McpToolProviderManager(GlobalVariableResolver globalVariableResolver, SecretResolver secretResolver, boolean ssrfProtectionEnabled,
            int maxDescriptionChars, long toolCacheTtlMillis) {
        this(globalVariableResolver, secretResolver, new CallerIdentityResolver(UNBOUND_CALLER_CONTEXT, true), UNBOUND_CALLER_CONTEXT,
                ssrfProtectionEnabled, maxDescriptionChars, toolCacheTtlMillis);
    }

    /**
     * Test/back-compat constructor using the shipped defaults (SSRF validation off,
     * 1024-char description cap, 5-minute tool cache) and a caller context that
     * resolves whatever is bound to the calling thread.
     */
    McpToolProviderManager(GlobalVariableResolver globalVariableResolver, SecretResolver secretResolver) {
        this(globalVariableResolver, secretResolver, new CallerIdentityResolver(UNBOUND_CALLER_CONTEXT, true), UNBOUND_CALLER_CONTEXT, false,
                DEFAULT_MAX_DESCRIPTION_CHARS, DEFAULT_TOOL_CACHE_TTL_MILLIS);
    }

    /**
     * Why a configured MCP server contributed no tools. Distinguishes a static
     * misconfiguration (which no retry will heal, and which an operator must fix)
     * from a transient connectivity problem.
     */
    public enum McpFailureKind {
        /** URL/transport rejected before any request was made — fix the config. */
        INVALID_CONFIGURATION,
        /** The server was contacted but the discovery call failed. */
        CONNECTION_FAILURE,
        /** Skipped because the circuit breaker is open for this server. */
        CIRCUIT_OPEN
    }

    /**
     * One server that did not contribute tools, and why.
     *
     * @param serverName
     *            configured name, or the URL when unnamed
     * @param url
     *            configured URL (may be {@code null}/empty for the empty-URL case)
     * @param kind
     *            failure classification
     * @param message
     *            human-readable reason
     */
    public record McpServerFailure(String serverName, String url, McpFailureKind kind, String message) {
    }

    /**
     * Result of discovering tools from MCP servers.
     *
     * @param toolSpecs
     *            list of tool specifications discovered
     * @param executors
     *            map of tool name → executor for each discovered tool
     * @param failures
     *            servers that contributed nothing, with the reason — an empty tool
     *            list alone cannot tell "rejected as misconfigured" apart from
     *            "server genuinely has no tools"
     */
    public record McpToolsResult(List<ToolSpecification> toolSpecs, Map<String, ToolExecutor> executors,
            List<McpServerFailure> failures) {

        /** Convenience for callers that build a result without failures. */
        public McpToolsResult(List<ToolSpecification> toolSpecs, Map<String, ToolExecutor> executors) {
            this(toolSpecs, executors, List.of());
        }

        /**
         * @return true if at least one server was rejected because its configuration is
         *         invalid (a 4xx-shaped condition, not a 5xx-shaped one)
         */
        public boolean hasConfigurationErrors() {
            return failures.stream().anyMatch(f -> f.kind() == McpFailureKind.INVALID_CONFIGURATION);
        }
    }

    /**
     * Connect to the configured MCP servers and discover their tools.
     * <p>
     * Returns the combined tool specifications and executors from all servers.
     * Failed connections log a warning but don't prevent other servers from being
     * used; every server that contributed nothing is reported in
     * {@link McpToolsResult#failures()} so the caller can tell a rejected
     * configuration apart from a server that genuinely exposes no tools.
     *
     * @param mcpServers
     *            list of MCP server configurations
     * @return combined tools from all reachable servers, plus per-server failures
     */
    public McpToolsResult discoverTools(List<McpServerConfig> mcpServers) {
        if (mcpServers == null || mcpServers.isEmpty()) {
            return new McpToolsResult(List.of(), Map.of());
        }

        List<ToolSpecification> allSpecs = new ArrayList<>();
        Map<String, ToolExecutor> allExecutors = new HashMap<>();
        List<McpServerFailure> failures = new ArrayList<>();

        for (McpServerConfig serverConfig : mcpServers) {
            String url = serverConfig.getUrl();
            String serverName = serverConfig.getName() != null ? serverConfig.getName() : url;

            if (isNullOrEmpty(url)) {
                LOGGER.errorf("MCP server '%s' has no URL configured — it contributes no tools", sanitize(serverName));
                failures.add(new McpServerFailure(serverName, url, McpFailureKind.INVALID_CONFIGURATION,
                        "MCP server URL must not be empty"));
                continue;
            }

            // Circuit breaker: skip servers that failed too often recently
            String circuitKey = cacheKey(serverConfig);
            if (isCircuitOpen(circuitKey)) {
                count("circuit_open");
                LOGGER.warnf("Circuit breaker OPEN for MCP server '%s' — skipping (>=%d failures in last %ds)",
                        sanitize(serverName), CIRCUIT_FAILURE_THRESHOLD, CIRCUIT_WINDOW_SECONDS);
                failures.add(new McpServerFailure(serverName, url, McpFailureKind.CIRCUIT_OPEN,
                        "Circuit breaker open after " + CIRCUIT_FAILURE_THRESHOLD + " or more recent failures"));
                continue;
            }

            // F12: serve the tool list from the TTL cache when it is still fresh —
            // a live tools/list RPC per server per turn was pure overhead. Nothing
            // reaches the cache without having passed validation first, so this is
            // checked before the (potentially DNS-resolving) validation below.
            CachedTools cached = toolCache.get(cacheKey(serverConfig));
            if (cached != null && (System.currentTimeMillis() - cached.timestamp()) < toolCacheTtlMillis) {
                LOGGER.debugf("Serving %d cached tools for MCP server '%s'", cached.toolSpecs().size(), sanitize(serverName));
                count("cache_hit");
                mergeServerTools(allSpecs, allExecutors, cached.toolSpecs(), cached.executors(), serverName);
                continue;
            }

            // A10/I3: a rejected URL or an unimplemented transport is a static
            // CONFIGURATION error, not a connectivity blip — validate before contacting
            // the server so it is reported as such, logged at ERROR, and never counted
            // as a circuit-breaker failure (the breaker shields flaky servers, not typos).
            try {
                validateServerUrl(url);
                validateTransport(serverConfig.getTransport());
                validateCallerBoundKey(serverConfig);
            } catch (IllegalArgumentException e) {
                LOGGER.errorf("MCP server '%s' was NOT contacted because its configuration is invalid: %s — "
                        + "the agent runs without this server's tools until the config is fixed",
                        sanitize(serverName), sanitize(e.getMessage()));
                failures.add(new McpServerFailure(serverName, url, McpFailureKind.INVALID_CONFIGURATION, e.getMessage()));
                continue;
            }

            // The transport passed validation but may be a backward-compat alias
            // (e.g. "sse") that is actually served over StreamableHTTP — say so once
            // per server rather than stripping every tool from a stored config.
            warnOnceAboutDeprecatedTransport(url, serverConfig.getTransport(), serverName);

            try {
                // Discover tools — McpToolProvider returns ToolProviderResult
                ToolProviderResult result = fetchToolsFromServer(serverConfig);

                List<ToolSpecification> serverSpecs = new ArrayList<>();
                Map<String, ToolExecutor> serverExecutors = new HashMap<>();
                if (result != null && result.tools() != null) {
                    for (var toolEntry : result.tools().entrySet()) {
                        // F16: the description is authored by the REMOTE server and lands
                        // verbatim in the model's tool definitions — cap and sanitize it
                        // before it becomes prompt content.
                        ToolSpecification spec = governDescription(toolEntry.getKey(), serverName);
                        ToolExecutor executor = toolEntry.getValue();

                        if (executor == null) {
                            LOGGER.warnf("MCP server '%s' advertises tool '%s' without an executor — dropping it",
                                    sanitize(serverName), sanitize(spec.name()));
                            continue;
                        }

                        // F15: within one server, a duplicate tool name would silently
                        // overwrite the earlier executor while both specs reach the model.
                        if (serverExecutors.containsKey(spec.name())) {
                            LOGGER.warnf("MCP server '%s' advertises tool '%s' more than once — keeping the first, dropping the duplicate",
                                    sanitize(serverName), sanitize(spec.name()));
                            continue;
                        }

                        serverSpecs.add(spec);
                        serverExecutors.put(spec.name(), executor);
                    }
                    LOGGER.infof("Discovered %d tools from MCP server '%s'", serverSpecs.size(), sanitize(serverName));
                }

                toolCache.put(cacheKey(serverConfig), new CachedTools(List.copyOf(serverSpecs), Map.copyOf(serverExecutors),
                        System.currentTimeMillis()));
                mergeServerTools(allSpecs, allExecutors, serverSpecs, serverExecutors, serverName);

                // Success — clear failure history for this server
                recordSuccess(circuitKey);
                count("success");

            } catch (Exception e) {
                LOGGER.warnf(e, "Failed to connect to MCP server '%s': %s", sanitize(serverName), e.getMessage());
                failures.add(new McpServerFailure(serverName, url, McpFailureKind.CONNECTION_FAILURE, e.getMessage()));
                recordFailure(circuitKey);
                count("failure");
            }
        }

        return new McpToolsResult(allSpecs, allExecutors, List.copyOf(failures));
    }

    /**
     * Merge one server's tools into the combined result.
     * <p>
     * F15 (cross-server half): deduping only inside a single server left two MCP
     * servers advertising the same tool name colliding on {@code putAll} — the
     * model saw two identically named specs while every call was dispatched to the
     * server that happened to be merged last. The first server to claim a name
     * keeps it, and the collision is logged instead of being silent.
     */
    private void mergeServerTools(List<ToolSpecification> allSpecs, Map<String, ToolExecutor> allExecutors,
                                  List<ToolSpecification> serverSpecs, Map<String, ToolExecutor> serverExecutors, String serverName) {
        for (ToolSpecification spec : serverSpecs) {
            ToolExecutor executor = serverExecutors.get(spec.name());
            if (executor == null) {
                continue;
            }
            ToolExecutor alreadyRegistered = allExecutors.get(spec.name());
            if (alreadyRegistered != null) {
                if (alreadyRegistered != executor) {
                    // Same name, different server: the model would otherwise see two
                    // identical names and every call would land on whichever server was
                    // merged last.
                    LOGGER.warnf("MCP server '%s' advertises tool '%s', which another MCP server already provides — "
                            + "keeping the first, dropping this one", sanitize(serverName), sanitize(spec.name()));
                }
                continue;
            }
            allSpecs.add(spec);
            allExecutors.put(spec.name(), executor);
        }
    }

    /**
     * Issue the {@code tools/list} discovery call against a single server.
     * Package-private and overridable purely as a seam so tests can exercise
     * {@link #discoverTools} without a live MCP server.
     */
    ToolProviderResult fetchToolsFromServer(McpServerConfig serverConfig) {
        McpClient client = getOrCreateClient(serverConfig);

        // Use McpToolProvider to discover tools from this server
        McpToolProvider toolProvider = McpToolProvider.builder().mcpClients(List.of(client)).build();

        return toolProvider.provideTools(null);
    }

    /**
     * Cache key for a client: the URL plus a digest of the configured credential.
     * <p>
     * Keying on the URL alone let two agents configured against the same server
     * with <em>different</em> credentials share whichever client was constructed
     * first — the second silently borrowed the first's authorization. That is a
     * privilege boundary, not a caching detail.
     * <p>
     * The credential is digested rather than used directly so a literal key never
     * becomes a map key that could reach a heap dump or a log line. The value is
     * taken <em>unresolved</em>, so two configs sharing one vault reference still
     * share a client — the distinction that matters is which credential a config
     * names, not what it resolves to.
     * <p>
     * This does not multiply clients per user: a {@code ${caller:...}} config
     * yields one client whose header supplier reads the caller per request, so
     * every user of that config shares it.
     */
    private static String cacheKey(McpServerConfig config) {
        String apiKey = config.getApiKey();
        if (isNullOrEmpty(apiKey)) {
            return config.getUrl() + "|anonymous";
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(apiKey.getBytes(StandardCharsets.UTF_8));
            return config.getUrl() + "|" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the platform; if it is truly absent, fall back to
            // isolating by identity so distinct credentials still cannot share.
            return config.getUrl() + "|" + System.identityHashCode(apiKey);
        }
    }

    /**
     * Get or create an MCP client for the given server configuration. Clients are
     * cached per URL and credential for connection reuse.
     */
    private McpClient getOrCreateClient(McpServerConfig config) {
        return clientCache.computeIfAbsent(cacheKey(config), key -> {
            String url = config.getUrl();
            LOGGER.infof("Creating MCP client for '%s' (%s transport)", sanitize(config.getName() != null ? config.getName() : url),
                    sanitize(config.getTransport()));

            Duration timeout = Duration.ofMillis(config.getTimeoutMs() != null ? config.getTimeoutMs() : 30000L);

            McpTransport transport = createTransport(config, timeout);

            return new DefaultMcpClient.Builder().transport(transport).clientName("eddi-mcp-client").build();
        });
    }

    /**
     * Supported MCP transport tokens. Only StreamableHTTP is implemented — every
     * other value (e.g. {@code "stdio"}) used to be accepted, logged, and then
     * silently served over StreamableHTTP anyway (finding I3).
     */
    static final Set<String> SUPPORTED_TRANSPORTS = Set.of("http", "https", "streamable-http", "streamablehttp");

    /**
     * Transport tokens that stored configs still carry and that this manager
     * accepted before finding I3 — served, then as now, over StreamableHTTP.
     * {@code "sse"} was the documented alternative in
     * {@code LlmConfiguration.McpServerConfig}, so agents written against that doc
     * are in the wild. Hard-rejecting it strips EVERY tool from such an agent, and
     * in the agent path the rejection is swallowed by the discovery catch — a
     * silent capability loss, not a loud failure. Accepted with a one-time
     * deprecation warning instead.
     */
    static final Set<String> DEPRECATED_TRANSPORT_ALIASES = Set.of("sse");

    /**
     * Server URLs already warned about a deprecated transport. Discovery runs per
     * turn, so without this the warning would repeat on every turn of every
     * conversation.
     */
    private final Set<String> deprecatedTransportWarned = ConcurrentHashMap.newKeySet();

    /**
     * Validate an MCP server URL before it is used for an outbound request.
     * <p>
     * Finding A10: the MCP client performed no URL validation at all, while
     * {@code GET /mcpcallsstore/mcpcalls/discover-tools?url=...} echoes the
     * response body back to the caller — a full SSRF read primitive. Mirrors
     * {@link A2AToolProviderManager}: private/loopback/link-local/cloud-metadata
     * targets are rejected when {@code eddi.security.ssrf-protection.enabled} is
     * on, and a non-http(s) scheme or unparseable URL is always rejected.
     *
     * @throws IllegalArgumentException
     *             if the URL is unusable or targets an internal address
     */
    /**
     * Refuse a credential the deployment cannot resolve.
     * <p>
     * A {@code ${caller:...}} key needs caller-identity forwarding switched on.
     * With it off, every tool call would throw from {@code authorizationHeader} —
     * once per request, forever, for a mistake made once in configuration. This
     * reports it alongside the URL and transport checks, so it surfaces as
     * {@code INVALID_CONFIGURATION} where an operator will actually see it.
     */
    private void validateCallerBoundKey(McpServerConfig config) {
        try {
            // A bare {caller:token} or a typo like ${caller:tokn} is a config mistake.
            // Left to createTransport it surfaces as CONNECTION_FAILURE and trips the
            // circuit breaker, blaming the server for something the config did.
            callerIdentityResolver.rejectUnsupportedReference(config.getApiKey());
        } catch (CallerIdentityResolver.CallerIdentityException e) {
            throw new IllegalArgumentException("apiKey: " + e.getMessage(), e);
        }
        if (CallerIdentityResolver.containsReference(config.getApiKey()) && !callerIdentityResolver.isEnabled()) {
            throw new IllegalArgumentException("apiKey uses ${caller:...} but caller-identity forwarding is disabled "
                    + "(eddi.caller-identity.enabled=false) — set a static key or enable forwarding");
        }
    }

    void validateServerUrl(String url) {
        if (isNullOrEmpty(url)) {
            throw new IllegalArgumentException("MCP server URL must not be empty");
        }
        if (ssrfProtectionEnabled) {
            UrlValidationUtils.validateUrl(url);
            return;
        }
        // Even with SSRF protection off, a non-http(s) scheme is never a valid MCP
        // StreamableHTTP endpoint — reject it rather than handing it to the builder.
        String lower = url.trim().toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new IllegalArgumentException("MCP server URL must use http or https: " + url);
        }
    }

    /**
     * Reject a transport token that this manager does not actually implement
     * (finding I3). {@code null}/blank means "use the default StreamableHTTP", and
     * a {@link #DEPRECATED_TRANSPORT_ALIASES deprecated alias} is accepted so
     * stored configs keep working.
     */
    static void validateTransport(String transport) {
        if (isNullOrEmpty(transport)) {
            return;
        }
        String normalised = transport.trim().toLowerCase(Locale.ROOT);
        if (SUPPORTED_TRANSPORTS.contains(normalised) || DEPRECATED_TRANSPORT_ALIASES.contains(normalised)) {
            return;
        }
        throw new IllegalArgumentException("Unsupported MCP transport '" + transport + "'. Only StreamableHTTP is implemented — use "
                + "\"http\" (supported: " + SUPPORTED_TRANSPORTS + ").");
    }

    /** Whether {@code transport} is honoured only for backward compatibility. */
    static boolean isDeprecatedTransport(String transport) {
        return !isNullOrEmpty(transport) && DEPRECATED_TRANSPORT_ALIASES.contains(transport.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * One WARN per server URL — discovery runs every turn, so warning per call
     * would repeat forever.
     */
    private void warnOnceAboutDeprecatedTransport(String url, String transport, String serverName) {
        if (isDeprecatedTransport(transport) && deprecatedTransportWarned.add(url)) {
            LOGGER.warnf("MCP server '%s' declares transport '%s', which EDDI does not implement — the connection is served over "
                    + "StreamableHTTP, as it always has been. Update the config to \"http\"; the alias will be removed.",
                    sanitize(serverName), sanitize(transport));
        }
    }

    /**
     * Cap and sanitize a remote tool description before it becomes part of the
     * model's tool definitions (finding F16). Whitelisting operates on tool NAMES,
     * so an approved tool whose description later turns into an instruction is
     * otherwise ungoverned.
     */
    ToolSpecification governDescription(ToolSpecification spec, String serverName) {
        String description = spec.description();
        if (description == null || description.isBlank()) {
            return spec;
        }

        String sanitized = DIRECTIVE_PATTERN.matcher(description).replaceAll("[redacted]");
        if (!sanitized.equals(description)) {
            LOGGER.warnf("MCP tool '%s' from server '%s' had directive-shaped content in its description — redacted before prompting",
                    sanitize(spec.name()), sanitize(serverName));
        }

        if (sanitized.length() > maxDescriptionChars) {
            LOGGER.warnf("MCP tool '%s' from server '%s' description is %d chars — truncated to %d",
                    sanitize(spec.name()), sanitize(serverName), sanitized.length(), maxDescriptionChars);
            sanitized = sanitized.substring(0, maxDescriptionChars) + " […truncated]";
        }

        if (sanitized.equals(description)) {
            return spec;
        }
        return ToolSpecification.builder().name(spec.name()).description(sanitized).parameters(spec.parameters()).build();
    }

    /**
     * Create the appropriate MCP transport based on configuration.
     */
    private McpTransport createTransport(McpServerConfig config, Duration timeout) {
        // A10: never open a connection to an unvalidated, caller-supplied URL.
        validateServerUrl(config.getUrl());
        // I3: fail loudly on a transport this manager does not implement instead of
        // logging it and quietly using StreamableHTTP.
        validateTransport(config.getTransport());

        // Resolve the static half once: a global variable or vault reference does not
        // change between requests, and the handshake needs a credential before any
        // conversation exists. A caller reference is left alone — it is resolved per
        // request below.
        String apiKey = config.getApiKey();
        // A bare {caller:token} — the natural Qute namespace form — is a reference the
        // resolver never substitutes. Left to containsReference() it would look like a
        // static key and be sent as literal text; this turns it into a clear error.
        callerIdentityResolver.rejectUnsupportedReference(apiKey);
        boolean callerBound = CallerIdentityResolver.containsReference(apiKey);
        if (!isNullOrEmpty(apiKey) && !callerBound) {
            apiKey = globalVariableResolver.resolveValue(apiKey);
            apiKey = secretResolver.resolveValue(apiKey);
        }

        // StreamableHttpMcpTransport (recommended, replaces deprecated
        // HttpMcpTransport)
        var transportBuilder = StreamableHttpMcpTransport.builder().url(config.getUrl()).timeout(timeout);

        if (!isNullOrEmpty(apiKey)) {
            final String configuredKey = apiKey;
            // Per request, not per client. The Map overload would freeze this value
            // into the cached client, which is why a ${caller:...} reference has to be
            // resolved here: the client is shared across conversations, the caller is
            // not. Applies to every POST the transport makes.
            transportBuilder.customHeaders(callContext -> authorizationHeader(configuredKey, config, callerBound, callContext));
        }

        return transportBuilder.build();
    }

    /**
     * Give a bare token the {@code Bearer} scheme, leaving an already-qualified
     * value alone.
     * <p>
     * {@code McpServerConfig.apiKey} is documented as a key or token, and the
     * static path has always prefixed {@code Bearer }. A caller-bound key must
     * behave the same or {@code apiKey: "${caller:token}"} — the form the
     * documentation implies — would send a raw token with no scheme and the server
     * would reject it. An author who spells the scheme out (as apicall headers do)
     * is not double-prefixed.
     */
    private static String withBearerPrefix(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        // Trim before use, not just for the check: stray whitespace — a newline most
        // of all — has no business reaching an Authorization header, and returning
        // the untrimmed value would emit " Bearer …" for a padded config.
        String trimmed = value.trim();
        // A scheme is one token followed by a space; anything else is a bare secret.
        return trimmed.matches("(?i)(bearer|basic|token|apikey)\\s.+") ? trimmed : "Bearer " + trimmed;
    }

    /**
     * Build the {@code Authorization} header for a single MCP request.
     * <p>
     * A static key behaves exactly as before. A {@code ${caller:...}} reference
     * resolves against the caller bound to this thread, so the tool call reaches
     * the MCP server as the person chatting rather than as a standing service
     * principal — the same guarantee {@code ApiCallExecutor} gives apicall headers,
     * including the same-origin restriction and failing closed rather than sending
     * a placeholder.
     * <p>
     * Only a tool call may carry the caller. Discovery — the {@code initialize}
     * handshake and {@code tools/list} — must not, even though it runs on a thread
     * that <em>is</em> bound to a caller: the client is cached, so a session
     * established with one user's token would be reused by everyone after them, and
     * a tool list reflecting one user's permissions would be offered to the next.
     * <p>
     * langchain4j draws that line for us. {@code DefaultMcpClient.listTools()}
     * delegates with a null invocation context, while {@code McpToolExecutor}
     * always builds one — so {@link McpCallContext#invocationContext()} is the
     * discriminator. The context itself is null on some transport-internal
     * requests, which count as not-a-tool-call.
     * <p>
     * A caller-bound config therefore has nothing to offer discovery and sends it
     * unauthenticated, letting the server decide.
     */
    private Map<String, String> authorizationHeader(String configuredKey, McpServerConfig config, boolean callerBound,
                                                    McpCallContext callContext) {
        if (!callerBound) {
            return Map.of("Authorization", withBearerPrefix(configuredKey));
        }
        if (callContext == null || callContext.invocationContext() == null) {
            LOGGER.debugf("MCP discovery request to '%s' carries no caller by design — sending it unauthenticated",
                    sanitize(config.getUrl()));
            return Map.of();
        }
        if (callerIdentityContext.current() == null) {
            // A scheduled turn, or a retry that landed on an HTTP callback thread where
            // the binding does not exist. Sending the placeholder text would be
            // nonsense and sending nothing is honest: the server refuses if it requires
            // authentication, which is a visible failure rather than the wrong
            // authority.
            LOGGER.debugf("No caller bound for MCP tool call to '%s' — sending it unauthenticated", sanitize(config.getUrl()));
            return Map.of();
        }
        String resolved = callerIdentityResolver.resolveValue(configuredKey, URI.create(config.getUrl()));
        return Map.of("Authorization", withBearerPrefix(resolved));
    }

    /**
     * Close every cached client for a server URL and remove them from the cache.
     * <p>
     * A URL can now hold more than one client — one per configured credential — so
     * this closes all of them. Callers identify a server, not a credential.
     */
    void closeClient(String url) {
        if (url == null) {
            return;
        }
        String prefix = url + "|";
        // The cached executors hold a reference to the client, so they are keyed the
        // same way and must be dropped with it — a bare toolCache.remove(url) would
        // leave executors bound to a closed client behind.
        toolCache.keySet().removeIf(key -> key.startsWith(prefix));
        for (var key : List.copyOf(clientCache.keySet())) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            McpClient client = clientCache.remove(key);
            if (client == null) {
                continue;
            }
            try {
                client.close();
                LOGGER.infof("Closed MCP client for '%s'", sanitize(url));
            } catch (Exception e) {
                LOGGER.warnf(e, "Error closing MCP client for '%s'", sanitize(url));
            }
        }
    }

    /**
     * Close all cached MCP client connections. Called on application shutdown.
     */
    @PreDestroy
    void shutdown() {
        LOGGER.infof("Shutting down %d MCP client connection(s)", clientCache.size());
        for (var entry : clientCache.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                LOGGER.warnf(e, "Error closing MCP client for '%s'", sanitize(entry.getKey()));
            }
        }
        clientCache.clear();
        toolCache.clear();
    }

    /**
     * Get the number of active cached connections (for monitoring/testing).
     */
    int getActiveConnectionCount() {
        return clientCache.size();
    }

    /**
     * Number of servers whose tool list is currently cached (monitoring/testing).
     */
    int getCachedToolServerCount() {
        return toolCache.size();
    }

    // ========================== Circuit Breaker ==========================

    /**
     * Whether the circuit is open for a configured server. It opens when the server
     * has failed {@value #CIRCUIT_FAILURE_THRESHOLD} or more times within the last
     * {@value #CIRCUIT_WINDOW_SECONDS} seconds.
     * <p>
     * This is the contract callers want: the key is an implementation detail, and a
     * credential-aware one, so asking by URL gives the wrong answer for a server
     * configured twice with different credentials.
     *
     * @param config
     *            the server whose circuit is in question
     */
    boolean isCircuitOpen(McpServerConfig config) {
        return isCircuitOpen(cacheKey(config));
    }

    /**
     * @param circuitKey
     *            the credential-aware cache key, not the bare URL: two configs may
     *            point at one server with different credentials, and one of them
     *            failing to authenticate must not suppress discovery for the other
     */
    boolean isCircuitOpen(String circuitKey) {
        List<Instant> failures = failureTimestamps.get(circuitKey);
        if (failures == null) {
            return false;
        }
        Instant cutoff = Instant.now().minusSeconds(CIRCUIT_WINDOW_SECONDS);
        long recentFailures = failures.stream().filter(t -> t.isAfter(cutoff)).count();
        return recentFailures >= CIRCUIT_FAILURE_THRESHOLD;
    }

    /**
     * Record a connection failure for circuit breaker tracking. Evicts timestamps
     * older than the window to prevent unbounded growth.
     */
    private void recordFailure(String url) {
        List<Instant> failures = failureTimestamps.computeIfAbsent(url, k -> new CopyOnWriteArrayList<>());
        failures.add(Instant.now());
        // Evict old entries outside the window
        Instant cutoff = Instant.now().minusSeconds(CIRCUIT_WINDOW_SECONDS);
        failures.removeIf(t -> t.isBefore(cutoff));
    }

    /**
     * Record a successful connection — clears the failure history so the circuit
     * breaker resets.
     */
    private void recordSuccess(String url) {
        failureTimestamps.remove(url);
    }
}
