/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.drive;

import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.IngestionSource.GoogleDriveSource;
import ai.labs.eddi.modules.ingestion.connector.Coverage;
import ai.labs.eddi.modules.ingestion.connector.DiscoveryContext;
import ai.labs.eddi.modules.ingestion.connector.DiscoveryResult;
import ai.labs.eddi.modules.ingestion.connector.KnownVersion;
import ai.labs.eddi.modules.ingestion.connector.Partition;
import ai.labs.eddi.modules.ingestion.connector.SourceConnector;
import ai.labs.eddi.modules.ingestion.connector.SourceContext;
import ai.labs.eddi.modules.ingestion.connector.SourceDocument;
import ai.labs.eddi.modules.ingestion.connector.SourceDocument.Content;
import ai.labs.eddi.modules.ingestion.connector.SourceDocument.ResponseValidators;
import ai.labs.eddi.modules.ingestion.connector.SourceError;
import ai.labs.eddi.modules.ingestion.connector.SourceException;
import ai.labs.eddi.modules.ingestion.connector.SourceMetadataKeys;
import ai.labs.eddi.modules.ingestion.connector.StopReason;
import ai.labs.eddi.modules.ingestion.connector.WorkItem;
import ai.labs.eddi.modules.ingestion.drive.DriveApi.DriveApiException;
import ai.labs.eddi.modules.ingestion.drive.DriveApi.DriveFile;
import ai.labs.eddi.modules.ingestion.drive.DriveApi.Page;
import ai.labs.eddi.modules.ingestion.drive.DriveTransport.DriveResponse;
import ai.labs.eddi.modules.ingestion.extract.DocumentExtractors;
import ai.labs.eddi.modules.ingestion.extract.UnreadableDocumentException;
import ai.labs.eddi.utils.LogSanitizer;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code googleDrive} source: a Google Shared Drive, or folders of one,
 * read with a {@code SERVICE}-bound connection (a service account).
 *
 * <h2>Discovery</h2>
 * <p>
 * Style C of {@link SourceConnector} — listing only. The framework does not
 * store cursors yet, so the Drive change feed ({@code changes.list}) is not
 * used: every run lists the scope in full, breadth-first from the configured
 * folders (or the drive's root), and the framework's version comparison skips
 * what has not changed. A run over an unchanged drive therefore costs its
 * listing requests and nothing else — no download, no export, no embedding.
 * Moving to the change feed is a matter of returning a cursor once the
 * framework keeps one.
 *
 * <p>
 * Every listing is scoped to the Shared Drive ({@code corpora=drive}), so a
 * shortcut to a folder elsewhere lists nothing and cannot widen the source.
 * Shortcuts to folders inside it are followed, with a cycle guard; shortcuts to
 * files are skipped, since the file they point at is either listed in its own
 * folder or outside the scope.
 *
 * <h2>When the drive looks empty</h2>
 * <p>
 * A service account removed from a Shared Drive, or a folder moved out of it,
 * can produce an empty listing rather than an error. Believed, a complete
 * listing that finds nothing deletes the whole knowledge base. So a root whose
 * listing comes back empty has its access checked again explicitly
 * ({@code drives.get}, {@code files.get}) before the listing counts as
 * coverage, and the run fails if access is gone.
 *
 * <h2>Change detection</h2>
 * <ul>
 * <li>A stored file's version is its {@code md5Checksum} (its
 * {@code headRevisionId} where Drive has no checksum), known from the listing:
 * an unchanged file is not downloaded.</li>
 * <li>A Google Doc, Sheet or Slides file has no checksum. Its {@code version}
 * is kept as the document's validator: an equal version is reported unchanged
 * without exporting. A different version is exported, and the hash of the
 * export decides whether to embed — {@code version} also moves on renames and
 * sharing changes, which must not re-embed a document.</li>
 * </ul>
 */
@ApplicationScoped
public class GoogleDriveSourceConnector implements SourceConnector {

    private static final Logger LOGGER = Logger.getLogger(GoogleDriveSourceConnector.class);

    static final String HINT_NAME = "name";
    static final String HINT_MIME = "mimeType";
    static final String HINT_PATH = "path";
    static final String HINT_LINK = "webViewLink";
    static final String HINT_MODIFIED = "modifiedTime";
    static final String HINT_AUTHOR = "author";
    static final String HINT_VERSION = "version";

    /** How long a fetch may spend waiting on Drive's rate limit. */
    private static final Duration FETCH_RETRY_WINDOW = Duration.ofMinutes(2);

    /** A Markdown image reference definition holding a data URI. */
    private static final Pattern DATA_URI_DEFINITION = Pattern.compile(
            "^\\s{0,3}\\[[^\\]\\n]{1,200}\\]:\\s{0,10}<?data:", Pattern.CASE_INSENSITIVE);

    /** An image that points at a reference ({@code ![][image1]}). */
    private static final Pattern REFERENCED_IMAGE = Pattern.compile("!\\[[^\\]\\n]{0,200}\\]\\[[^\\]\\n]{1,200}\\]");

    private final DriveTransport transport;
    private final DocumentExtractors extractors;

    /** Replaced in tests, so a retry does not really wait. */
    DriveApi.Sleeper sleeper = DriveApi.THREAD_SLEEPER;

    /** Replaced in tests. */
    Supplier<Instant> clock = Instant::now;

    @Inject
    public GoogleDriveSourceConnector(DriveTransport transport, DocumentExtractors extractors) {
        this.transport = transport;
        this.extractors = extractors;
    }

    @Override
    public String type() {
        return IngestionSource.TYPE_GOOGLE_DRIVE;
    }

    @Override
    public List<Partition> listPartitions(SourceContext context) {
        return List.of(new Partition(drive(context).getSharedDriveId(), null));
    }

    @Override
    public void verifyAccess(SourceContext context, Partition partition) throws SourceException {
        GoogleDriveSource drive = drive(context);
        DriveApi api = api(context, clock.get().plus(FETCH_RETRY_WINDOW));
        requireDrive(api, drive);
        for (String folderId : drive.getFolderIds()) {
            requireFolder(api, drive, folderId);
        }
    }

    @Override
    public DiscoveryResult discover(DiscoveryContext context) throws SourceException {
        SourceContext source = context.source();
        GoogleDriveSource drive = drive(source);
        DriveApi api = api(source, context.deadline());
        Listing listing = new Listing(context, drive);

        Deque<Folder> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        for (String root : drive.rootFolderIds()) {
            if (visited.add(root)) {
                queue.add(new Folder(root, "", true));
            }
        }

        StopReason stopReason = StopReason.COMPLETED;
        boolean complete = true;
        String incompleteReason = null;
        Map<String, Integer> rootChildren = new LinkedHashMap<>();

        outer : while (!queue.isEmpty()) {
            Folder folder = queue.poll();
            String pageToken = null;
            do {
                if (context.isCancelled()) {
                    stopReason = StopReason.CANCELLED;
                    break outer;
                }
                if (clock.get().isAfter(context.deadline())) {
                    stopReason = StopReason.TIME_LIMIT;
                    break outer;
                }
                Page page;
                try {
                    page = api.listChildren(drive.getSharedDriveId(), folder.id(), pageToken);
                } catch (SourceException e) {
                    SourceError error = e.error();
                    if (error.kind() == SourceError.Kind.AUTH) {
                        // The credential cannot look at all: fail the run so the operator
                        // sees it, rather than report a partial listing every night.
                        throw e;
                    }
                    context.reportFailure(null, folder.label(), error);
                    complete = false;
                    incompleteReason = "a folder of the drive could not be listed: " + error.reason();
                    if (error.kind() == SourceError.Kind.THROTTLED) {
                        // Drive is still saying "wait" after the retries; every further
                        // request would hear the same.
                        stopReason = StopReason.CANCELLED;
                        break outer;
                    }
                    break;
                }
                if (page.incompleteSearch()) {
                    complete = false;
                    incompleteReason = "Google Drive reported an incomplete listing";
                }
                if (folder.root()) {
                    rootChildren.merge(folder.id(), page.files().size(), Integer::sum);
                }
                for (DriveFile file : page.files()) {
                    if (!listing.add(file, folder, queue, visited)) {
                        stopReason = StopReason.PAGE_LIMIT;
                        break outer;
                    }
                }
                pageToken = page.nextPageToken();
            } while (pageToken != null);
        }

        if (stopReason == StopReason.COMPLETED && complete) {
            // An empty root is the one listing that could be an outage dressed as
            // coverage: a removed member or a moved folder lists nothing rather than
            // failing. Confirmed before it is believed.
            for (String root : drive.rootFolderIds()) {
                if (rootChildren.getOrDefault(root, 0) == 0) {
                    if (root.equals(drive.getSharedDriveId())) {
                        requireDrive(api, drive);
                    } else {
                        requireFolder(api, drive, root);
                    }
                }
            }
        }

        Coverage coverage = stopReason == StopReason.COMPLETED && complete ? Coverage.FULL_LISTING : Coverage.PARTIAL;
        return new DiscoveryResult(stopReason, coverage, coverage == Coverage.PARTIAL ? incompleteReason : null, null,
                listing.summary(visited.size(), api.requests()));
    }

    @Override
    public SourceDocument fetch(WorkItem item, SourceContext context) throws SourceException {
        GoogleDriveSource drive = drive(context);
        Map<String, String> hints = item.hints();
        String name = hints.getOrDefault(HINT_NAME, item.documentId());
        String driveMime = hints.get(HINT_MIME);
        DriveFormat format = DriveFormat.of(driveMime);
        if (format == null) {
            throw new SourceException(new SourceError.Permanent("Files of type " + driveMime + " are not read"));
        }
        Map<String, Object> metadata = metadata(item, drive, format, name, driveMime);
        String version = hints.get(HINT_VERSION);
        ResponseValidators validators = format.isNative() && version != null
                ? new ResponseValidators(version, null)
                : null;
        String versionToken = format.isNative() ? null : item.versionToken();

        if (context.preview()) {
            // A preview reports what a run would do from the listing alone. Exporting
            // and downloading every new file to show a count would make the dry run
            // cost what the real one does.
            return new SourceDocument(item.documentId(), new Content.Text("Preview of " + name), versionToken,
                    validators, false, metadata);
        }

        DriveApi api = api(context, clock.get().plus(FETCH_RETRY_WINDOW));
        long maxBytes = drive.maxFileBytesOrDefault();
        DriveResponse response;
        try {
            response = format.isNative()
                    ? export(api, item.documentId(), format, maxBytes)
                    : api.download(item.documentId(), maxBytes);
        } catch (DriveApiException e) {
            if (e.hasReason(DriveApi.EXPORT_TOO_LARGE)) {
                return skipped(item, context, name, "it is larger than Google's export limit", metadata);
            }
            throw e;
        }
        if (response.tooLarge()) {
            return skipped(item, context, name, "it is larger than googleDrive.maxFileBytes (" + maxBytes + " bytes)",
                    metadata);
        }

        Content content = switch (format) {
            case GOOGLE_DOCS -> googleDoc(response);
            case GOOGLE_SHEETS -> new Content.FileBytes(response.body(), DriveFormat.XLSX_MIME);
            case GOOGLE_SLIDES -> new Content.FileBytes(response.body(), "text/plain");
            default -> storedFile(response.body(), name, format);
        };
        return new SourceDocument(item.documentId(), content, versionToken, validators, false, metadata);
    }

    /**
     * A Google Doc as Markdown, or as HTML from a Drive that does not offer the
     * Markdown export.
     */
    private static DriveResponse export(DriveApi api, String fileId, DriveFormat format, long maxBytes)
            throws SourceException {
        if (format != DriveFormat.GOOGLE_DOCS) {
            return api.export(fileId, format.exportMime(), maxBytes);
        }
        try {
            DriveResponse markdown = api.export(fileId, "text/markdown", maxBytes);
            return markdown.tooLarge() ? markdown : withMime(markdown, "text/markdown");
        } catch (DriveApiException e) {
            if (e.error().kind() != SourceError.Kind.REJECTED) {
                throw e;
            }
            return withMime(api.export(fileId, "text/html", maxBytes), "text/html");
        }
    }

    private static DriveResponse withMime(DriveResponse response, String mime) {
        Map<String, List<String>> headers = new HashMap<>(response.headers());
        headers.put("X-Eddi-Export", List.of(mime));
        return new DriveResponse(response.status(), headers, response.body(), response.tooLarge());
    }

    private static Content googleDoc(DriveResponse response) {
        String mime = response.header("X-Eddi-Export").orElse("text/markdown");
        if ("text/html".equals(mime)) {
            return new Content.FileBytes(response.body(), "text/html");
        }
        String markdown = withoutEmbeddedImages(new String(response.body(), StandardCharsets.UTF_8));
        return new Content.FileBytes(markdown.getBytes(StandardCharsets.UTF_8), "text/markdown");
    }

    /**
     * Drops the images Google's Markdown export inlines as base64 data URIs. They
     * are most of a document's bytes, and embedded as text they are noise that
     * crowds the real content out of every chunk they touch.
     */
    static String withoutEmbeddedImages(String markdown) {
        StringBuilder kept = new StringBuilder(markdown.length());
        for (String line : markdown.split("\n", -1)) {
            if (DATA_URI_DEFINITION.matcher(line).find()) {
                continue;
            }
            kept.append(removeInlineDataImages(line)).append('\n');
        }
        if (!kept.isEmpty()) {
            kept.setLength(kept.length() - 1);
        }
        Matcher referenced = REFERENCED_IMAGE.matcher(kept);
        return referenced.replaceAll("");
    }

    /**
     * Removes {@code ![alt](data:…)} from one line, without a regex over the
     * payload.
     */
    private static String removeInlineDataImages(String line) {
        String current = line;
        int marker;
        while ((marker = current.toLowerCase(Locale.ROOT).indexOf("](data:")) >= 0) {
            int start = current.lastIndexOf("![", marker);
            int end = current.indexOf(')', marker);
            if (start < 0 || end < 0) {
                break;
            }
            current = current.substring(0, start) + current.substring(end + 1);
        }
        return current;
    }

    /**
     * A stored file, its type confirmed from its bytes: Drive's MIME type is what
     * the uploader's browser claimed, and a renamed file would otherwise reach the
     * wrong extractor and fail as damaged.
     */
    private Content storedFile(byte[] bytes, String name, DriveFormat format) throws SourceException {
        String fileName = name.toLowerCase(Locale.ROOT).endsWith(format.extension()) ? name : name + format.extension();
        try {
            return new Content.FileBytes(bytes, extractors.resolveMimeType(fileName, bytes));
        } catch (UnreadableDocumentException e) {
            throw new SourceException(new SourceError.Permanent(e.getMessage()), e);
        }
    }

    /**
     * A file this run will not ingest because of its size. Returned as empty text,
     * which the framework counts as skipped rather than failed — and, not being
     * recorded as seen, an older indexed version of it is reconciled away like any
     * other absence.
     */
    private static SourceDocument skipped(WorkItem item, SourceContext context, String name, String why,
                                          Map<String, Object> metadata) {
        LOGGER.infof("Skipped '%s' of source '%s': %s", LogSanitizer.sanitize(name),
                LogSanitizer.sanitize(context.source().getName()), why);
        return new SourceDocument(item.documentId(), new Content.Text(""), null, null, false, metadata);
    }

    private static Map<String, Object> metadata(WorkItem item, GoogleDriveSource drive, DriveFormat format,
                                                String name, String driveMime) {
        Map<String, String> hints = item.hints();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(SourceMetadataKeys.TITLE, name);
        String link = hints.get(HINT_LINK);
        // The provider's own page, opened under the reader's own permissions — never
        // an EDDI URL that would serve the content to whoever holds the link.
        metadata.put(SourceMetadataKeys.CITATION_URL,
                link != null ? link : "https://drive.google.com/open?id=" + item.documentId());
        metadata.put(SourceMetadataKeys.PATH, hints.get(HINT_PATH));
        metadata.put(SourceMetadataKeys.MIME_TYPE, driveMime);
        metadata.put(SourceMetadataKeys.FORMAT, format.formatClass());
        metadata.put(SourceMetadataKeys.AUTHOR, hints.get(HINT_AUTHOR));
        metadata.put(SourceMetadataKeys.MODIFIED_AT, hints.get(HINT_MODIFIED));
        metadata.put(SourceMetadataKeys.PARTITION_ID, drive.getSharedDriveId());
        metadata.put(SourceMetadataKeys.PROVIDER, IngestionSource.TYPE_GOOGLE_DRIVE);
        return metadata;
    }

    private static void requireDrive(DriveApi api, GoogleDriveSource drive) throws SourceException {
        try {
            api.getDrive(drive.getSharedDriveId());
        } catch (DriveApiException e) {
            if (e.error().kind() == SourceError.Kind.NOT_FOUND) {
                throw new SourceException(new SourceError.Auth("Shared Drive " + drive.getSharedDriveId()
                        + " is not visible to connection '" + drive.getConnection() + "': it does not exist, or the "
                        + "service account is not a member. Add the service account's email to the Shared Drive "
                        + "(Viewer is enough)."), e);
            }
            throw e;
        }
    }

    private static void requireFolder(DriveApi api, GoogleDriveSource drive, String folderId)
            throws SourceException {
        JsonNode folder;
        try {
            folder = api.getFile(folderId);
        } catch (DriveApiException e) {
            if (e.error().kind() == SourceError.Kind.NOT_FOUND) {
                throw new SourceException(new SourceError.Auth("Folder " + folderId + " is not visible to connection '"
                        + drive.getConnection() + "': it was deleted, or the service account lost access to it"), e);
            }
            throw e;
        }
        if (!DriveFormat.FOLDER_MIME.equals(folder.path("mimeType").asText())) {
            throw new SourceException(new SourceError.Rejected("googleDrive.folderIds lists " + folderId
                    + ", which is not a folder"));
        }
        if (!drive.getSharedDriveId().equals(folder.path("driveId").asText())) {
            throw new SourceException(new SourceError.Rejected("Folder " + folderId + " is not in Shared Drive "
                    + drive.getSharedDriveId()));
        }
        if (folder.path("trashed").asBoolean(false)) {
            throw new SourceException(new SourceError.Auth("Folder " + folderId + " is in the trash. Restore it, or "
                    + "remove it from googleDrive.folderIds."));
        }
    }

    private DriveApi api(SourceContext context, Instant deadline) {
        return new DriveApi(transport, context, deadline, sleeper, clock);
    }

    private static GoogleDriveSource drive(SourceContext context) {
        GoogleDriveSource drive = context.source().getGoogleDrive();
        if (drive == null) {
            // Validation refuses this before any run; reaching here is a defect.
            throw new IllegalStateException("Source '" + context.source().getName() + "' has no googleDrive block");
        }
        return drive;
    }

    /**
     * Whether a file name matches a glob, case-insensitively: {@code *} is any run
     * of characters, {@code ?} exactly one, everything else literal. A name is not
     * a path, so unlike the crawler's URL patterns {@code *} crosses a {@code /} a
     * Drive name may contain. Greedy with one backtrack point, so the work is
     * bounded by the two lengths whatever the pattern.
     *
     * @param glob
     *            already lower-cased
     */
    static boolean nameMatches(String glob, String name) {
        String text = name.toLowerCase(Locale.ROOT);
        int g = 0;
        int t = 0;
        int star = -1;
        int resume = 0;
        while (t < text.length()) {
            if (g < glob.length() && (glob.charAt(g) == '?' || glob.charAt(g) == text.charAt(t))) {
                g++;
                t++;
            } else if (g < glob.length() && glob.charAt(g) == '*') {
                star = g++;
                resume = t;
            } else if (star >= 0) {
                g = star + 1;
                t = ++resume;
            } else {
                return false;
            }
        }
        while (g < glob.length() && glob.charAt(g) == '*') {
            g++;
        }
        return g == glob.length();
    }

    /** A folder waiting to be listed, and the path to it from its root. */
    private record Folder(String id, String path, boolean root) {

        String label() {
            return path.isEmpty() ? "folder " + id : path;
        }

        String child(String name) {
            return path.isEmpty() ? name : path + "/" + name;
        }
    }

    /**
     * What one discovery has found so far: the files handed over, what was skipped
     * and why, and the counts the summary reports.
     */
    private static final class Listing {

        private final DiscoveryContext context;
        private final GoogleDriveSource drive;
        private final Set<String> formats;
        private final List<String> excluded;
        private final Partition partition;
        private final Set<String> seenFiles = new HashSet<>();
        private final Map<String, Integer> acceptedByFormat = new TreeMap<>();
        private final Map<String, Integer> unsupportedByMime = new TreeMap<>();
        private int accepted;
        private long acceptedBytes;
        private int sizedFiles;
        private int skippedFormat;
        private int skippedName;
        private int skippedSize;
        private int skippedShortcuts;
        private int repeatedFolders;

        Listing(DiscoveryContext context, GoogleDriveSource drive) {
            this.context = context;
            this.drive = drive;
            this.formats = drive.formatsOrDefault();
            this.excluded = drive.getExcludeNamePatterns().stream().map(p -> p.toLowerCase(Locale.ROOT)).toList();
            this.partition = context.partition();
        }

        /**
         * Takes one listed item.
         *
         * @return false when the file limit is reached and discovery must stop
         */
        boolean add(DriveFile file, Folder folder, Deque<Folder> queue, Set<String> visited) {
            if (file.id() == null || !GoogleDriveSource.DRIVE_ID.matcher(file.id()).matches()) {
                return true;
            }
            String name = file.name() == null ? file.id() : file.name();
            if (DriveFormat.FOLDER_MIME.equals(file.mimeType())) {
                if (drive.isIncludeSubfolders() && visited.add(file.id())) {
                    queue.add(new Folder(file.id(), folder.child(name), false));
                }
                return true;
            }
            if (DriveFormat.SHORTCUT_MIME.equals(file.mimeType())) {
                String target = file.shortcutTargetId();
                if (DriveFormat.FOLDER_MIME.equals(file.shortcutTargetMimeType())) {
                    if (!drive.isIncludeSubfolders() || target == null
                            || !GoogleDriveSource.DRIVE_ID.matcher(target).matches()) {
                        return true;
                    }
                    // A shortcut back to a folder already listed is how Drive makes a
                    // cycle; the visited set is what stops the listing going round it.
                    if (visited.add(target)) {
                        queue.add(new Folder(target, folder.child(name), false));
                    } else {
                        repeatedFolders++;
                    }
                    return true;
                }
                skippedShortcuts++;
                context.reportSkipped(file.id(), "a shortcut to a file");
                return true;
            }
            if (!seenFiles.add(file.id())) {
                return true;
            }
            DriveFormat format = DriveFormat.of(file.mimeType());
            if (format == null) {
                unsupportedByMime.merge(String.valueOf(file.mimeType()), 1, Integer::sum);
                context.reportSkipped(file.id(), "files of type " + file.mimeType() + " are not read");
                return true;
            }
            if (!formats.contains(format.key())) {
                skippedFormat++;
                context.reportSkipped(file.id(), "format " + format.key() + " is not in googleDrive.mimeTypes");
                return true;
            }
            if (excluded.stream().anyMatch(glob -> nameMatches(glob, name))) {
                skippedName++;
                context.reportSkipped(file.id(), "excluded by googleDrive.excludeNamePatterns");
                return true;
            }
            if (!format.isNative() && file.size() != null && file.size() > drive.maxFileBytesOrDefault()) {
                skippedSize++;
                context.reportSkipped(file.id(), "larger than googleDrive.maxFileBytes");
                return true;
            }
            if (accepted >= drive.maxFilesOrDefault()) {
                return false;
            }
            accepted++;
            acceptedByFormat.merge(format.key(), 1, Integer::sum);
            if (file.size() != null) {
                acceptedBytes += file.size();
                sizedFiles++;
            }
            Map<String, String> hints = hints(file, name, folder.child(name));
            if (format.isNative()) {
                String version = hints.get(HINT_VERSION);
                Optional<KnownVersion> known = context.knownVersion(file.id());
                if (version != null && known.isPresent() && version.equals(known.get().etag())) {
                    context.accept(WorkItem.unchanged(file.id(), partition));
                } else {
                    // No version token: the hash of the export decides, because the
                    // native version also moves on a rename or a sharing change.
                    context.accept(WorkItem.upsert(file.id(), null, partition, hints));
                }
            } else {
                context.accept(WorkItem.upsert(file.id(), storedVersion(file), partition, hints));
            }
            return true;
        }

        private static String storedVersion(DriveFile file) {
            if (file.md5Checksum() != null) {
                return "md5:" + file.md5Checksum();
            }
            return file.headRevisionId() != null ? "rev:" + file.headRevisionId() : null;
        }

        private static Map<String, String> hints(DriveFile file, String name, String path) {
            Map<String, String> hints = new HashMap<>();
            hints.put(HINT_NAME, name);
            hints.put(HINT_PATH, path);
            putIfPresent(hints, HINT_MIME, file.mimeType());
            putIfPresent(hints, HINT_LINK, file.webViewLink());
            putIfPresent(hints, HINT_MODIFIED, file.modifiedTime());
            putIfPresent(hints, HINT_AUTHOR, file.author());
            if (file.version() != null) {
                hints.put(HINT_VERSION, "v:" + file.version());
            }
            return hints;
        }

        private static void putIfPresent(Map<String, String> hints, String key, String value) {
            if (value != null) {
                hints.put(key, value);
            }
        }

        /** One line for a preview's message and a run's log. */
        String summary(int folders, int requests) {
            StringBuilder text = new StringBuilder("Google Drive listing: ").append(accepted).append(" file(s) to read");
            if (!acceptedByFormat.isEmpty()) {
                text.append(" (");
                appendCounts(text, acceptedByFormat);
                text.append(')');
            }
            text.append(", ").append(formatBytes(acceptedBytes)).append(" in ").append(sizedFiles)
                    .append(" stored file(s) — Google Docs, Sheets and Slides have no size before export");
            text.append("; ").append(folders).append(" folder(s), ").append(requests).append(" API request(s)");
            int unsupported = unsupportedByMime.values().stream().mapToInt(Integer::intValue).sum();
            int skipped = unsupported + skippedFormat + skippedName + skippedSize + skippedShortcuts;
            if (skipped > 0) {
                text.append(". Skipped ").append(skipped).append(':');
                if (unsupported > 0) {
                    text.append(' ').append(unsupported).append(" unsupported type (");
                    appendCounts(text, unsupportedByMime);
                    text.append(");");
                }
                appendSkip(text, skippedFormat, "not in mimeTypes");
                appendSkip(text, skippedName, "excluded by name");
                appendSkip(text, skippedSize, "over maxFileBytes");
                appendSkip(text, skippedShortcuts, "shortcut to a file");
                text.setLength(text.length() - 1);
            }
            if (repeatedFolders > 0) {
                text.append(". ").append(repeatedFolders).append(" shortcut(s) to an already listed folder not followed");
            }
            return text.toString();
        }

        private static void appendSkip(StringBuilder text, int count, String why) {
            if (count > 0) {
                text.append(' ').append(count).append(' ').append(why).append(';');
            }
        }

        private static void appendCounts(StringBuilder text, Map<String, Integer> counts) {
            int shown = 0;
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                if (shown == 10) {
                    text.append(", …");
                    break;
                }
                if (shown > 0) {
                    text.append(", ");
                }
                text.append(entry.getValue()).append(' ').append(entry.getKey());
                shown++;
            }
        }

        private static String formatBytes(long bytes) {
            if (bytes < 1024) {
                return bytes + " B";
            }
            if (bytes < 1024L * 1024) {
                return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
            }
            return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        }
    }
}
