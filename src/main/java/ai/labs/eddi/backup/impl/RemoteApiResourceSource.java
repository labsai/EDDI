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
import ai.labs.eddi.utils.LogSanitizer;
import ai.labs.eddi.utils.RestUtilities;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    /**
     * Downloading an archive is a bulk transfer, not a config read, so it gets its
     * own budget: a large agent over a slow link must not fail on the per-request
     * timeout that a single JSON document is sized for.
     */
    private static final Duration ARCHIVE_DOWNLOAD_TIMEOUT = Duration.ofMinutes(5);

    /**
     * The largest export archive a first-time sync will take from a remote.
     * <p>
     * The body is read into memory before it is imported, so without a bound a
     * source that is compromised — or simply wrong about what it is serving —
     * decides how much heap this instance allocates. 256 MB is far above any real
     * agent archive (a large one is single-digit MB) and far below a heap.
     */
    private static final long MAX_ARCHIVE_BYTES = 256L * 1024 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** The {@code "resource": "eddi://…"} field of a descriptor listing entry. */
    private static final Pattern RESOURCE_FIELD = Pattern.compile("\"resource\"\s*:\s*\"([^\"]+)\"");

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
        this.httpClient = configure(HttpClient.newBuilder()).build();
        this.ownsHttpClient = true;
    }

    /**
     * The configuration every {@link HttpClient} this class owns is built with.
     * <p>
     * Redirects are stated rather than inherited. A redirect is never followed, so
     * a remote instance answering 3xx can never bounce the caller's
     * X-Source-Authorization bearer at an address of its choosing: the hop surfaces
     * as a non-200 status and the read fails. This is the JDK's default too, and
     * saying so is what stops a later edit from changing it without anyone
     * noticing.
     * <p>
     * Both build sites go through here, and it is package-private, so that the
     * policy can be asserted directly. Building a real client to read it back is
     * not an option in a sandboxed build: {@code HttpClient.build()} opens a
     * selector, which needs a loopback socket.
     */
    static HttpClient.Builder configure(HttpClient.Builder builder) {
        return builder
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER);
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
            throw new RemoteReadException("Failed to read agent from remote instance " + baseUrl + ": " + e.getMessage(), e);
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
                throw new RemoteReadException("Interrupted while reading workflows from remote " + baseUrl);
            }
            try {
                WorkflowSourceData wfData = readSingleWorkflow(workflowUris.get(i), i);
                if (wfData != null) {
                    workflowDataList.add(wfData);
                }
            } catch (Exception e) {
                LOGGER.warnf(e, "Failed to read workflow %d from remote %s", i, LogSanitizer.sanitize(baseUrl));
            }
        }

        return workflowDataList;
    }

    /**
     * The snippets <em>this agent</em> references, as {@link IResourceSource}
     * specifies — not every snippet the remote instance holds.
     * <p>
     * The remote store has no per-agent snippet listing, so the names come from the
     * agent's own configuration documents, exactly as {@code RestExportService}
     * decides what to put in an archive. Reading the whole store instead offered
     * the operator every snippet on the source as a CREATE, so promoting one agent
     * proposed copying a staging instance's entire snippet library — unreleased
     * drafts and other teams' snippets included — into production.
     */
    @Override
    public List<SnippetSourceData> readSnippets() {
        if (snippetDataList != null)
            return snippetDataList;
        snippetDataList = new ArrayList<>();

        Set<String> referencedNames = SnippetReferences.namesIn(configDocumentsForSnippetScan());
        if (referencedNames.isEmpty()) {
            return snippetDataList;
        }

        try {
            // The store is listed in full because that is the only listing there is;
            // what is downloaded and offered is then narrowed to the referenced names.
            String descriptorsJson = httpGet("/snippetstore/snippets/descriptors?index=0&limit=0");
            DocumentDescriptor[] descriptors = jsonSerialization.deserialize(descriptorsJson, DocumentDescriptor[].class);
            if (descriptors == null)
                return snippetDataList;

            for (DocumentDescriptor desc : descriptors) {
                try {
                    IResourceId resId = RestUtilities.extractResourceId(desc.getResource());
                    if (resId == null)
                        continue;

                    // Skip the download when the descriptor already proves it is not one
                    // of ours. A descriptor with no name still has to be read, because
                    // the name that matters lives on the snippet itself.
                    String descriptorName = desc.getName();
                    if (descriptorName != null && !descriptorName.isBlank() && !referencedNames.contains(descriptorName)) {
                        continue;
                    }

                    String snippetJson = httpGet("/snippetstore/snippets/" + resId.getId() + "?version=" + resId.getVersion());
                    PromptSnippet snippet = jsonSerialization.deserialize(snippetJson, PromptSnippet.class);

                    if (snippet != null && snippet.getName() != null && referencedNames.contains(snippet.getName())) {
                        snippetDataList.add(new SnippetSourceData(
                                resId.getId(), snippet.getName(), snippet));
                    }
                } catch (Exception e) {
                    LOGGER.debugf("Could not read remote snippet %s: %s",
                            LogSanitizer.sanitize(desc.getName()), LogSanitizer.sanitize(e.getMessage()));
                }
            }
        } catch (Exception e) {
            LOGGER.warnf("Failed to read snippets from remote %s: %s",
                    LogSanitizer.sanitize(baseUrl), LogSanitizer.sanitize(e.getMessage()));
        }

        return snippetDataList;
    }

    /**
     * Everything a snippet reference could be written in: the agent document and,
     * for every workflow, the workflow document and each of its extension configs.
     */
    private List<String> configDocumentsForSnippetScan() {
        List<String> documents = new ArrayList<>();
        try {
            AgentSourceData agent = readAgent();
            if (agent != null) {
                documents.add(serializeQuietly(agent.config()));
            }
            for (WorkflowSourceData workflow : readWorkflows()) {
                documents.add(serializeQuietly(workflow.config()));
                for (ExtensionSourceData extension : workflow.extensions().values()) {
                    documents.add(extension.contentJson());
                }
            }
        } catch (Exception e) {
            // A source that cannot be read at all fails loudly elsewhere; here it just
            // means no snippet can be attributed to this agent.
            LOGGER.warnf("Could not scan agent %s for snippet references: %s",
                    LogSanitizer.sanitize(agentId), LogSanitizer.sanitize(e.getMessage()));
        }
        return documents;
    }

    private String serializeQuietly(Object config) {
        if (config == null) {
            return null;
        }
        try {
            return jsonSerialization.serialize(config);
        } catch (Exception e) {
            return null;
        }
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

        try (HttpClient client = configure(HttpClient.newBuilder()).build()) {

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
                throw new RemoteReadException("Remote instance returned status " + response.statusCode());
            }

            DocumentDescriptor[] descriptors = jsonSerialization.deserialize(
                    response.body(), DocumentDescriptor[].class);
            return descriptors != null ? List.of(descriptors) : List.of();
        } catch (InterruptedException e) {
            // Never swallow an interrupt: the caller (or the container shutting down)
            // asked this thread to stop, and a lost flag means the next blocking call
            // simply carries on.
            Thread.currentThread().interrupt();
            throw new RemoteReadException("Interrupted while listing agents from remote instance " + baseUrl, e);
        } catch (Exception e) {
            throw new RemoteReadException("Failed to list agents from remote instance " + baseUrl + ": " + e.getMessage(), e);
        }
    }

    /**
     * Asks the remote instance to export an agent and downloads the archive.
     * <p>
     * This is how a live sync creates an agent the target does not have yet. The
     * alternative — writing every resource from {@link #readAgent()} and friends
     * with a second set of create calls — would have been a parallel implementation
     * of {@code RestImportService}'s create path that quietly did less: no
     * schedules, no connections, no capability registration, no rollback. Fetching
     * the source's own archive and handing it to the importer that already exists
     * means a first promotion over the wire lands exactly what the same archive
     * would have landed by hand.
     * <p>
     * <b>The {@code Location} the export answers with is deliberately not
     * followed.</b> Only its last path segment — the archive's file name — is
     * taken, and the download is issued against the base URL that
     * {@link SourceUrlValidator} already approved. A source instance that answered
     * with an absolute URL of its choosing would otherwise decide where this
     * deployment sends the caller's bearer token, which is the SSRF the validator
     * exists to prevent.
     *
     * @return the archive bytes
     */
    public static byte[] exportAgentArchive(String baseUrl, String agentId, Integer agentVersion, String authToken) {
        String normalized = normalizeBaseUrl(baseUrl);
        URI baseUri = URI.create(normalized.endsWith("/") ? normalized : normalized + "/");

        try (HttpClient client = configure(HttpClient.newBuilder()).build()) {
            // The version is always stated. The remote's export defaults it to 1
            // (@DefaultValue("1") on IRestExportService.exportAgent), so omitting it
            // for "latest" — which is what the API documents a null version as, and
            // what the preview resolves it to — would quietly promote the agent's
            // OLDEST configuration while the preview showed its newest.
            int version = agentVersion != null
                    ? agentVersion
                    : latestAgentVersion(client, baseUri, agentId, authToken);
            String exportPath = "backup/export/" + encodePathSegment(agentId) + "?agentVersion=" + version;

            // codeql[java/ssrf] False Positive: connecting to the operator-approved
            // remote EDDI instance is the feature
            HttpResponse<Void> exportResponse = client.send(
                    // Not REQUEST_TIMEOUT: that budget is sized for reading one JSON
                    // document, and this POST makes the remote read every workflow and
                    // extension, scrub secrets, gather snippets and schedules and zip
                    // the lot before it answers. A large agent legitimately takes
                    // longer than a config read.
                    authorized(HttpRequest.newBuilder().uri(baseUri.resolve(exportPath)).timeout(ARCHIVE_DOWNLOAD_TIMEOUT),
                            authToken).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());

            if (exportResponse.statusCode() < 200 || exportResponse.statusCode() >= 300) {
                throw new RemoteReadException("Remote instance refused to export agent " + agentId
                        + " (status " + exportResponse.statusCode() + ")");
            }

            String archiveName = archiveNameFrom(exportResponse.headers().firstValue("Location").orElse(null));
            if (archiveName == null) {
                throw new RemoteReadException("Remote instance exported agent " + agentId
                        + " but named no archive to download");
            }

            // codeql[java/ssrf] False Positive: same approved base URL as above
            // ofInputStream, not ofByteArray: the byte-array handler blocks until the
            // WHOLE body is in memory, so a size check after it runs is a check on an
            // allocation that has already happened. The stream is read to the cap and
            // no further.
            HttpResponse<InputStream> download = client.send(
                    authorized(HttpRequest.newBuilder()
                            .uri(baseUri.resolve("backup/export/" + encodePathSegment(archiveName)))
                            .timeout(ARCHIVE_DOWNLOAD_TIMEOUT), authToken).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());

            if (download.statusCode() != 200) {
                throw new RemoteReadException("Could not download the exported archive for agent " + agentId
                        + " (status " + download.statusCode() + ")");
            }
            long declared = download.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (declared > MAX_ARCHIVE_BYTES) {
                throw new RemoteReadException("The exported archive for agent " + agentId + " is "
                        + declared + " bytes, more than this instance will import (" + MAX_ARCHIVE_BYTES + ")");
            }
            byte[] body = readAtMost(download.body(), agentId);
            if (body.length == 0) {
                throw new RemoteReadException("The exported archive for agent " + agentId + " came back empty");
            }
            return body;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RemoteReadException("Interrupted while exporting agent " + agentId + " from " + baseUrl, e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RemoteReadException("Failed to export agent " + agentId + " from " + baseUrl + ": " + e.getMessage(), e);
        }
    }

    /**
     * The version the remote instance's descriptor says an agent is currently at.
     * <p>
     * The static export path cannot use the instance method
     * {@code resolveLatestAgentVersion}, and must not guess: see the call site for
     * what guessing cost.
     */
    private static int latestAgentVersion(HttpClient client, URI baseUri, String agentId, String authToken) {
        try {
            // codeql[java/ssrf] False Positive: the operator-approved remote instance
            HttpResponse<String> response = client.send(
                    authorized(HttpRequest.newBuilder()
                            .uri(baseUri.resolve("agentstore/agents/descriptors?index=0&limit=0"))
                            .timeout(REQUEST_TIMEOUT)
                            .header("Accept", "application/json"), authToken).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RemoteReadException("Could not list agents on " + baseUri
                        + " to find the latest version of " + agentId + " (status " + response.statusCode() + ")");
            }
            for (String resource : agentResourceUris(response.body())) {
                IResourceId resourceId = RestUtilities.extractResourceId(URI.create(resource));
                if (resourceId != null && agentId.equals(resourceId.getId())) {
                    return resourceId.getVersion();
                }
            }
            throw new RemoteReadException("Remote instance " + baseUri + " has no agent " + agentId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RemoteReadException("Interrupted while resolving the latest version of agent " + agentId, e);
        } catch (RemoteReadException e) {
            throw e;
        } catch (Exception e) {
            throw new RemoteReadException("Could not determine the latest version of agent " + agentId
                    + " on " + baseUri + ": " + e.getMessage(), e);
        }
    }

    /**
     * The {@code resource} URI of every descriptor in a listing.
     * <p>
     * Read with a regex rather than the serializer because this path is static and
     * has no {@link IJsonSerialization} to hand — and because all it needs is the
     * one field.
     */
    private static List<String> agentResourceUris(String descriptorsJson) {
        List<String> uris = new ArrayList<>();
        if (descriptorsJson == null) {
            return uris;
        }
        Matcher matcher = RESOURCE_FIELD.matcher(descriptorsJson);
        while (matcher.find()) {
            uris.add(matcher.group(1));
        }
        return uris;
    }

    /**
     * Reads the archive, refusing to allocate more than {@link #MAX_ARCHIVE_BYTES}.
     * <p>
     * The bound is enforced <em>while</em> reading. A remote that declares no
     * {@code Content-Length}, or declares one and then sends more, cannot make this
     * instance hold an unbounded body in memory before the guard is reached: the
     * read stops one byte past the cap and the stream is closed.
     */
    private static byte[] readAtMost(InputStream body, String agentId) throws IOException {
        if (body == null) {
            return new byte[0];
        }
        try (body; ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            long total = 0;
            int read;
            while ((read = body.read(chunk)) != -1) {
                total += read;
                if (total > MAX_ARCHIVE_BYTES) {
                    throw new RemoteReadException("The exported archive for agent " + agentId
                            + " is larger than this instance will import (" + MAX_ARCHIVE_BYTES + " bytes)");
                }
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }
    }

    /**
     * The archive's file name from the export's {@code Location}, and nothing else
     * from it.
     * <p>
     * Any path traversal the remote might put there ({@code ../../etc/passwd}, an
     * absolute path, a nested directory) is discarded with the rest of the path:
     * only a plain final segment is accepted.
     *
     * @return the file name, or null when the header names none that is usable
     */
    static String archiveNameFrom(String locationHeader) {
        if (locationHeader == null || locationHeader.isBlank()) {
            return null;
        }
        String path = locationHeader;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int lastSlash = path.lastIndexOf('/');
        String name = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        name = name.trim();
        // A segment that is not a plain file name is a remote trying to steer this
        // request somewhere else.
        if (name.isEmpty() || name.contains("..") || name.contains("\\") || !name.endsWith(".zip")) {
            return null;
        }
        return name;
    }

    private static HttpRequest.Builder authorized(HttpRequest.Builder builder, String authToken) {
        if (authToken != null && !authToken.isBlank()) {
            builder.header("Authorization", authToken);
        }
        return builder;
    }

    private static String encodePathSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    // ==================== Internal Helpers ====================

    private WorkflowSourceData readSingleWorkflow(URI workflowUri, int positionIndex) throws IOException {
        IResourceId wfResId = RestUtilities.extractResourceId(workflowUri);
        // extractResourceId answers with an id of null for a URI that carries no
        // resource segment, so the null check has to be on the id: without it the
        // read went out as /workflowstore/workflows/null?version=0.
        if (wfResId == null || wfResId.getId() == null) {
            LOGGER.warnf("Agent %s on %s references a workflow URI with no resource id: %s",
                    LogSanitizer.sanitize(agentId), LogSanitizer.sanitize(baseUrl),
                    LogSanitizer.sanitize(String.valueOf(workflowUri)));
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
                LOGGER.debugf("Could not read remote extension %s: %s",
                        LogSanitizer.sanitize(String.valueOf(ref.extensionUri())), LogSanitizer.sanitize(e.getMessage()));
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
            throw new RemoteReadException("Could not determine the latest version of agent " + agentId
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
            LOGGER.debugf("Could not read remote descriptors from %s: %s",
                    LogSanitizer.sanitize(descriptorsPath), LogSanitizer.sanitize(e.getMessage()));
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
                throw new RemoteReadException("Remote " + baseUrl + path + " returned status " + response.statusCode());
            }

            return response.body();
        } catch (InterruptedException e) {
            // Restore the flag before unwinding: readWorkflows catches per-workflow
            // failures and carries on, so a swallowed interrupt meant a cancelled or
            // shutting-down batch sync kept issuing HTTP calls to the remote instance.
            Thread.currentThread().interrupt();
            throw new RemoteReadException("Interrupted while reading " + baseUrl + path, e);
        } catch (IOException e) {
            throw new RemoteReadException("Failed to connect to remote " + baseUrl + path + ": " + e.getMessage(), e);
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

    /**
     * A failure to read the <em>other</em> instance.
     * <p>
     * Every failure this class raises is one: unreachable, refusing the token,
     * answering a status or a body that cannot be used. Naming the category lets
     * the endpoints answer {@code 502 Bad Gateway} for it and keep {@code 500} for
     * what this deployment itself got wrong — a distinction an operator needs and a
     * single {@code RuntimeException} could not carry.
     */
    public static class RemoteReadException extends RuntimeException {

        public RemoteReadException(String message) {
            super(message);
        }

        public RemoteReadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
