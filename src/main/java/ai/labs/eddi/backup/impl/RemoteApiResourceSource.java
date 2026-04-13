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

    private static final Logger log = Logger.getLogger(RemoteApiResourceSource.class);

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
     * Extension type mappings: step type URI → REST path segment. The REST path
     * segment is the portion of the URL that identifies the store for this
     * extension type.
     */
    private static final Map<String, String> STEP_TYPE_TO_REST_PATH = Map.of(
            "ai.labs.dictionary", "/dictionarystore/dictionaries/",
            "ai.labs.rules", "/rulestore/rulesets/",
            "ai.labs.apicalls", "/apicallstore/apicalls/",
            "ai.labs.llm", "/llmstore/llms/",
            "ai.labs.property", "/propertysetterstore/propertysetters/",
            "ai.labs.output", "/outputstore/outputsets/",
            "ai.labs.mcpcalls", "/mcpcallsstore/mcpcalls/",
            "ai.labs.rag", "/ragstore/rags/");

    /**
     * Step type URI → file extension label (for ExtensionSourceData.type).
     */
    private static final Map<String, String> STEP_TYPE_TO_EXT_LABEL = Map.of(
            "ai.labs.dictionary", "regulardictionary",
            "ai.labs.rules", "behavior",
            "ai.labs.apicalls", "httpcalls",
            "ai.labs.llm", "langchain",
            "ai.labs.property", "property",
            "ai.labs.output", "output",
            "ai.labs.mcpcalls", "mcpcalls",
            "ai.labs.rag", "rag");

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
    }

    // Visible for testing
    RemoteApiResourceSource(String baseUrl, String agentId, Integer agentVersion,
            String authToken, IJsonSerialization jsonSerialization,
            HttpClient httpClient) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.agentId = agentId;
        this.agentVersion = agentVersion;
        this.authToken = authToken;
        this.jsonSerialization = jsonSerialization;
        this.httpClient = httpClient;
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
            try {
                WorkflowSourceData wfData = readSingleWorkflow(workflowUris.get(i), i);
                if (wfData != null) {
                    workflowDataList.add(wfData);
                }
            } catch (Exception e) {
                log.warnf("Failed to read workflow %d from remote %s: %s", i, baseUrl, e.getMessage());
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
                    log.debugf("Could not read remote snippet %s: %s", desc.getName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warnf("Failed to read snippets from remote %s: %s", baseUrl, e.getMessage());
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
        try {
            String normalized = normalizeBaseUrl(baseUrl);
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .build();

            // codeql[java/ssrf] False Positive: It is an intended feature to connect to a
            // user-provided remote EDDI instance
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(normalized + "/agentstore/agents/descriptors?index=0&limit=0"))
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
        } catch (Exception e) {
            throw new RuntimeException("Failed to list agents from remote instance " + baseUrl + ": " + e.getMessage(), e);
        }
    }

    // ==================== Internal Helpers ====================

    private WorkflowSourceData readSingleWorkflow(URI workflowUri, int positionIndex) throws IOException {
        IResourceId wfResId = RestUtilities.extractResourceId(workflowUri);
        if (wfResId == null)
            return null;

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
     * Reads all extension configs referenced by a workflow configuration by parsing
     * each workflow step and fetching the extension resource from the remote
     * instance.
     */
    private Map<String, ExtensionSourceData> readExtensionsFromWorkflow(WorkflowConfiguration config) {
        Map<String, ExtensionSourceData> extensions = new LinkedHashMap<>();

        for (WorkflowConfiguration.WorkflowStep step : config.getWorkflowSteps()) {
            URI stepType = step.getType();
            if (stepType == null)
                continue;

            Object uriObj = step.getExtensions().get("uri");
            if (uriObj == null)
                continue;

            URI extUri = URI.create(uriObj.toString());
            IResourceId extResId = RestUtilities.extractResourceId(extUri);
            if (extResId == null)
                continue;

            String stepTypeStr = stepType.toString();
            String restPath = STEP_TYPE_TO_REST_PATH.get(stepTypeStr);
            String extLabel = STEP_TYPE_TO_EXT_LABEL.get(stepTypeStr);
            if (restPath == null || extLabel == null) {
                log.debugf("Unknown step type: %s", stepTypeStr);
                continue;
            }

            try {
                String contentJson = httpGet(restPath + extResId.getId() + "?version=" + extResId.getVersion());

                // Try to read the descriptor name
                String name = tryReadDescriptorName(restPath, extResId.getId());

                extensions.put(stepTypeStr, new ExtensionSourceData(
                        extResId.getId(), name, extLabel, stepTypeStr, contentJson));
            } catch (Exception e) {
                log.debugf("Could not read remote extension %s/%s: %s",
                        stepTypeStr, extResId.getId(), e.getMessage());
            }
        }

        return extensions;
    }

    /**
     * Resolves the latest version of the agent when no explicit version was
     * provided. Reads the agent descriptor list and finds the matching entry.
     */
    private int resolveLatestAgentVersion() {
        try {
            String json = httpGet("/agentstore/agents/descriptors?index=0&limit=0");
            DocumentDescriptor[] descriptors = jsonSerialization.deserialize(json, DocumentDescriptor[].class);
            if (descriptors != null) {
                for (DocumentDescriptor desc : descriptors) {
                    IResourceId resId = RestUtilities.extractResourceId(desc.getResource());
                    if (resId != null && agentId.equals(resId.getId())) {
                        return resId.getVersion();
                    }
                }
            }
        } catch (Exception e) {
            log.debugf("Could not resolve latest version for %s: %s", agentId, e.getMessage());
        }
        return 1; // fallback
    }

    /**
     * Tries to find a resource's name from the descriptor endpoint.
     */
    private String readRemoteDescriptorName(String descriptorsPath, String resourceId) {
        try {
            String json = httpGet(descriptorsPath + "?index=0&limit=0");
            DocumentDescriptor[] descriptors = jsonSerialization.deserialize(json, DocumentDescriptor[].class);
            if (descriptors != null) {
                for (DocumentDescriptor desc : descriptors) {
                    IResourceId resId = RestUtilities.extractResourceId(desc.getResource());
                    if (resId != null && resourceId.equals(resId.getId())) {
                        return desc.getName();
                    }
                }
            }
        } catch (Exception e) {
            log.debugf("Could not read remote descriptor name for %s: %s", resourceId, e.getMessage());
        }
        return null;
    }

    /**
     * Best-effort name read for extension resources.
     */
    private String tryReadDescriptorName(String storePath, String resourceId) {
        // Derive descriptors path from store path:
        // e.g., "/llmstore/llms/" → "/llmstore/llms/descriptors"
        String descriptorsPath = storePath;
        if (descriptorsPath.endsWith("/")) {
            descriptorsPath = descriptorsPath.substring(0, descriptorsPath.length() - 1);
        }
        descriptorsPath = descriptorsPath + "/descriptors";

        try {
            return readRemoteDescriptorName(descriptorsPath, resourceId);
        } catch (Exception e) {
            return null;
        }
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
        try {
            // codeql[java/ssrf] False Positive: It is an intended feature to connect to a
            // user-provided remote EDDI instance
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
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
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to connect to remote " + baseUrl + path + ": " + e.getMessage(), e);
        }
    }

    private static String normalizeBaseUrl(String url) {
        if (url == null)
            return "";
        // Remove trailing slash
        String normalized = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;

        try {
            // Validate URL to mitigate SSRF concerns by ensuring a valid network scheme and
            // host.
            // Note: Connecting to a user-provided instance is an intended feature.
            URI uri = URI.create(normalized);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException("Only HTTP or HTTPS schemes are allowed: " + url);
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("Invalid base URL host: " + url);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid base URL: " + url, e);
        }

        return normalized;
    }
}
