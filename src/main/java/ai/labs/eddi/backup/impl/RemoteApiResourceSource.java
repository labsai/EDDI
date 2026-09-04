/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IResourceSource;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.snippets.model.PromptSnippet;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.utils.RestUtilities;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Reads agent resource data from a remote EDDI instance's REST API. Produces
 * the same {@link IResourceSource} data records as {@link ZipResourceSource},
 * enabling the same {@link StructuralMatcher} and {@link UpgradeExecutor}
 * pipeline to work for live instance-to-instance sync.
 * <p>
 * This class is NOT a CDI bean — it's constructed per-sync-request with the
 * remote instance's base URL, agent ID, and authentication credentials.
 * <p>
 * <b>Security:</b> The bearer token is passed in each HTTP request's
 * {@code Authorization} header and is never persisted. The token comes from the
 * calling endpoint's {@code X-Source-Authorization} header.
 *
 * <h3>Remote API endpoints used</h3>
 *
 * <pre>
 * GET  {baseUrl}/agentstore/agents/descriptors         → list agents
 * GET  {baseUrl}/agentstore/agents/{id}?version=N      → read agent config
 * GET  {baseUrl}/workflowstore/workflows/{id}?version=N → read workflow config
 * GET  {baseUrl}/llmstore/llms/{id}?version=N           → read LLM config
 * GET  {baseUrl}/rulestore/rulesets/{id}?version=N      → read behavior config
 * GET  {baseUrl}/apicallstore/apicalls/{id}?version=N   → read API calls config
 * etc. for all extension types
 * GET  {baseUrl}/snippetstore/snippets/descriptors      → list snippets
 * GET  {baseUrl}/snippetstore/snippets/{id}?version=N   → read snippet
 * </pre>
 *
 * @since 6.0.0
 */
public class RemoteApiResourceSource implements IResourceSource {

    private static final Logger LOGGER = Logger.getLogger(RemoteApiResourceSource.class);

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final String baseUrl;
    private final String agentId;
    private final Integer agentVersion;
    private final String authToken;
    private final IJsonSerialization jsonSerialization;
    private final HttpClient httpClient;

    // Lazily loaded and cached
    private AgentSourceData agentData;
    private List<WorkflowSourceData> workflowDataList;
    private List<SnippetSourceData> snippetDataList;

    /**
     * Descriptor listings already fetched from the remote instance, keyed by
     * descriptors path. A listing is unpaged ({@code limit=0}) and returns every
     * descriptor of that type in the whole remote deployment, so fetching it once
     * per workflow and once per extension — purely to fill a display name — turned
     * a single preview into dozens of full downloads.
     * <p>
     * An instance of this class serves exactly one sync request on one thread, so a
     * plain HashMap is enough.
     */
    private final Map<String, Map<String, String>> descriptorNamesByPath = new HashMap<>();

    /** Whether this instance owns {@link #httpClient} and must close it. */
    private final boolean ownsHttpClient;

    public RemoteApiResourceSource(String baseUrl, String agentId, Integer agentVersion,
            String authToken, IJsonSerialization jsonSerialization) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.agentId = agentId;
        this.agentVersion = agentVersion;
        this.authToken = authToken;
        this.jsonSerialization = jsonSerialization;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        this.ownsHttpClient = true;
    }

    // Visible for testing
    RemoteApiResourceSource(String baseUrl, String agentId, Integer agentVersion,
            String authToken, IJsonSerialization jsonSerialization,
            HttpClient httpClient) {
        this(baseUrl, agentId, agentVersion, authToken, jsonSerialization, httpClient, false);
    }

    /**
     * Visible for testing. The owned-client branch of {@link #close()} is otherwise
     * only reachable through the public constructor, which builds a real
     * {@link HttpClient} — and that opens a selector, which needs a loopback socket
     * a sandboxed build does not have.
     */
    RemoteApiResourceSource(String baseUrl, String agentId, Integer agentVersion,
            String authToken, IJsonSerialization jsonSerialization,
            HttpClient httpClient, boolean ownsHttpClient) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.agentId = agentId;
        this.agentVersion = agentVersion;
        this.authToken = authToken;
        this.jsonSerialization = jsonSerialization;
        this.httpClient = httpClient;
        this.ownsHttpClient = ownsHttpClient;
    }

    /**
     * Closes the HTTP client this instance created. Callers already wrap every
     * source in try-with-resources; without this override they inherited
     * {@link IResourceSource#close()}'s no-op, so a batch sync over N agents left N
     * clients — each with a selector thread and an executor — alive until GC.
     */
    @Override
    public void close() {
        if (ownsHttpClient) {
            httpClient.close();
        }
    }

    @Override
    public AgentSourceData readAgent() {
        if (agentData != null)
            return agentData;

        try {
            // Resolve version if not specified
            int version = agentVersion != null ? agentVersion : resolveLatestAgentVersion();

            String agentJson = httpGet("/agentstore/agents/" + agentId + "?version=" + version);
            AgentConfiguration config = jsonSerialization.deserialize(agentJson, AgentConfiguration.class);

            String agentName = readRemoteDescriptorName("/agentstore/agents/descriptors", agentId);

            agentData = new AgentSourceData(agentId, agentName, config);
            return agentData;
        } catch (Exception e) {
            throw new RuntimeException("Failed to read agent from remote instance " + baseUrl + ": " + e.getMessage(), e);
        }
    }

    @Override
    public List<WorkflowSourceData> readWorkflows() {
        if (workflowDataList != null)
            return workflowDataList;

        AgentSourceData agent = readAgent();
        workflowDataList = new ArrayList<>();

        List<URI> workflowUris = agent.config().getWorkflows();
        for (int i = 0; i < workflowUris.size(); i++) {
            if (Thread.currentThread().isInterrupted()) {
                // Per-workflow failures are tolerated, a cancellation is not: carrying on
                // would keep hitting the remote instance after the thread was asked to stop
                // and report a truncated read as a complete one.
                throw new RuntimeException("Interrupted while reading workflows from remote " + baseUrl);
            }
            try {
                WorkflowSourceData wfData = readSingleWorkflow(workflowUris.get(i), i);
                if (wfData != null) {
                    workflowDataList.add(wfData);
                }
            } catch (Exception e) {
                LOGGER.warnf(e, "Failed to read workflow %d from remote %s", i, baseUrl);
            }
        }

        return workflowDataList;
    }

    @Override
    public List<SnippetSourceData> readSnippets() {
        if (snippetDataList != null)
            return snippetDataList;
        snippetDataList = new ArrayList<>();

        try {
            // List all snippet descriptors from the remote instance
            String descriptorsJson = httpGet("/snippetstore/snippets/descriptors?index=0&limit=0");
            DocumentDescriptor[] descriptors = jsonSerialization.deserialize(descriptorsJson, DocumentDescriptor[].class);
            if (descriptors == null)
                return snippetDataList;

            for (DocumentDescriptor desc : descriptors) {
                try {
                    IResourceId resId = RestUtilities.extractResourceId(desc.getResource());
                    if (resId == null)
                        continue;

                    String snippetJson = httpGet("/snippetstore/snippets/" + resId.getId() + "?version=" + resId.getVersion());
                    PromptSnippet snippet = jsonSerialization.deserialize(snippetJson, PromptSnippet.class);

                    if (snippet != null && snippet.getName() != null) {
                        snippetDataList.add(new SnippetSourceData(
                                resId.getId(), snippet.getName(), snippet));
                    }
                } catch (Exception e) {
                    LOGGER.debugf("Could not read remote snippet %s: %s", desc.getName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            LOGGER.warnf("Failed to read snippets from remote %s: %s", baseUrl, e.getMessage());
        }

        return snippetDataList;
    }

    // ==================== Static Utility ====================

    /**
     * Lists agents on a remote EDDI instance. This is a static utility used by the
     * {@code listRemoteAgents} endpoint — it doesn't require a full
     * {@link RemoteApiResourceSource} instance since it doesn't target a specific
     * agent.
     *
     * @param baseUrl
     *            remote instance URL
     * @param authToken
     *            bearer token for the remote instance
     * @param jsonSerialization
     *            serialization service
     * @return list of agent descriptors from the remote instance
     */
    public static List<DocumentDescriptor> listRemoteAgentDescriptors(
                                                                      String baseUrl, String authToken, IJsonSerialization jsonSerialization) {
        String normalized = normalizeBaseUrl(baseUrl);
        if (!normalized.endsWith("/")) {
            normalized += "/";
        }
        URI baseUri = URI.create(normalized);

        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build()) {

            // codeql[java/ssrf] False Positive: It is an intended feature to connect to a
            // user-provided remote EDDI instance
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(baseUri.resolve("agentstore/agents/descriptors?index=0&limit=0"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json");
            if (authToken != null && !authToken.isBlank()) {
                builder.header("Authorization", authToken);
            }
            HttpRequest request = builder.GET().build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Remote instance returned status " + response.statusCode());
            }

            DocumentDescriptor[] descriptors = jsonSerialization.deserialize(
                    response.body(), DocumentDescriptor[].class);
            return descriptors != null ? List.of(descriptors) : List.of();
        } catch (InterruptedException e) {
            // Never swallow an interrupt: the caller (or the container shutting down)
            // asked this thread to stop, and a lost flag means the next blocking call
            // simply carries on.
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while listing agents from remote instance " + baseUrl, e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to list agents from remote instance " + baseUrl + ": " + e.getMessage(), e);
        }
    }

    // ==================== Internal Helpers ====================

    private WorkflowSourceData readSingleWorkflow(URI workflowUri, int positionIndex) throws IOException {
        IResourceId wfResId = RestUtilities.extractResourceId(workflowUri);
        // extractResourceId answers with an id of null for a URI that carries no
        // resource segment, so the null check has to be on the id: without it the
        // read went out as /workflowstore/workflows/null?version=0.
        if (wfResId == null || wfResId.getId() == null) {
            LOGGER.warnf("Agent %s on %s references a workflow URI with no resource id: %s",
                    agentId, baseUrl, workflowUri);
            return null;
        }

        String workflowId = wfResId.getId();
        int version = wfResId.getVersion();

        String workflowJson = httpGet("/workflowstore/workflows/" + workflowId + "?version=" + version);
        WorkflowConfiguration config = jsonSerialization.deserialize(workflowJson, WorkflowConfiguration.class);

        String workflowName = readRemoteDescriptorName("/workflowstore/workflows/descriptors", workflowId);

        // Read extensions from the workflow configuration
        Map<String, ExtensionSourceData> extensions = readExtensionsFromWorkflow(config);

        return new WorkflowSourceData(workflowId, workflowName, positionIndex, config, extensions);
    }

    /**
     * Reads all extension configs referenced by a workflow configuration, keyed by
     * the canonical {@link WorkflowExtensions} key so the map joins with the one
     * {@link StructuralMatcher} builds from the local target workflow.
     */
    private Map<String, ExtensionSourceData> readExtensionsFromWorkflow(WorkflowConfiguration config) {
        Map<String, ExtensionSourceData> extensions = new LinkedHashMap<>();

        for (WorkflowExtensions.ExtensionRef ref : WorkflowExtensions.scan(config)) {
            String restPath = ref.type().restPath();
            String extId = ref.resourceId().getId();
            try {
                String contentJson = httpGet(restPath + extId + "?version=" + ref.resourceId().getVersion());
                String name = readRemoteDescriptorName(descriptorsPathOf(restPath), extId);

                extensions.put(ref.key(), new ExtensionSourceData(
                        extId, name, ref.fileExtension(), ref.stepType(), contentJson));
            } catch (Exception e) {
                LOGGER.debugf("Could not read remote extension %s: %s", ref.extensionUri(), e.getMessage());
            }
        }

        return extensions;
    }

    /**
     * Resolves the latest version of the agent when no explicit version was
     * provided. Reads the agent descriptor list and finds the matching entry.
     * <p>
     * Failing to resolve is an error, not a reason to guess: falling back to
     * version 1 silently synced an arbitrarily old configuration into the target
     * whenever the descriptor listing was paginated, access-scoped, or briefly
     * unavailable.
     */
    private int resolveLatestAgentVersion() {
        DocumentDescriptor[] descriptors;
        try {
            String json = httpGet("/agentstore/agents/descriptors?index=0&limit=0");
            descriptors = jsonSerialization.deserialize(json, DocumentDescriptor[].class);
        } catch (Exception e) {
            throw new RuntimeException("Could not determine the latest version of agent " + agentId
                    + " on " + baseUrl + ": " + e.getMessage(), e);
        }

        if (descriptors != null) {
            for (DocumentDescriptor desc : descriptors) {
                IResourceId resId = RestUtilities.extractResourceId(desc.getResource());
                if (resId != null && agentId.equals(resId.getId())) {
                    return resId.getVersion();
                }
            }
        }

        throw new RuntimeException("Agent " + agentId + " is not listed on " + baseUrl
                + " — cannot determine its latest version. Pass an explicit sourceAgentVersion.");
    }

    /**
     * Name of a resource, taken from its store's descriptor listing. The listing is
     * fetched at most once per store per request — see
     * {@link #descriptorNamesByPath}.
     */
    private String readRemoteDescriptorName(String descriptorsPath, String resourceId) {
        return descriptorNamesByPath
                .computeIfAbsent(descriptorsPath, this::loadDescriptorNames)
                .get(resourceId);
    }

    private Map<String, String> loadDescriptorNames(String descriptorsPath) {
        Map<String, String> names = new HashMap<>();
        try {
            String json = httpGet(descriptorsPath + "?index=0&limit=0");
            DocumentDescriptor[] descriptors = jsonSerialization.deserialize(json, DocumentDescriptor[].class);
            if (descriptors != null) {
                for (DocumentDescriptor desc : descriptors) {
                    IResourceId resId = RestUtilities.extractResourceId(desc.getResource());
                    if (resId != null && resId.getId() != null && desc.getName() != null) {
                        names.putIfAbsent(resId.getId(), desc.getName());
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debugf("Could not read remote descriptors from %s: %s", descriptorsPath, e.getMessage());
        }
        return names;
    }

    /** e.g. {@code /llmstore/llms/} → {@code /llmstore/llms/descriptors}. */
    private static String descriptorsPathOf(String storePath) {
        String path = storePath.endsWith("/") ? storePath.substring(0, storePath.length() - 1) : storePath;
        return path + "/descriptors";
    }

    /**
     * Executes an authenticated HTTP GET against the remote EDDI instance.
     *
     * @param path
     *            relative path (e.g., "/agentstore/agents/abc123?version=1")
     * @return response body as string
     * @throws RuntimeException
     *             on HTTP errors or connection failures
     */
    private String httpGet(String path) {
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("Path must start with '/'.");
        }
        String normalized = this.baseUrl;
        if (!normalized.endsWith("/")) {
            normalized += "/";
        }
        URI requestUri = URI.create(normalized).resolve(path.substring(1));
        try {
            // codeql[java/ssrf] False Positive: It is an intended feature to connect to a
            // user-provided remote EDDI instance
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(requestUri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json");

            if (authToken != null && !authToken.isBlank()) {
                builder.header("Authorization", authToken);
            }

            HttpRequest request = builder.GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Remote " + baseUrl + path + " returned status " + response.statusCode());
            }

            return response.body();
        } catch (InterruptedException e) {
            // Restore the flag before unwinding: readWorkflows catches per-workflow
            // failures and carries on, so a swallowed interrupt meant a cancelled or
            // shutting-down batch sync kept issuing HTTP calls to the remote instance.
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while reading " + baseUrl + path, e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to connect to remote " + baseUrl + path + ": " + e.getMessage(), e);
        }
    }

    private static String normalizeBaseUrl(String url) {
        if (url == null)
            return "";
        // Remove trailing slash
        String normalized = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;

        // Validate URL to mitigate SSRF concerns by ensuring a valid network scheme and
        // host. Note: Connecting to a user-provided instance is an intended feature.
        // URI.create and the guards below can only produce IllegalArgumentException,
        // so there is nothing else to catch and re-wrap here.
        URI uri = URI.create(normalized);
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("Only HTTP or HTTPS schemes are allowed: " + url);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("Invalid base URL host: " + url);
        }

        return normalized;
    }
}
