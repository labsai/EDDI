/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.drive;

import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.IngestionSource.GoogleDriveSource;
import ai.labs.eddi.connections.ResolvedCredential;
import ai.labs.eddi.modules.ingestion.connector.Coverage;
import ai.labs.eddi.modules.ingestion.connector.DiscoveryContext;
import ai.labs.eddi.modules.ingestion.connector.DiscoveryResult;
import ai.labs.eddi.modules.ingestion.connector.KnownVersion;
import ai.labs.eddi.modules.ingestion.connector.Partition;
import ai.labs.eddi.modules.ingestion.connector.SourceContext;
import ai.labs.eddi.modules.ingestion.connector.SourceCredentials;
import ai.labs.eddi.modules.ingestion.connector.SourceDocument;
import ai.labs.eddi.modules.ingestion.connector.SourceDocument.Content;
import ai.labs.eddi.modules.ingestion.connector.SourceError;
import ai.labs.eddi.modules.ingestion.connector.SourceException;
import ai.labs.eddi.modules.ingestion.connector.SourceMetadataKeys;
import ai.labs.eddi.modules.ingestion.connector.StopReason;
import ai.labs.eddi.modules.ingestion.connector.WorkItem;
import ai.labs.eddi.modules.ingestion.extract.CsvTextExtractor;
import ai.labs.eddi.modules.ingestion.extract.DocumentExtractors;
import ai.labs.eddi.modules.ingestion.extract.PlainTextExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Drive connector against {@link FakeDrive}: no network, no credentials, no
 * containers.
 */
class GoogleDriveSourceConnectorTest {

    private static final String DRIVE = "0AdriveAAA";
    private static final ResolvedCredential TOKEN = new ResolvedCredential("Authorization", "Bearer fixture-token");

    private FakeDrive fake;
    private GoogleDriveSourceConnector connector;
    private List<Duration> waits;
    private GoogleDriveSource config;
    private IngestionSource source;
    private SourceCredentials credentials;

    @BeforeEach
    void setUp() {
        fake = new FakeDrive(DRIVE);
        connector = new GoogleDriveSourceConnector(fake,
                new DocumentExtractors(List.of(new PlainTextExtractor(), new CsvTextExtractor())));
        waits = new ArrayList<>();
        connector.sleeper = waits::add;
        config = new GoogleDriveSource();
        config.setConnection("google-drive-sa");
        config.setSharedDriveId(DRIVE);
        source = new IngestionSource();
        source.setName("handbook");
        source.setType(IngestionSource.TYPE_GOOGLE_DRIVE);
        source.setGoogleDrive(config);
        credentials = (name, url) -> TOKEN;
    }

    private SourceContext context(boolean preview) {
        return new SourceContext(source, "kb/handbook", preview ? "preview" : "run-1", preview, credentials);
    }

    private Recorder discover() throws SourceException {
        Recorder recorder = new Recorder(context(false));
        recorder.result = connector.discover(recorder);
        return recorder;
    }

    private SourceDocument fetch(String id, boolean preview) throws SourceException {
        Recorder recorder = new Recorder(context(false));
        recorder.result = connector.discover(recorder);
        WorkItem item = recorder.item(id);
        return connector.fetch(item, context(preview));
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("listing")
    class Listing {

        @Test
        @DisplayName("pages through a folder and asks Drive for a Shared Drive listing every time")
        void paging() throws SourceException {
            fake.pageSize = 2;
            for (int i = 1; i <= 5; i++) {
                fake.file("f" + i, "note-" + i + ".txt", "text/plain", DRIVE, "text " + i);
            }

            Recorder run = discover();

            assertEquals(List.of("f1", "f2", "f3", "f4", "f5"), run.acceptedIds());
            assertEquals(3, fake.countRequests("/drive/v3/files?"));
            assertEquals(StopReason.COMPLETED, run.result.stopReason());
            assertEquals(Coverage.FULL_LISTING, run.result.coverage());
            assertTrue(fake.requests.stream().filter(uri -> uri.getPath().equals("/drive/v3/files"))
                    .allMatch(uri -> uri.getRawQuery().contains("corpora=drive")
                            && uri.getRawQuery().contains("driveId=" + DRIVE)
                            && uri.getRawQuery().contains("supportsAllDrives=true")
                            && uri.getRawQuery().contains("includeItemsFromAllDrives=true")));
            assertTrue(fake.authorizations.stream().allMatch("Bearer fixture-token"::equals));
        }

        @Test
        @DisplayName("descends breadth-first into subfolders, carrying the folder path")
        void recursion() throws SourceException {
            fake.folder("fa", "Policies", DRIVE)
                    .folder("fb", "Travel", "fa")
                    .file("top", "readme.md", "text/markdown", DRIVE, "# top")
                    .file("deep", "rules.txt", "text/plain", "fb", "rules")
                    .file("mid", "leave.txt", "text/plain", "fa", "leave");

            Recorder run = discover();

            assertEquals(List.of("top", "mid", "deep"), run.acceptedIds());
            assertEquals("Policies/Travel/rules.txt", run.item("deep").hints().get(GoogleDriveSourceConnector.HINT_PATH));
        }

        @Test
        @DisplayName("does not descend when includeSubfolders is off")
        void noSubfolders() throws SourceException {
            config.setIncludeSubfolders(false);
            fake.folder("fa", "Policies", DRIVE).file("top", "a.txt", "text/plain", DRIVE, "a")
                    .file("mid", "b.txt", "text/plain", "fa", "b");

            assertEquals(List.of("top"), discover().acceptedIds());
        }

        @Test
        @DisplayName("a shortcut cycle is listed once and terminates; a folder shortcut is followed once")
        void shortcutCycle() throws SourceException {
            fake.folder("fa", "A", DRIVE)
                    .folder("fb", "B", "fa")
                    .shortcut("s1", "back to A", "fb", "fa", FakeDrive.FOLDER)
                    .shortcut("s2", "loop to root", "fa", DRIVE, FakeDrive.FOLDER)
                    .file("x", "x.txt", "text/plain", "fb", "x");

            Recorder run = discover();

            assertEquals(List.of("x"), run.acceptedIds());
            assertEquals(1, fake.countRequests("%27fa%27"));
            assertEquals(1, fake.countRequests("%27" + DRIVE + "%27"));
            assertTrue(run.result.summary().contains("not followed"), run.result.summary());
        }

        @Test
        @DisplayName("a shortcut to a file is skipped, not followed out of its folder")
        void fileShortcut() throws SourceException {
            fake.shortcut("s1", "elsewhere.pdf", DRIVE, "outside", "application/pdf");

            Recorder run = discover();

            assertTrue(run.accepted.isEmpty());
            assertEquals(List.of("s1"), run.skipped);
        }

        @Test
        @DisplayName("only the configured folders are read")
        void folderScope() throws SourceException {
            fake.folder("fa", "In", DRIVE).folder("fz", "Out", DRIVE)
                    .file("in", "in.txt", "text/plain", "fa", "in")
                    .file("out", "out.txt", "text/plain", "fz", "out");
            config.setFolderIds(List.of("fa"));

            assertEquals(List.of("in"), discover().acceptedIds());
            assertEquals(0, fake.countRequests("%27" + DRIVE + "%27"));
        }

        @Test
        @DisplayName("maxFiles stops the listing short, which is no coverage")
        void maxFiles() throws SourceException {
            config.setMaxFiles(2);
            for (int i = 1; i <= 3; i++) {
                fake.file("f" + i, i + ".txt", "text/plain", DRIVE, "t");
            }

            Recorder run = discover();

            assertEquals(2, run.accepted.size());
            assertEquals(StopReason.PAGE_LIMIT, run.result.stopReason());
            assertEquals(Coverage.PARTIAL, run.result.coverage());
        }

        @Test
        @DisplayName("a cancelled run stops before the next request")
        void cancelled() throws SourceException {
            fake.file("f1", "a.txt", "text/plain", DRIVE, "a");
            Recorder recorder = new Recorder(context(false));
            recorder.cancelled = true;

            DiscoveryResult result = connector.discover(recorder);

            assertEquals(StopReason.CANCELLED, result.stopReason());
            assertEquals(Coverage.PARTIAL, result.coverage());
            assertTrue(fake.requests.isEmpty());
        }
    }

    @Nested
    @DisplayName("skips")
    class Skips {

        @Test
        @DisplayName("unsupported types, filtered formats, excluded names and oversized files are skipped and counted")
        void skippedAndCounted() throws SourceException {
            config.setMimeTypes(List.of("text", "google-docs"));
            config.setExcludeNamePatterns(List.of("*DRAFT*"));
            config.setMaxFileBytes(10L);
            fake.file("img", "photo.png", "image/png", DRIVE, "png")
                    .file("pdf", "manual.pdf", "application/pdf", DRIVE, "%PDF-1.4")
                    .file("draft", "Plan DRAFT.txt", "text/plain", DRIVE, "d")
                    .file("big", "huge.txt", "text/plain", DRIVE, "more than ten bytes")
                    .file("ok", "ok.txt", "text/plain", DRIVE, "fine");

            Recorder run = discover();

            assertEquals(List.of("ok"), run.acceptedIds());
            assertEquals(List.of("img", "pdf", "draft", "big"), run.skipped);
            assertTrue(run.failures.isEmpty(), "a skip is not a failure");
            String summary = run.result.summary();
            assertTrue(summary.contains("1 file(s) to read"), summary);
            assertTrue(summary.contains("Skipped 4"), summary);
            assertTrue(summary.contains("1 image/png"), summary);
            assertEquals(Coverage.FULL_LISTING, run.result.coverage());
        }
        @Test
        @DisplayName("name globs are case-insensitive, and * crosses the slash a Drive name may hold")
        void nameGlobs() {
            assertTrue(GoogleDriveSourceConnector.nameMatches("*draft*", "Plan DRAFT v2"));
            assertTrue(GoogleDriveSourceConnector.nameMatches("~$*", "~$budget.xlsx"));
            assertTrue(GoogleDriveSourceConnector.nameMatches("a?c", "ABC"));
            assertTrue(GoogleDriveSourceConnector.nameMatches("*", "2026/11 notes"));
            assertFalse(GoogleDriveSourceConnector.nameMatches("*.tmp", "a.tmp.txt"));
            assertFalse(GoogleDriveSourceConnector.nameMatches("draft", "draft.txt"));
        }
    }

    @Nested
    @DisplayName("versions")
    class Versions {

        @Test
        @DisplayName("a stored file's version is its checksum, known without downloading")
        void storedFileVersion() throws SourceException {
            fake.file("f1", "a.txt", "text/plain", DRIVE, utf8("a"), "abc123");

            WorkItem item = discover().item("f1");

            assertEquals("md5:abc123", item.versionToken());
            assertEquals(0, fake.countRequests("alt=media"));
        }

        @Test
        @DisplayName("a native file whose version matches the stored validator is unchanged and not exported")
        void nativeUnchanged() throws SourceException {
            fake.nativeFile("d1", "Handbook", FakeDrive.DOC, DRIVE, 7, Map.of("text/markdown", utf8("# H")));
            Recorder recorder = new Recorder(context(false));
            recorder.known.put("d1", new KnownVersion("hash", "v:7", null));

            connector.discover(recorder);

            assertEquals(WorkItem.Action.UNCHANGED, recorder.accepted.get(0).action());
        }

        @Test
        @DisplayName("a native file with a new version is fetched, and its export's hash decides")
        void nativeChanged() throws SourceException {
            fake.nativeFile("d1", "Handbook", FakeDrive.DOC, DRIVE, 8, Map.of("text/markdown", utf8("# H")));
            Recorder recorder = new Recorder(context(false));
            recorder.known.put("d1", new KnownVersion("hash", "v:7", null));

            connector.discover(recorder);
            WorkItem item = recorder.accepted.get(0);
            SourceDocument document = connector.fetch(item, context(false));

            assertEquals(WorkItem.Action.UPSERT, item.action());
            assertNull(item.versionToken());
            assertNull(document.versionToken(), "the hash of the export is the change key");
            assertEquals("v:8", document.validators().etag());
        }
    }

    @Nested
    @DisplayName("content")
    class Contents {

        @Test
        @DisplayName("a Google Doc is exported as Markdown, without its inlined base64 images")
        void googleDoc() throws SourceException {
            String markdown = "# Travel\n\n![][image1]\nBook early.\n![logo](data:image/png;base64,AAAA) end\n\n"
                    + "[image1]: <data:image/png;base64,iVBORw0KGgo=>";
            fake.nativeFile("d1", "Travel", FakeDrive.DOC, DRIVE, 1, Map.of("text/markdown", utf8(markdown)));

            SourceDocument document = fetch("d1", false);

            Content.FileBytes file = assertInstanceOf(Content.FileBytes.class, document.content());
            assertEquals("text/markdown", file.mimeType());
            String text = new String(file.bytes(), StandardCharsets.UTF_8);
            assertTrue(text.contains("Book early."), text);
            assertFalse(text.contains("base64"), text);
            assertFalse(text.contains("[image1]"), text);
            assertEquals("https://docs.google.com/fake/d1", document.citationUrl());
            assertEquals("document", document.metadata().get(SourceMetadataKeys.FORMAT));
            assertEquals(IngestionSource.TYPE_GOOGLE_DRIVE, document.metadata().get(SourceMetadataKeys.PROVIDER));
        }

        @Test
        @DisplayName("a Doc whose Markdown export is refused falls back to HTML")
        void googleDocHtmlFallback() throws SourceException {
            fake.nativeFile("d1", "Travel", FakeDrive.DOC, DRIVE, 1, Map.of("text/html", utf8("<p>Hi</p>")));

            Content.FileBytes file = assertInstanceOf(Content.FileBytes.class, fetch("d1", false).content());

            assertEquals("text/html", file.mimeType());
        }

        @Test
        @DisplayName("a Sheet is exported as XLSX, which carries every sheet")
        void googleSheet() throws SourceException {
            fake.nativeFile("s1", "Budget", FakeDrive.SHEET, DRIVE, 1, Map.of(DriveFormat.XLSX_MIME, utf8("PK")));

            Content.FileBytes file = assertInstanceOf(Content.FileBytes.class, fetch("s1", false).content());

            assertEquals(DriveFormat.XLSX_MIME, file.mimeType());
            assertTrue(fake.countRequests("mimeType=application%2Fvnd.openxmlformats") > 0);
        }

        @Test
        @DisplayName("Slides are exported as plain text")
        void googleSlides() throws SourceException {
            fake.nativeFile("p1", "Deck", FakeDrive.SLIDES, DRIVE, 1, Map.of("text/plain", utf8("Slide one")));

            Content.FileBytes file = assertInstanceOf(Content.FileBytes.class, fetch("p1", false).content());

            assertEquals("text/plain", file.mimeType());
        }

        @Test
        @DisplayName("a stored file is downloaded and typed from its bytes, its extension restored where missing")
        void storedFiles() throws SourceException {
            fake.file("pdf", "manual.pdf", "application/pdf", DRIVE, "%PDF-1.4\n%fake\n")
                    .file("csv", "prices", "text/csv", DRIVE, "a,b\n1,2\n");

            assertEquals("application/pdf",
                    assertInstanceOf(Content.FileBytes.class, fetch("pdf", false).content()).mimeType());
            assertEquals("text/csv", assertInstanceOf(Content.FileBytes.class, fetch("csv", false).content()).mimeType());
            assertTrue(fake.countRequests("alt=media") >= 2);
        }

        @Test
        @DisplayName("a file whose bytes are not what Drive says is a permanent failure")
        void mislabelled() throws SourceException {
            fake.file("pdf", "manual.pdf", "application/pdf", DRIVE, "just text");

            var failure = assertThrows(SourceException.class, () -> fetch("pdf", false));

            assertEquals(SourceError.Kind.PERMANENT, failure.error().kind());
        }

        @Test
        @DisplayName("an export over Google's limit, or a download over maxFileBytes, is skipped as empty text")
        void oversizedContent() throws SourceException {
            fake.nativeFile("d1", "Huge", FakeDrive.DOC, DRIVE, 1, Map.of())
                    .script("/files/d1/export", FakeDrive.error(403, "exportSizeLimitExceeded",
                            "This file is too large to be exported."));
            config.setMaxFileBytes(4L);
            fake.nativeFile("d2", "Long", FakeDrive.DOC, DRIVE, 1, Map.of("text/markdown", utf8("0123456789")));

            assertEquals("", assertInstanceOf(Content.Text.class, fetch("d1", false).content()).text());
            assertEquals("", assertInstanceOf(Content.Text.class, fetch("d2", false).content()).text());
        }

        @Test
        @DisplayName("a preview reads nothing beyond the listing")
        void previewDownloadsNothing() throws SourceException {
            fake.file("f1", "a.txt", "text/plain", DRIVE, "a")
                    .nativeFile("d1", "Doc", FakeDrive.DOC, DRIVE, 1, Map.of("text/markdown", utf8("# d")));

            assertInstanceOf(Content.Text.class, fetch("f1", true).content());
            assertInstanceOf(Content.Text.class, fetch("d1", true).content());

            assertEquals(0, fake.countRequests("alt=media"));
            assertEquals(0, fake.countRequests("/export"));
        }
    }

    @Nested
    @DisplayName("errors")
    class Errors {

        @Test
        @DisplayName("403 rateLimitExceeded is a throttle: waited out, honouring Retry-After, then retried")
        void rateLimitRetried() throws SourceException {
            fake.file("f1", "a.txt", "text/plain", DRIVE, "a")
                    .script("/drive/v3/files?", FakeDrive.error(403, "rateLimitExceeded", "Rate Limit Exceeded", "3"),
                            FakeDrive.error(403, "userRateLimitExceeded", "User Rate Limit Exceeded"));

            Recorder run = discover();

            assertEquals(List.of("f1"), run.acceptedIds());
            assertEquals(List.of(Duration.ofSeconds(3), Duration.ofSeconds(2)), waits);
        }

        @Test
        @DisplayName("a throttle that outlasts the retries stops the listing as partial, deleting nothing")
        void rateLimitExhausted() throws SourceException {
            var limited = FakeDrive.error(403, "rateLimitExceeded", "Rate Limit Exceeded");
            fake.file("f1", "a.txt", "text/plain", DRIVE, "a")
                    .script("/drive/v3/files?", limited, limited, limited, limited);

            Recorder run = discover();

            assertEquals(Coverage.PARTIAL, run.result.coverage());
            assertEquals(StopReason.CANCELLED, run.result.stopReason());
            assertEquals(SourceError.Kind.THROTTLED, run.failures.get(0).kind());
            assertEquals(4, fake.countRequests("/drive/v3/files?"));
        }

        @Test
        @DisplayName("403 insufficientPermissions is an access failure: not retried, and the run fails")
        void insufficientPermissions() {
            fake.script("/drive/v3/files?", FakeDrive.error(403, "insufficientPermissions",
                    "Insufficient permissions for this file"));

            var failure = assertThrows(SourceException.class, GoogleDriveSourceConnectorTest.this::discover);

            assertEquals(SourceError.Kind.AUTH, failure.error().kind());
            assertTrue(failure.getMessage().contains("insufficientPermissions"), failure.getMessage());
            assertEquals(1, fake.requests.size());
            assertTrue(waits.isEmpty());
        }

        @Test
        @DisplayName("the same status means different things by reason")
        void classificationByReason() {
            assertEquals(SourceError.Kind.THROTTLED,
                    DriveApi.classify(FakeDrive.error(403, "rateLimitExceeded", "x")).error().kind());
            assertEquals(SourceError.Kind.AUTH,
                    DriveApi.classify(FakeDrive.error(403, "insufficientPermissions", "x")).error().kind());
            assertEquals(SourceError.Kind.PERMANENT,
                    DriveApi.classify(FakeDrive.error(403, "cannotDownloadFile", "x")).error().kind());
            assertEquals(SourceError.Kind.THROTTLED, DriveApi.classify(FakeDrive.error(429, null, "x")).error().kind());
            assertEquals(SourceError.Kind.NOT_FOUND,
                    DriveApi.classify(FakeDrive.error(404, "notFound", "x")).error().kind());
            assertEquals(SourceError.Kind.TRANSIENT,
                    DriveApi.classify(FakeDrive.error(503, "backendError", "x")).error().kind());
            assertEquals(SourceError.Kind.REJECTED,
                    DriveApi.classify(FakeDrive.error(400, "invalid", "x")).error().kind());
            assertEquals(SourceError.Kind.AUTH, DriveApi.classify(FakeDrive.error(401, "authError", "x")).error().kind());
        }

        @Test
        @DisplayName("a subfolder that cannot be listed makes the run partial, and the rest is still read")
        void subfolderFailure() throws SourceException {
            fake.folder("fa", "A", DRIVE).folder("fb", "B", DRIVE)
                    .file("a", "a.txt", "text/plain", "fa", "a").file("b", "b.txt", "text/plain", "fb", "b");
            var down = FakeDrive.error(500, "backendError", "Backend Error");
            fake.script("%27fa%27", down, down, down, down);

            Recorder run = discover();

            assertEquals(List.of("b"), run.acceptedIds());
            assertEquals(Coverage.PARTIAL, run.result.coverage());
            assertEquals(3, waits.size());
        }

        @Test
        @DisplayName("a document that vanished before it was fetched is NOT_FOUND")
        void fetchNotFound() throws SourceException {
            fake.file("f1", "a.txt", "text/plain", DRIVE, "a");
            WorkItem item = discover().item("f1");
            fake.items.remove("f1");

            var failure = assertThrows(SourceException.class, () -> connector.fetch(item, context(false)));

            assertEquals(SourceError.Kind.NOT_FOUND, failure.error().kind());
        }

        @Test
        @DisplayName("a credential the connection refuses — off its allowlist, say — sends no request at all")
        void refusedCredentialSendsNothing() {
            fake.file("f1", "a.txt", "text/plain", DRIVE, "a");
            credentials = (name, url) -> {
                throw new SourceException(new SourceError.Auth("TARGET_NOT_ALLOWED"));
            };

            assertThrows(SourceException.class, GoogleDriveSourceConnectorTest.this::discover);
            assertThrows(SourceException.class,
                    () -> connector.verifyAccess(context(false), new Partition(DRIVE, null)));

            assertTrue(fake.requests.isEmpty());
        }
    }

    @Nested
    @DisplayName("an empty listing")
    class EmptyListing {

        @Test
        @DisplayName("after the service account lost the drive, fails the run rather than reporting it empty")
        void accessLost() {
            fake.file("f1", "a.txt", "text/plain", DRIVE, "a");
            fake.driveVisible = false;

            var failure = assertThrows(SourceException.class, GoogleDriveSourceConnectorTest.this::discover);

            assertEquals(SourceError.Kind.AUTH, failure.error().kind());
            assertTrue(failure.getMessage().contains("not a member"), failure.getMessage());
            assertEquals(1, fake.countRequests("/drive/v3/drives/"));
        }

        @Test
        @DisplayName("of a drive that really is empty is believed, once access is confirmed")
        void reallyEmpty() throws SourceException {
            Recorder run = discover();

            assertEquals(Coverage.FULL_LISTING, run.result.coverage());
            assertEquals(1, fake.countRequests("/drive/v3/drives/"));
        }

        @Test
        @DisplayName("of a configured folder that was trashed fails the run")
        void folderTrashed() {
            fake.folder("fa", "A", DRIVE).trash("fa");
            config.setFolderIds(List.of("fa"));

            var failure = assertThrows(SourceException.class, GoogleDriveSourceConnectorTest.this::discover);

            assertTrue(failure.getMessage().contains("trash"), failure.getMessage());
        }

        @Test
        @DisplayName("a listing that found something does not re-check access")
        void nonEmptyNotRechecked() throws SourceException {
            fake.file("f1", "a.txt", "text/plain", DRIVE, "a");

            discover();

            assertEquals(0, fake.countRequests("/drive/v3/drives/"));
        }
    }

    @Nested
    @DisplayName("access check before a run")
    class VerifyAccess {

        @Test
        @DisplayName("checks the drive and every configured folder")
        void checksDriveAndFolders() throws SourceException {
            fake.folder("fa", "A", DRIVE);
            config.setFolderIds(List.of("fa"));

            connector.verifyAccess(context(false), new Partition(DRIVE, null));

            assertEquals(1, fake.countRequests("/drive/v3/drives/" + DRIVE));
            assertEquals(1, fake.countRequests("/drive/v3/files/fa?"));
        }

        @Test
        @DisplayName("refuses a folder id that is a file")
        void notAFolder() {
            fake.file("f1", "a.txt", "text/plain", DRIVE, "a");
            config.setFolderIds(List.of("f1"));

            var failure = assertThrows(SourceException.class,
                    () -> connector.verifyAccess(context(false), new Partition(DRIVE, null)));

            assertTrue(failure.getMessage().contains("not a folder"), failure.getMessage());
        }

        @Test
        @DisplayName("names the Shared Drive as the partition")
        void partition() {
            assertEquals(List.of(new Partition(DRIVE, null)), connector.listPartitions(context(false)));
        }
    }

    /** A discovery that records what the connector hands over. */
    private static final class Recorder implements DiscoveryContext {

        private final SourceContext source;
        final List<WorkItem> accepted = new ArrayList<>();
        final List<String> skipped = new ArrayList<>();
        final List<SourceError> failures = new ArrayList<>();
        final Map<String, KnownVersion> known = new HashMap<>();
        boolean cancelled;
        DiscoveryResult result;

        Recorder(SourceContext source) {
            this.source = source;
        }

        List<String> acceptedIds() {
            return accepted.stream().map(WorkItem::documentId).toList();
        }

        WorkItem item(String id) {
            return accepted.stream().filter(item -> item.documentId().equals(id)).findFirst().orElseThrow();
        }

        @Override
        public SourceContext source() {
            return source;
        }

        @Override
        public Partition partition() {
            return new Partition(DRIVE, null);
        }

        @Override
        public Optional<String> cursor() {
            return Optional.empty();
        }

        @Override
        public Instant deadline() {
            return Instant.now().plus(Duration.ofMinutes(10));
        }

        @Override
        public Optional<KnownVersion> knownVersion(String documentId) {
            return Optional.ofNullable(known.get(documentId));
        }

        @Override
        public void accept(WorkItem item) {
            accepted.add(item);
        }

        @Override
        public void reportFailure(String documentId, String location, SourceError error) {
            failures.add(error);
        }

        @Override
        public void reportSkipped(String documentId, String reason) {
            skipped.add(documentId);
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }
}
