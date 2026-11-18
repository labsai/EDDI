/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.model;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Where a knowledge base gets its documents from, and on what terms.
 *
 * <p>
 * A source belongs to its {@link RagConfiguration} rather than being a resource
 * of its own. That is deliberate: the vector store is keyed by the knowledge
 * base, so a source that could exist independently of one would need to name
 * its target by a string, and the draft this replaces did exactly that and
 * keyed ingestion on the <em>source's</em> name while retrieval keyed on the
 * <em>knowledge base's</em>. Crawled content went into one table and queries
 * read another, and because no test performed a retrieval after an ingest,
 * nothing failed. Owning the source removes the possibility.
 *
 * <h2>Defaults are defaults, not traps</h2>
 * <p>
 * Every unset value falls back to something sensible. The draft's config
 * records threw on a missing {@code maxPages} or {@code maxDepth} — which is
 * precisely what Jackson supplies for an omitted field — so {@code {"scope":
 * {"pathPrefix": "/docs/"}}} was rejected outright while its own comment
 * claimed it "provides safe defaults". Values that are present but nonsensical
 * are still rejected, loudly, by {@link #validate}.
 */
public class IngestionSource {

    /** Documents are crawled from a website. */
    public static final String TYPE_WEB = "web";

    /**
     * Documents are files an operator uploaded, held by EDDI and re-read on every
     * run.
     *
     * <p>
     * Keeping the files rather than embedding them once and forgetting them is what
     * makes this a source at all: changing the embedding model or the chunk size
     * re-ingests from what is stored, a purge is recoverable, and deleting a file
     * removes its vectors through the same reconciliation every other source uses.
     * The alternative — embed on upload, keep nothing — would make every one of
     * those an ask-the-operator-to-upload-200-files-again.
     */
    public static final String TYPE_UPLOAD = "upload";

    /**
     * Documents are read from a Google Shared Drive through a service-account
     * connection — see {@link GoogleDriveSource}.
     */
    public static final String TYPE_GOOGLE_DRIVE = "googleDrive";

    /** Stable identity within the knowledge base; generated when absent. */
    private String id;

    /** Operator-facing label. */
    private String name;

    /** A disabled source keeps its configuration and history but does not run. */
    private boolean enabled = true;

    private String type = TYPE_WEB;

    /** Populated when {@link #type} is {@link #TYPE_WEB}. */
    private WebSource web;

    /**
     * Optional for a {@link #TYPE_UPLOAD} source — absent means all defaults, the
     * same as everywhere else in this class.
     */
    private UploadSource upload;

    /** Populated when {@link #type} is {@link #TYPE_GOOGLE_DRIVE}. */
    private GoogleDriveSource googleDrive;

    private IngestionSettings settings;

    /**
     * Standard five-field cron for scheduled runs — {@code min hour dom month dow},
     * the form {@code CronParser} accepts. Not Quartz: a six- or seven-field
     * expression with seconds is refused. Null means the source only runs when
     * triggered by hand.
     */
    private String cron;

    public void validate() {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Ingestion source needs a name");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Ingestion source '" + name + "' needs a type");
        }
        switch (type) {
            case TYPE_WEB -> {
                if (web == null) {
                    throw new IllegalArgumentException("Web ingestion source '" + name + "' needs a 'web' block");
                }
                web.validate(name);
            }
            case TYPE_UPLOAD -> upload().validate(name);
            case TYPE_GOOGLE_DRIVE -> {
                if (googleDrive == null) {
                    throw new IllegalArgumentException(
                            "Google Drive ingestion source '" + name + "' needs a 'googleDrive' block");
                }
                googleDrive.validate(name);
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported ingestion source type '" + type + "' on source '" + name
                            + "'. Supported: " + TYPE_WEB + ", " + TYPE_UPLOAD + ", " + TYPE_GOOGLE_DRIVE);
        }
        settings().validate(name);
    }

    /**
     * What this source's ingestion state is keyed by: its id, or its name when it
     * has none. Two sources of one knowledge base must never share it — see
     * {@code RagConfiguration.validate()}.
     */
    public String effectiveId() {
        return id == null || id.isBlank() ? name : id;
    }

    /** Never null — an absent settings block means "all defaults". */
    public IngestionSettings settings() {
        return settings == null ? new IngestionSettings() : settings;
    }

    /** Never null — an absent upload block means "all defaults". */
    public UploadSource upload() {
        return upload == null ? new UploadSource() : upload;
    }

    /** Whether this source's documents come from uploaded files. */
    public boolean isUpload() {
        return TYPE_UPLOAD.equals(type);
    }

    /**
     * Whether two configurations of one source read the same kind of thing, so that
     * what the first ingested still belongs to the second. A change of type — a
     * website replaced by a Drive folder under the same id — is a different source,
     * and what the old one put into the knowledge base has to go.
     */
    public boolean sameTypeAs(IngestionSource other) {
        return other != null && Objects.equals(typeOrDefault(), other.typeOrDefault());
    }

    private String typeOrDefault() {
        return type == null || type.isBlank() ? TYPE_WEB : type;
    }

    // --- Getters and Setters ---

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public WebSource getWeb() {
        return web;
    }

    public void setWeb(WebSource web) {
        this.web = web;
    }

    public UploadSource getUpload() {
        return upload;
    }

    public void setUpload(UploadSource upload) {
        this.upload = upload;
    }

    public GoogleDriveSource getGoogleDrive() {
        return googleDrive;
    }

    public void setGoogleDrive(GoogleDriveSource googleDrive) {
        this.googleDrive = googleDrive;
    }

    public IngestionSettings getSettings() {
        return settings;
    }

    public void setSettings(IngestionSettings settings) {
        this.settings = settings;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    /** Crawl configuration for a {@link #TYPE_WEB} source. */
    public static class WebSource {

        /**
         * Cap on configured sitemaps — the same ceiling the crawler applies to the
         * sitemaps it reads per run, indexes included.
         */
        static final int MAX_SITEMAP_URLS = 20;

        private String startUrl;

        /** Stay on the seed's site. */
        private boolean sameSiteOnly = true;

        /**
         * Treat subdomains as part of the site. Off by default: a crawl seeded at a
         * docs site should not wander into every subdomain a company owns.
         */
        private boolean includeSubdomains;

        private String pathPrefix = "/";
        private Integer maxDepth = 3;
        private Integer maxPages = 200;
        private List<String> excludePatterns = new ArrayList<>();

        /**
         * Sitemaps to seed the crawl from, in addition to any the site's robots.txt
         * advertises. For a site that publishes a sitemap without listing it in
         * robots.txt, or a crawl with {@link #respectRobots} off, which reads no
         * robots.txt at all. Sitemap indexes are followed. The pages they list are held
         * to the same scope as linked ones.
         */
        private List<String> sitemapUrls = new ArrayList<>();

        private Integer requestDelayMs = 500;
        private Integer timeoutSeconds = 15;
        private String userAgent;

        /**
         * Honour {@code robots.txt}. On by default — EDDI installations crawl sites
         * their operators do not own, on a schedule. Turn it off only for a site you
         * own.
         */
        private boolean respectRobots = true;

        void validate(String sourceName) {
            if (startUrl == null || startUrl.isBlank()) {
                throw new IllegalArgumentException("Ingestion source '" + sourceName + "' needs a startUrl");
            }
            if (!startUrl.startsWith("http://") && !startUrl.startsWith("https://")) {
                throw new IllegalArgumentException(
                        "startUrl of ingestion source '" + sourceName + "' must be http or https, got: " + startUrl);
            }
            requireRoutableHost(startUrl, "startUrl", sourceName);
            if (sitemapUrls.size() > MAX_SITEMAP_URLS) {
                throw new IllegalArgumentException("sitemapUrls of ingestion source '" + sourceName + "' may list at most "
                        + MAX_SITEMAP_URLS + " sitemaps, got: " + sitemapUrls.size());
            }
            for (String sitemapUrl : sitemapUrls) {
                if (sitemapUrl == null || !(sitemapUrl.startsWith("http://") || sitemapUrl.startsWith("https://"))) {
                    throw new IllegalArgumentException(
                            "sitemapUrls of ingestion source '" + sourceName + "' must be http or https URLs, got: " + sitemapUrl);
                }
                requireRoutableHost(sitemapUrl, "sitemapUrls", sourceName);
            }
            requirePositiveAtMost(maxDepth, 20, "maxDepth", sourceName);
            requirePositiveAtMost(maxPages, 50_000, "maxPages", sourceName);
            requirePositiveAtMost(timeoutSeconds, 300, "timeoutSeconds", sourceName);
            if (requestDelayMs != null && (requestDelayMs < 0 || requestDelayMs > 60_000)) {
                throw new IllegalArgumentException("requestDelayMs of ingestion source '" + sourceName
                        + "' must be between 0 and 60000, got: " + requestDelayMs);
            }
        }

        /**
         * Refuses a start URL whose host is a literal address the fetcher will always
         * reject — loopback, private, link-local, or the cloud metadata endpoint.
         * Saving one produces a source that fails on every run with an error the
         * operator only sees in the run history.
         *
         * <p>
         * Literals only, deliberately. Resolving a hostname here would make saving a
         * knowledge base depend on DNS and would reject a perfectly good configuration
         * during an outage; the real check runs per request in {@code OutboundHttp},
         * where it belongs.
         */
        private static void requireRoutableHost(String url, String field, String sourceName) {
            String host;
            try {
                host = URI.create(url).getHost();
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(field + " of ingestion source '" + sourceName
                        + "' is not a valid URL: " + url);
            }
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException(
                        field + " of ingestion source '" + sourceName + "' has no host: " + url);
            }
            boolean literal = host.chars().allMatch(c -> c == '.' || (c >= '0' && c <= '9'))
                    || host.startsWith("[") || host.contains(":");
            if (!literal && !"localhost".equalsIgnoreCase(host)) {
                return;
            }
            try {
                InetAddress address = InetAddress.getByName(host.replace("[", "").replace("]", ""));
                if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                        || address.isAnyLocalAddress()) {
                    throw new IllegalArgumentException(field + " of ingestion source '" + sourceName
                            + "' points at a local or private address (" + host + "), which the crawler refuses "
                            + "on every run");
                }
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException(
                        field + " of ingestion source '" + sourceName + "' has an unusable host: " + host);
            }
        }

        private static void requirePositiveAtMost(Integer value, int ceiling, String field, String sourceName) {
            // Null is "unset" and gets the default; a value the operator actually typed
            // is held to the limits.
            if (value != null && (value <= 0 || value > ceiling)) {
                throw new IllegalArgumentException(field + " of ingestion source '" + sourceName
                        + "' must be between 1 and " + ceiling + ", got: " + value);
            }
        }

        public String getStartUrl() {
            return startUrl;
        }

        public void setStartUrl(String startUrl) {
            this.startUrl = startUrl;
        }

        public boolean isSameSiteOnly() {
            return sameSiteOnly;
        }

        public void setSameSiteOnly(boolean sameSiteOnly) {
            this.sameSiteOnly = sameSiteOnly;
        }

        public boolean isIncludeSubdomains() {
            return includeSubdomains;
        }

        public void setIncludeSubdomains(boolean includeSubdomains) {
            this.includeSubdomains = includeSubdomains;
        }

        public String getPathPrefix() {
            return pathPrefix;
        }

        public void setPathPrefix(String pathPrefix) {
            this.pathPrefix = pathPrefix;
        }

        public Integer getMaxDepth() {
            return maxDepth;
        }

        public void setMaxDepth(Integer maxDepth) {
            this.maxDepth = maxDepth;
        }

        public Integer getMaxPages() {
            return maxPages;
        }

        public void setMaxPages(Integer maxPages) {
            this.maxPages = maxPages;
        }

        public List<String> getExcludePatterns() {
            return excludePatterns;
        }

        public void setExcludePatterns(List<String> excludePatterns) {
            this.excludePatterns = excludePatterns == null ? new ArrayList<>() : excludePatterns;
        }

        public List<String> getSitemapUrls() {
            return sitemapUrls;
        }

        public void setSitemapUrls(List<String> sitemapUrls) {
            this.sitemapUrls = sitemapUrls == null ? new ArrayList<>() : sitemapUrls;
        }

        public Integer getRequestDelayMs() {
            return requestDelayMs;
        }

        public void setRequestDelayMs(Integer requestDelayMs) {
            this.requestDelayMs = requestDelayMs;
        }

        public Integer getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(Integer timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public String getUserAgent() {
            return userAgent;
        }

        public void setUserAgent(String userAgent) {
            this.userAgent = userAgent;
        }

        public boolean isRespectRobots() {
            return respectRobots;
        }

        public void setRespectRobots(boolean respectRobots) {
            this.respectRobots = respectRobots;
        }
    }

    /**
     * How much may be uploaded to a {@link #TYPE_UPLOAD} source.
     *
     * <p>
     * These are storage limits, not ingestion limits: they bound what EDDI keeps on
     * the operator's behalf. What is done with the text afterwards is bounded by
     * {@link IngestionSettings} exactly as it is for a crawl.
     */
    public static class UploadSource {

        /**
         * The largest {@code maxFileBytes} that can be saved, held below
         * {@code quarkus.http.limits.max-body-size} (60 MB) so that a file at the limit
         * still reaches the code that knows what the limit is.
         */
        private static final long MAX_FILE_BYTES_CEILING = 50L * 1024 * 1024;

        /** Files this source may hold. */
        private Integer maxFiles = 500;

        /**
         * Bytes a single file may be. Twenty-five megabytes covers a long PDF with
         * images and stops an operator filling the database from a browser tab.
         *
         * <p>
         * The ceiling below is not arbitrary: the request carrying the file has to fit
         * inside {@code quarkus.http.limits.max-body-size}, and a file over that is
         * refused by the server with a bare 413 before anything here can explain why.
         * Raise the two together or not at all.
         */
        private Long maxFileBytes = 25L * 1024 * 1024;

        /** Bytes this source may hold across all of its files. */
        private Long maxTotalBytes = 500L * 1024 * 1024;

        void validate(String sourceName) {
            requirePositiveAtMost(maxFiles, 10_000, "upload.maxFiles", sourceName);
            requirePositiveAtMost(maxFileBytes, MAX_FILE_BYTES_CEILING, "upload.maxFileBytes", sourceName);
            requirePositiveAtMost(maxTotalBytes, 20L * 1024 * 1024 * 1024, "upload.maxTotalBytes", sourceName);
            if (maxFileBytes != null && maxTotalBytes != null && maxFileBytes > maxTotalBytes) {
                // Otherwise every upload is refused: the first file is under its own
                // limit and over the source's, with two error messages that each look
                // wrong on their own.
                throw new IllegalArgumentException("upload.maxFileBytes of ingestion source '" + sourceName
                        + "' is larger than upload.maxTotalBytes, so no file could ever be stored");
            }
        }

        public int maxFilesOrDefault() {
            return maxFiles == null ? 500 : maxFiles;
        }

        public long maxFileBytesOrDefault() {
            return maxFileBytes == null ? 25L * 1024 * 1024 : maxFileBytes;
        }

        public long maxTotalBytesOrDefault() {
            return maxTotalBytes == null ? 500L * 1024 * 1024 : maxTotalBytes;
        }

        private static void requirePositiveAtMost(Number value, long ceiling, String field, String sourceName) {
            if (value != null && (value.longValue() <= 0 || value.longValue() > ceiling)) {
                throw new IllegalArgumentException(field + " of ingestion source '" + sourceName
                        + "' must be between 1 and " + ceiling + ", got: " + value);
            }
        }

        public Integer getMaxFiles() {
            return maxFiles;
        }

        public void setMaxFiles(Integer maxFiles) {
            this.maxFiles = maxFiles;
        }

        public Long getMaxFileBytes() {
            return maxFileBytes;
        }

        public void setMaxFileBytes(Long maxFileBytes) {
            this.maxFileBytes = maxFileBytes;
        }

        public Long getMaxTotalBytes() {
            return maxTotalBytes;
        }

        public void setMaxTotalBytes(Long maxTotalBytes) {
            this.maxTotalBytes = maxTotalBytes;
        }
    }

    /**
     * A Google <b>Shared Drive</b>, or folders within one, read with a
     * service-account connection.
     *
     * <p>
     * The scope is always a Shared Drive, narrowed to folders when
     * {@link #folderIds} is set. There is no "My Drive" option: a service account
     * sees only what it was added to, and Shared Drive membership is the one grant
     * an operator can give it without a Workspace administrator.
     *
     * <pre>
     * "googleDrive": {
     *   "connection": "google-drive-sa",
     *   "sharedDriveId": "0AB…",
     *   "folderIds": ["1xY…"],
     *   "includeSubfolders": true,
     *   "mimeTypes": ["google-docs", "google-sheets", "pdf"],
     *   "excludeNamePatterns": ["*DRAFT*"]
     * }
     * </pre>
     */
    public static class GoogleDriveSource {

        /**
         * The formats {@link #mimeTypes} may name, in the order they are documented.
         */
        public static final List<String> FORMATS = List.of("google-docs", "google-sheets", "google-slides", "pdf",
                "docx", "xlsx", "pptx", "text", "markdown", "csv", "html");

        /**
         * The connection-name rule of the connections framework. Checked here too
         * because the name is spliced into a {@code ${connection:…}} reference, and the
         * {@code tenant/name} form would reach across tenants.
         */
        private static final Pattern CONNECTION_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

        /**
         * What a Drive id looks like. Ids are spliced into the listing query
         * ({@code 'id' in parents}), so anything else — a quote above all — is refused
         * rather than escaped.
         */
        public static final Pattern DRIVE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,200}$");

        static final int MAX_FOLDERS = 50;
        static final int MAX_EXCLUDE_PATTERNS = 50;
        static final int DEFAULT_MAX_FILES = 2_000;
        static final long DEFAULT_MAX_FILE_BYTES = 25L * 1024 * 1024;
        private static final long MAX_FILE_BYTES_CEILING = 50L * 1024 * 1024;

        /**
         * Name of a {@code SERVICE}-bound connection, usually {@code OAUTH2_JWT_BEARER}
         * holding a Google service-account key.
         */
        private String connection;

        /** The Shared Drive to read. Required. */
        private String sharedDriveId;

        /**
         * Folders of that drive to read; empty means the whole drive. Each must be a
         * folder of {@link #sharedDriveId}.
         */
        private List<String> folderIds = new ArrayList<>();

        /** Read the folders' subfolders too, breadth-first. */
        private boolean includeSubfolders = true;

        /**
         * Formats to read, from {@link #FORMATS}; empty or absent means all of them.
         */
        private List<String> mimeTypes = new ArrayList<>();

        /** File-name globs to skip ({@code *DRAFT*}, {@code ~$*}). */
        private List<String> excludeNamePatterns = new ArrayList<>();

        /** Files one run reads at most. A drive larger than this is read in part. */
        private Integer maxFiles;

        /**
         * Bytes one file may be. A stored file over it is skipped without being
         * downloaded, and an export over it is skipped when it arrives.
         */
        private Long maxFileBytes;

        /**
         * Reserved for per-document access control ({@code DOCUMENT_ACL}), which is not
         * built yet. {@code true} is refused rather than ignored: an operator who asks
         * for it believes retrieval respects each file's sharing, and it does not.
         */
        private Boolean captureAcl;

        void validate(String sourceName) {
            String prefix = "googleDrive";
            if (connection == null || connection.isBlank()) {
                throw new IllegalArgumentException(prefix + ".connection of ingestion source '" + sourceName
                        + "' is required: name a SERVICE-bound connection (OAUTH2_JWT_BEARER with a Google service "
                        + "account)");
            }
            if (!CONNECTION_NAME.matcher(connection).matches()) {
                throw new IllegalArgumentException(prefix + ".connection of ingestion source '" + sourceName
                        + "' is not a connection name (letters, digits, '.', '_' and '-', at most 64): " + connection);
            }
            if (sharedDriveId == null || sharedDriveId.isBlank()) {
                throw new IllegalArgumentException(prefix + ".sharedDriveId of ingestion source '" + sourceName
                        + "' is required: the id of the Shared Drive, from its URL "
                        + "(drive.google.com/drive/folders/<id>)");
            }
            requireDriveId(sharedDriveId, prefix + ".sharedDriveId", sourceName);
            if (folderIds.size() > MAX_FOLDERS) {
                throw new IllegalArgumentException(prefix + ".folderIds of ingestion source '" + sourceName
                        + "' may list at most " + MAX_FOLDERS + " folders, got: " + folderIds.size());
            }
            for (String folderId : folderIds) {
                requireDriveId(folderId, prefix + ".folderIds", sourceName);
            }
            for (String format : mimeTypes) {
                if (format == null || !FORMATS.contains(format)) {
                    throw new IllegalArgumentException(prefix + ".mimeTypes of ingestion source '" + sourceName
                            + "' contains '" + format + "'. Accepted: " + String.join(", ", FORMATS));
                }
            }
            if (excludeNamePatterns.size() > MAX_EXCLUDE_PATTERNS) {
                throw new IllegalArgumentException(prefix + ".excludeNamePatterns of ingestion source '" + sourceName
                        + "' may list at most " + MAX_EXCLUDE_PATTERNS + " patterns, got: "
                        + excludeNamePatterns.size());
            }
            for (String pattern : excludeNamePatterns) {
                if (pattern == null || pattern.isBlank() || pattern.length() > 512) {
                    throw new IllegalArgumentException(prefix + ".excludeNamePatterns of ingestion source '"
                            + sourceName + "' contains an empty pattern or one longer than 512 characters");
                }
            }
            if (maxFiles != null && (maxFiles <= 0 || maxFiles > 50_000)) {
                throw new IllegalArgumentException(prefix + ".maxFiles of ingestion source '" + sourceName
                        + "' must be between 1 and 50000, got: " + maxFiles);
            }
            if (maxFileBytes != null && (maxFileBytes <= 0 || maxFileBytes > MAX_FILE_BYTES_CEILING)) {
                throw new IllegalArgumentException(prefix + ".maxFileBytes of ingestion source '" + sourceName
                        + "' must be between 1 and " + MAX_FILE_BYTES_CEILING + ", got: " + maxFileBytes);
            }
            if (Boolean.TRUE.equals(captureAcl)) {
                throw new IllegalArgumentException(prefix + ".captureAcl of ingestion source '" + sourceName
                        + "' is not supported yet: per-document access control (DOCUMENT_ACL) is a later phase, and "
                        + "until it exists every conversation of an agent using this knowledge base can retrieve "
                        + "what this source ingests, whatever each file's sharing says. Remove captureAcl or set it "
                        + "to false.");
            }
        }

        private static void requireDriveId(String id, String field, String sourceName) {
            if (id == null || !DRIVE_ID.matcher(id).matches()) {
                throw new IllegalArgumentException(field + " of ingestion source '" + sourceName
                        + "' is not a Drive id (letters, digits, '-' and '_'): " + id);
            }
        }

        /** The folders to read from: the listed ones, or the drive's root. */
        public List<String> rootFolderIds() {
            return folderIds.isEmpty() ? List.of(sharedDriveId) : List.copyOf(new LinkedHashSet<>(folderIds));
        }

        /** The formats to read; never empty. */
        public Set<String> formatsOrDefault() {
            return mimeTypes.isEmpty() ? new LinkedHashSet<>(FORMATS) : new LinkedHashSet<>(mimeTypes);
        }

        public int maxFilesOrDefault() {
            return maxFiles == null ? DEFAULT_MAX_FILES : maxFiles;
        }

        public long maxFileBytesOrDefault() {
            return maxFileBytes == null ? DEFAULT_MAX_FILE_BYTES : maxFileBytes;
        }

        public String getConnection() {
            return connection;
        }

        public void setConnection(String connection) {
            this.connection = connection;
        }

        public String getSharedDriveId() {
            return sharedDriveId;
        }

        public void setSharedDriveId(String sharedDriveId) {
            this.sharedDriveId = sharedDriveId;
        }

        public List<String> getFolderIds() {
            return folderIds;
        }

        public void setFolderIds(List<String> folderIds) {
            this.folderIds = folderIds == null ? new ArrayList<>() : folderIds;
        }

        public boolean isIncludeSubfolders() {
            return includeSubfolders;
        }

        public void setIncludeSubfolders(boolean includeSubfolders) {
            this.includeSubfolders = includeSubfolders;
        }

        public List<String> getMimeTypes() {
            return mimeTypes;
        }

        public void setMimeTypes(List<String> mimeTypes) {
            this.mimeTypes = mimeTypes == null ? new ArrayList<>() : mimeTypes;
        }

        public List<String> getExcludeNamePatterns() {
            return excludeNamePatterns;
        }

        public void setExcludeNamePatterns(List<String> excludeNamePatterns) {
            this.excludeNamePatterns = excludeNamePatterns == null ? new ArrayList<>() : excludeNamePatterns;
        }

        public Integer getMaxFiles() {
            return maxFiles;
        }

        public void setMaxFiles(Integer maxFiles) {
            this.maxFiles = maxFiles;
        }

        public Long getMaxFileBytes() {
            return maxFileBytes;
        }

        public void setMaxFileBytes(Long maxFileBytes) {
            this.maxFileBytes = maxFileBytes;
        }

        public Boolean getCaptureAcl() {
            return captureAcl;
        }

        public void setCaptureAcl(Boolean captureAcl) {
            this.captureAcl = captureAcl;
        }
    }

    /** Limits that apply to the ingestion itself rather than to the crawl. */
    public static class IngestionSettings {

        /** Characters kept per document after conversion to Markdown. */
        private Integer maxContentLength = 100_000;

        /**
         * How many consecutive completed runs may miss a document before it is
         * considered gone and its vectors removed. Two, not one: sites go down and
         * crawls hit their caps, and a hair trigger empties a knowledge base because a
         * site was briefly unreachable.
         */
        private Integer tombstoneAfterMissedRuns = 2;

        /**
         * Hard ceiling on embedded segments per run — the cost control. Segments rather
         * than dollars because it needs no pricing table to be exact; set
         * {@link #costPerThousandSegments} to have runs report a dollar figure too.
         */
        private Integer maxSegmentsPerRun = 20_000;

        /** Optional rate used to report a run's cost. */
        private Double costPerThousandSegments;

        /** Cap on a single response body. */
        private Long maxBytesPerPage = 5L * 1024 * 1024;

        /** Wall-clock ceiling for one run. */
        private Integer timeBudgetMinutes = 10;

        void validate(String sourceName) {
            if (maxContentLength != null && maxContentLength <= 0) {
                throw new IllegalArgumentException(
                        "maxContentLength of ingestion source '" + sourceName + "' must be positive");
            }
            if (tombstoneAfterMissedRuns != null && tombstoneAfterMissedRuns < 1) {
                throw new IllegalArgumentException("tombstoneAfterMissedRuns of ingestion source '" + sourceName
                        + "' must be at least 1 — a document missing from a single run is not a deleted document");
            }
            if (maxSegmentsPerRun != null && maxSegmentsPerRun <= 0) {
                throw new IllegalArgumentException(
                        "maxSegmentsPerRun of ingestion source '" + sourceName + "' must be positive");
            }
            if (timeBudgetMinutes != null && (timeBudgetMinutes <= 0 || timeBudgetMinutes > 24 * 60)) {
                throw new IllegalArgumentException("timeBudgetMinutes of ingestion source '" + sourceName
                        + "' must be between 1 and 1440");
            }
            if (costPerThousandSegments != null && costPerThousandSegments < 0) {
                throw new IllegalArgumentException(
                        "costPerThousandSegments of ingestion source '" + sourceName + "' must not be negative");
            }
            if (maxBytesPerPage != null && maxBytesPerPage <= 0) {
                // The fetcher reads a non-positive cap as "no cap", so letting one
                // through would turn a typo into unbounded downloads.
                throw new IllegalArgumentException(
                        "maxBytesPerPage of ingestion source '" + sourceName + "' must be positive");
            }
        }

        public int maxContentLengthOrDefault() {
            return maxContentLength == null ? 100_000 : maxContentLength;
        }

        public int tombstoneAfterMissedRunsOrDefault() {
            return tombstoneAfterMissedRuns == null ? 2 : tombstoneAfterMissedRuns;
        }

        public int maxSegmentsPerRunOrDefault() {
            return maxSegmentsPerRun == null ? 20_000 : maxSegmentsPerRun;
        }

        public long maxBytesPerPageOrDefault() {
            return maxBytesPerPage == null ? 5L * 1024 * 1024 : maxBytesPerPage;
        }

        public int timeBudgetMinutesOrDefault() {
            return timeBudgetMinutes == null ? 10 : timeBudgetMinutes;
        }

        public Integer getMaxContentLength() {
            return maxContentLength;
        }

        public void setMaxContentLength(Integer maxContentLength) {
            this.maxContentLength = maxContentLength;
        }

        public Integer getTombstoneAfterMissedRuns() {
            return tombstoneAfterMissedRuns;
        }

        public void setTombstoneAfterMissedRuns(Integer tombstoneAfterMissedRuns) {
            this.tombstoneAfterMissedRuns = tombstoneAfterMissedRuns;
        }

        public Integer getMaxSegmentsPerRun() {
            return maxSegmentsPerRun;
        }

        public void setMaxSegmentsPerRun(Integer maxSegmentsPerRun) {
            this.maxSegmentsPerRun = maxSegmentsPerRun;
        }

        public Double getCostPerThousandSegments() {
            return costPerThousandSegments;
        }

        public void setCostPerThousandSegments(Double costPerThousandSegments) {
            this.costPerThousandSegments = costPerThousandSegments;
        }

        public Long getMaxBytesPerPage() {
            return maxBytesPerPage;
        }

        public void setMaxBytesPerPage(Long maxBytesPerPage) {
            this.maxBytesPerPage = maxBytesPerPage;
        }

        public Integer getTimeBudgetMinutes() {
            return timeBudgetMinutes;
        }

        public void setTimeBudgetMinutes(Integer timeBudgetMinutes) {
            this.timeBudgetMinutes = timeBudgetMinutes;
        }
    }
}
