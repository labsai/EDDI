/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.drive;

import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.IngestionSource.DriveFolder;
import ai.labs.eddi.configs.rag.model.IngestionSource.GoogleDriveSource;
import ai.labs.eddi.connections.ResolvedCredential;
import ai.labs.eddi.modules.ingestion.connector.Coverage;
import ai.labs.eddi.modules.ingestion.connector.DiscoveryContext;
import ai.labs.eddi.modules.ingestion.connector.DiscoveryResult;
import ai.labs.eddi.modules.ingestion.connector.KnownVersion;
import ai.labs.eddi.modules.ingestion.connector.Partition;
import ai.labs.eddi.modules.ingestion.connector.SourceContext;
import ai.labs.eddi.modules.ingestion.connector.SourceError;
import ai.labs.eddi.modules.ingestion.connector.SourceException;
import ai.labs.eddi.modules.ingestion.connector.WorkItem;
import ai.labs.eddi.modules.ingestion.drive.GoogleDriveSourceConnector.FolderPage;
import ai.labs.eddi.modules.ingestion.drive.GoogleDriveSourceConnector.PickerFolder;
import ai.labs.eddi.modules.ingestion.extract.DocumentExtractors;
import ai.labs.eddi.modules.ingestion.extract.PlainTextExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Folder scoping of the Drive source against {@link FakeDrive}: folders by
 * path, renames, exclusions and their precedence, and the folder picker.
 */
class GoogleDriveFolderScopeTest {

    private static final String DRIVE = "0AdriveAAA";

    private FakeDrive fake;
    private GoogleDriveSourceConnector connector;
    private GoogleDriveSource config;
    private IngestionSource source;

    /** What the pipeline keeps for the source between runs. */
    private String recordedScope;

    @BeforeEach
    void setUp() {
        fake = new FakeDrive(DRIVE);
        connector = new GoogleDriveSourceConnector(fake, new DocumentExtractors(List.of(new PlainTextExtractor())));
        connector.sleeper = duration -> {
        };
        config = new GoogleDriveSource();
        config.setConnection("google-drive-sa");
        config.setSharedDriveId(DRIVE);
        source = new IngestionSource();
        source.setName("handbook");
        source.setType(IngestionSource.TYPE_GOOGLE_DRIVE);
        source.setGoogleDrive(config);
        recordedScope = null;

        // Sales/Offers/q1.txt, Sales/Offers/Old/q0.txt, Sales/Archive/a.txt,
        // Sales/Archive/Deep/d.txt, Legal/l.txt, top.txt
        fake.folder("fSales", "Sales", DRIVE)
                .folder("fOffers", "Offers", "fSales")
                .folder("fOld", "Old", "fOffers")
                .folder("fArchive", "Archive", "fSales")
                .folder("fDeep", "Deep", "fArchive")
                .folder("fLegal", "Legal", DRIVE)
                .file("q1", "q1.txt", "text/plain", "fOffers", "offer one")
                .file("q0", "q0.txt", "text/plain", "fOld", "offer zero")
                .file("a", "a.txt", "text/plain", "fArchive", "archived")
                .file("d", "d.txt", "text/plain", "fDeep", "deep archived")
                .file("l", "l.txt", "text/plain", "fLegal", "legal")
                .file("top", "top.txt", "text/plain", DRIVE, "top");
    }

    private Recorder discover() throws SourceException {
        Recorder recorder = new Recorder(new SourceContext(source, "kb/handbook", "run-1", false,
                (name, url) -> new ResolvedCredential("Authorization", "Bearer fixture-token")));
        recorder.result = connector.discover(recorder);
        return recorder;
    }

    private SourceError failure(Runnable setup) {
        setup.run();
        SourceException failure = assertThrows(SourceException.class, this::discover);
        return failure.error();
    }

    private static DriveFolder path(String path) {
        return DriveFolder.ofPath(path);
    }

    private static DriveFolder id(String id) {
        return DriveFolder.ofId(id);
    }

    @Nested
    @DisplayName("folders by path")
    class Paths {

        @Test
        @DisplayName("a path resolves to its folder, which is read with its subfolders and nothing else")
        void resolves() throws SourceException {
            config.setFolders(List.of(path("Sales/Offers")));

            Recorder run = discover();

            assertEquals(List.of("q1", "q0"), run.acceptedIds());
            assertEquals(Coverage.FULL_LISTING, run.result.coverage());
            assertTrue(recordedScope.contains("\"include:Sales/Offers\":\"fOffers\""), recordedScope);
            assertEquals("fOffers", run.item("q1").hints().get(GoogleDriveSourceConnector.HINT_PARENT));
            assertEquals("fOld", run.item("q0").hints().get(GoogleDriveSourceConnector.HINT_PARENT));
        }

        @Test
        @DisplayName("spaces around each name are ignored")
        void trimmedSegments() throws SourceException {
            config.setFolders(List.of(path(" Sales / Offers ")));

            assertEquals(List.of("q1", "q0"), discover().acceptedIds());
        }

        @Test
        @DisplayName("each file carries the chain of folders above it, from the drive's top down")
        void folderChain() throws SourceException {
            config.setFolders(List.of(path("Sales/Offers")));

            Recorder run = discover();

            assertEquals("/fSales/fOffers/", run.item("q1").hints().get(GoogleDriveSourceConnector.HINT_FOLDERS));
            assertEquals("/fSales/fOffers/fOld/", run.item("q0").hints().get(GoogleDriveSourceConnector.HINT_FOLDERS));
            assertEquals("/fSales/fOffers/", run.item("q1").citation().folderIds());
        }

        @Test
        @DisplayName("names compare in NFC and, failing an exact match, ignoring case")
        void unicodeAndCase() throws SourceException {
            // "Café" stored decomposed (e + combining acute), configured composed and
            // in lower case.
            fake.folder("fCafe", "Café", DRIVE).file("c", "c.txt", "text/plain", "fCafe", "coffee");
            config.setFolders(List.of(path("café")));

            assertEquals(List.of("c"), discover().acceptedIds());
        }

        @Test
        @DisplayName("an exact match wins over one that only matches ignoring case")
        void exactWins() throws SourceException {
            fake.folder("fSalesLower", "sales", DRIVE).file("s", "s.txt", "text/plain", "fSalesLower", "lower");
            config.setFolders(List.of(path("sales")));

            assertEquals(List.of("s"), discover().acceptedIds());
        }

        @Test
        @DisplayName("two folders of the same name are an error naming both ids, never a guess")
        void ambiguous() {
            fake.folder("fOffers2", "Offers", "fSales");
            config.setFolders(List.of(path("Sales/Offers")));

            SourceError error = failure(() -> {
            });

            assertInstanceOf(SourceError.Rejected.class, error);
            assertTrue(error.reason().contains("ambiguous"), error.reason());
            assertTrue(error.reason().contains("fOffers") && error.reason().contains("fOffers2"), error.reason());
            assertNull(recordedScope, "nothing is recorded for an ambiguous path");
        }

        @Test
        @DisplayName("two folders that match only ignoring case are ambiguous too")
        void ambiguousIgnoringCase() {
            fake.folder("fUpper", "OFFERS", "fSales").folder("fLower", "offers", "fSales");
            config.setFolders(List.of(path("Sales/Offers")));
            fake.delete("fOffers");

            SourceError error = failure(() -> {
            });

            assertTrue(error.reason().contains("fUpper") && error.reason().contains("fLower"), error.reason());
        }

        @Test
        @DisplayName("a path that does not exist fails the run rather than reading nothing")
        void missing() {
            config.setFolders(List.of(path("Sales/Quotes")));

            SourceError error = failure(() -> {
            });

            assertInstanceOf(SourceError.Rejected.class, error);
            assertTrue(error.reason().contains("Quotes"), error.reason());
        }

        @Test
        @DisplayName("a renamed folder keeps being read by its recorded id, and the run says where it is now")
        void rename() throws SourceException {
            config.setFolders(List.of(path("Sales/Offers")));
            Recorder first = discover();
            fake.rename("fOffers", "Quotes");

            Recorder second = discover();

            assertEquals(List.of("q1", "q0"), second.acceptedIds());
            assertTrue(second.result.summary().contains("'Sales/Offers' is now 'Sales/Quotes'"),
                    second.result.summary());
            assertEquals(first.result.scopeSignature(), second.result.scopeSignature(),
                    "a rename is not a scope change");
        }

        @Test
        @DisplayName("a recorded folder moved elsewhere in the drive is still read where it is")
        void moved() throws SourceException {
            config.setFolders(List.of(path("Sales/Offers")));
            discover();
            fake.move("fOffers", "fLegal");

            Recorder second = discover();

            assertEquals(List.of("q1", "q0"), second.acceptedIds());
            assertTrue(second.result.summary().contains("is now 'Legal/Offers'"), second.result.summary());
        }

        @Test
        @DisplayName("a recorded folder that is gone is resolved again, and the scope changes")
        void goneAndRecreated() throws SourceException {
            config.setFolders(List.of(path("Sales/Offers")));
            Recorder first = discover();
            fake.delete("fOffers").folder("fOffersNew", "Offers", "fSales")
                    .file("n", "n.txt", "text/plain", "fOffersNew", "new");

            Recorder second = discover();

            assertEquals(List.of("n"), second.acceptedIds());
            assertTrue(second.result.summary().contains("now resolves to fOffersNew"), second.result.summary());
            assertNotEquals(first.result.scopeSignature(), second.result.scopeSignature());
            assertTrue(recordedScope.contains("fOffersNew"), recordedScope);
        }

        @Test
        @DisplayName("a recorded folder that is gone with nothing at its path fails the run")
        void goneForGood() throws SourceException {
            config.setFolders(List.of(path("Sales/Offers")));
            discover();
            fake.trash("fOffers");

            SourceError error = failure(() -> {
            });

            assertInstanceOf(SourceError.Auth.class, error);
            assertTrue(error.reason().contains("fOffers"), error.reason());
        }

        @Test
        @DisplayName("an unchanged scope records nothing again")
        void unchangedRecordsNothing() throws SourceException {
            config.setFolders(List.of(path("Sales/Offers")));
            Recorder first = discover();
            assertEquals(1, first.recordings);

            Recorder second = discover();

            assertEquals(0, second.recordings);
        }
    }

    @Nested
    @DisplayName("exclusions")
    class Exclusions {

        @Test
        @DisplayName("an excluded folder's whole subtree is not read, and not even listed")
        void subtree() throws SourceException {
            config.setExcludeFolders(List.of(path("Sales/Archive")));

            Recorder run = discover();

            assertEquals(List.of("top", "l", "q1", "q0"), run.acceptedIds());
            assertFalse(run.acceptedIds().contains("a"));
            assertFalse(run.acceptedIds().contains("d"));
            assertEquals(0, fake.countRequests("%27fArchive%27"), "the excluded folder is never listed");
            assertEquals(0, fake.countRequests("%27fDeep%27"));
            assertEquals(Coverage.FULL_LISTING, run.result.coverage());
            assertTrue(run.result.summary().contains("1 folder(s) not read"), run.result.summary());
        }

        @Test
        @DisplayName("an excluded id works like an excluded path")
        void byId() throws SourceException {
            config.setFolders(List.of(path("Sales")));
            config.setExcludeFolders(List.of(id("fOffers")));

            assertEquals(List.of("a", "d"), discover().acceptedIds());
        }

        @Test
        @DisplayName("exclusion wins over inclusion: a configured folder inside an excluded one is not read")
        void exclusionWins() throws SourceException {
            config.setFolders(List.of(path("Sales/Archive/Deep"), path("Legal")));
            config.setExcludeFolders(List.of(path("Sales/Archive")));

            Recorder run = discover();

            assertEquals(List.of("l"), run.acceptedIds());
            assertTrue(run.result.summary().contains("'Sales/Archive/Deep' lies inside googleDrive.excludeFolders"),
                    run.result.summary());
        }

        @Test
        @DisplayName("exclusion wins over a configured folder that is itself excluded")
        void sameFolderIncludedAndExcluded() throws SourceException {
            config.setFolders(List.of(id("fOffers"), path("Legal")));
            config.setExcludeFolders(List.of(id("fOffers")));

            assertEquals(List.of("l"), discover().acceptedIds());
        }

        @Test
        @DisplayName("every configured folder excluded is refused, not a listing of nothing that empties the base")
        void everythingExcluded() {
            config.setFolders(List.of(path("Sales/Archive/Deep")));
            config.setExcludeFolders(List.of(path("Sales")));

            SourceError error = failure(() -> {
            });

            assertInstanceOf(SourceError.Rejected.class, error);
            assertTrue(error.reason().contains("Every folder"), error.reason());
        }

        @Test
        @DisplayName("excluding the drive itself is refused")
        void wholeDrive() {
            config.setExcludeFolders(List.of(id(DRIVE)));

            assertInstanceOf(SourceError.Rejected.class, failure(() -> {
            }));
        }

        @Test
        @DisplayName("a shortcut into an excluded subtree is not followed, judged by where its target really is")
        void shortcutIntoExcludedSubtree() throws SourceException {
            fake.shortcut("s1", "to deep", "fOffers", "fDeep", FakeDrive.FOLDER);
            config.setFolders(List.of(path("Sales/Offers")));
            config.setExcludeFolders(List.of(path("Sales/Archive")));

            Recorder run = discover();

            assertEquals(List.of("q1", "q0"), run.acceptedIds());
            assertEquals(0, fake.countRequests("%27fDeep%27"));
            assertEquals(Coverage.FULL_LISTING, run.result.coverage());
        }

        @Test
        @DisplayName("a shortcut to a folder outside every exclusion is still followed")
        void shortcutOutsideExclusions() throws SourceException {
            fake.shortcut("s1", "to legal", "fOffers", "fLegal", FakeDrive.FOLDER);
            config.setFolders(List.of(path("Sales/Offers")));
            config.setExcludeFolders(List.of(path("Sales/Archive")));

            assertTrue(discover().acceptedIds().contains("l"));
        }

        @Test
        @DisplayName("an exclusion that never resolved fails the run: a typo would read what was meant to stay out")
        void unresolvedExclusion() {
            config.setExcludeFolders(List.of(path("Sales/Archiv")));

            assertInstanceOf(SourceError.Rejected.class, failure(() -> {
            }));
        }

        @Test
        @DisplayName("an excluded folder deleted since is reported and ignored: nothing is left to exclude")
        void exclusionGone() throws SourceException {
            config.setExcludeFolders(List.of(path("Sales/Archive")));
            discover();
            fake.delete("fDeep").delete("d").delete("a").delete("fArchive");

            Recorder run = discover();

            assertTrue(run.result.summary().contains("The excluded folder 'Sales/Archive' is gone"),
                    run.result.summary());
            assertEquals(Coverage.FULL_LISTING, run.result.coverage());

            // And every run after: the gone exclusion stays recorded, so its path is
            // not taken for a typo that fails the source for good.
            Recorder third = discover();

            assertEquals(Coverage.FULL_LISTING, third.result.coverage());
            assertTrue(third.result.summary().contains("is gone"), third.result.summary());
        }

        @Test
        @DisplayName("an id exclusion that never existed fails the run instead of reading its subtree")
        void unknownIdExclusion() {
            config.setExcludeFolders(List.of(id("fNoSuchFolder")));

            SourceError error = failure(() -> {
            });

            assertInstanceOf(SourceError.Rejected.class, error);
            assertTrue(error.reason().contains("fNoSuchFolder"), error.reason());
        }

        @Test
        @DisplayName("an id exclusion seen before that Drive now answers 404 for stops the run; a bare 404 is no proof")
        void seenIdExclusionThen404() throws SourceException {
            config.setExcludeFolders(List.of(id("fArchive")));
            discover();
            fake.script("/drive/v3/files/fArchive?", FakeDrive.error(404, "notFound", "File not found: fArchive"));

            SourceError error = failure(() -> {
            });

            assertInstanceOf(SourceError.Transient.class, error);
        }

        @Test
        @DisplayName("an id exclusion in the trash stays excluded")
        void trashedIdExclusion() throws SourceException {
            config.setExcludeFolders(List.of(id("fArchive")));
            discover();
            fake.trash("fArchive");
            fake.shortcut("sDeep", "to deep", DRIVE, "fDeep", FakeDrive.FOLDER);

            Recorder run = discover();

            assertFalse(run.acceptedIds().contains("d"), "the subtree stays out, also through a shortcut");
            assertEquals(Coverage.FULL_LISTING, run.result.coverage());
        }

        @Test
        @DisplayName("a shortcut to a folder that no longer exists is skipped and does not block coverage")
        void danglingShortcut() throws SourceException {
            fake.shortcut("sGone", "to nowhere", "fLegal", "fVanished", FakeDrive.FOLDER);
            config.setExcludeFolders(List.of(path("Sales/Archive")));

            Recorder run = discover();

            assertEquals(Coverage.FULL_LISTING, run.result.coverage(), String.valueOf(run.result.incompleteReason()));
            assertTrue(run.skipped.contains("sGone"));
        }

        @Test
        @DisplayName("adding an exclusion changes the signature; reading the same folders again does not")
        void signature() throws SourceException {
            String before = discover().result.scopeSignature();
            assertEquals(before, discover().result.scopeSignature());

            config.setExcludeFolders(List.of(path("Sales/Archive")));

            assertNotEquals(before, discover().result.scopeSignature());
        }

        @Test
        @DisplayName("folderIds and folders by id are the same scope")
        void legacyIdsSameSignature() throws SourceException {
            config.setFolderIds(List.of("fLegal"));
            String legacy = discover().result.scopeSignature();
            config.setFolderIds(List.of());
            config.setFolders(List.of(id("fLegal")));

            assertEquals(legacy, discover().result.scopeSignature());
        }

        @Test
        @DisplayName("includeSubfolders is part of the signature")
        void subfoldersInSignature() throws SourceException {
            String before = discover().result.scopeSignature();
            config.setIncludeSubfolders(false);

            assertNotEquals(before, discover().result.scopeSignature());
        }
    }

    @Nested
    @DisplayName("folder picker")
    class Picker {

        private SourceContext context() {
            return new SourceContext(source, "kb/handbook", null, true,
                    (name, url) -> new ResolvedCredential("Authorization", "Bearer fixture-token"));
        }

        @Test
        @DisplayName("lists the drive's top-level folders only, never a file, in name order")
        void root() throws SourceException {
            FolderPage page = connector.listFolders(context(), "root", null);

            assertEquals(List.of(new PickerFolder("fLegal", "Legal"), new PickerFolder("fSales", "Sales")),
                    page.folders());
            assertNull(page.nextPageToken());
            assertEquals(0, fake.countRequests("alt=media"));
            assertEquals(0, fake.countRequests("/export"));
        }

        @Test
        @DisplayName("lists one level below a folder")
        void child() throws SourceException {
            FolderPage page = connector.listFolders(context(), "fSales", null);

            assertEquals(List.of("Archive", "Offers"), page.folders().stream().map(PickerFolder::name).toList());
        }

        @Test
        @DisplayName("pages at most 200 folders at a time and follows the page token")
        void paging() throws SourceException {
            for (int i = 0; i < 205; i++) {
                fake.folder(String.format("fMany%03d", i), String.format("Team %03d", i), "fLegal");
            }

            FolderPage first = connector.listFolders(context(), "fLegal", null);
            FolderPage second = connector.listFolders(context(), "fLegal", first.nextPageToken());

            assertEquals(200, first.folders().size());
            assertEquals(5, second.folders().size());
            assertNull(second.nextPageToken());
            assertTrue(fake.requests.stream().filter(uri -> uri.getPath().equals("/drive/v3/files"))
                    .allMatch(uri -> uri.getRawQuery().contains("pageSize=200")));
        }

        @Test
        @DisplayName("refuses a parentId that is not a Drive id, before asking Drive anything")
        void malformedParent() {
            SourceException failure = assertThrows(SourceException.class,
                    () -> connector.listFolders(context(), "x' or name contains '", null));

            assertInstanceOf(SourceError.Rejected.class, failure.error());
            assertTrue(fake.requests.isEmpty());
        }

        @Test
        @DisplayName("refuses a page token with whitespace or of absurd length")
        void malformedToken() {
            assertThrows(SourceException.class, () -> connector.listFolders(context(), "root", "p 1"));
            assertThrows(SourceException.class, () -> connector.listFolders(context(), "root", "p".repeat(3000)));
            assertTrue(fake.requests.isEmpty());
        }

        @Test
        @DisplayName("refuses a folder of another drive")
        void otherDrive() {
            fake.folder("fElsewhere", "Elsewhere", "0AotherDrive").inOtherDrive("fElsewhere");

            SourceException failure = assertThrows(SourceException.class,
                    () -> connector.listFolders(context(), "fElsewhere", null));

            assertInstanceOf(SourceError.Rejected.class, failure.error());
            assertEquals(0, fake.countRequests("/drive/v3/files?"), "nothing of the other drive is listed");
        }

        @Test
        @DisplayName("answers not found for a missing folder and for a file id")
        void notFound() {
            assertInstanceOf(SourceError.NotFound.class, assertThrows(SourceException.class,
                    () -> connector.listFolders(context(), "fNoSuch", null)).error());
            assertInstanceOf(SourceError.NotFound.class, assertThrows(SourceException.class,
                    () -> connector.listFolders(context(), "q1", null)).error());
        }
    }

    /**
     * A discovery context that keeps the resolved scope the way the pipeline does.
     */
    private final class Recorder implements DiscoveryContext {

        private final SourceContext source;
        final List<WorkItem> accepted = new ArrayList<>();
        final List<String> skipped = new ArrayList<>();
        int recordings;
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
            return Optional.empty();
        }

        @Override
        public void accept(WorkItem item) {
            accepted.add(item);
        }

        @Override
        public void reportFailure(String documentId, String location, SourceError error) {
        }

        @Override
        public void reportSkipped(String documentId, String reason) {
            skipped.add(documentId);
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public Optional<String> resolvedScope() {
            return Optional.ofNullable(recordedScope);
        }

        @Override
        public void recordResolvedScope(String resolvedScope) {
            recordings++;
            recordedScope = resolvedScope;
        }
    }
}
