/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.model;

import ai.labs.eddi.configs.rag.model.IngestionSource.IngestionSettings;
import ai.labs.eddi.configs.rag.model.IngestionSource.UploadSource;
import ai.labs.eddi.configs.rag.model.IngestionSource.WebSource;
import ai.labs.eddi.modules.ingestion.crawl.CrawlRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Save-time validation of a knowledge base's ingestion sources: every value an
 * operator can type is held to the limits the crawler and the store work to.
 */
class IngestionSourceValidationTest {

    private static IngestionSource web(String startUrl) {
        var web = new WebSource();
        web.setStartUrl(startUrl);
        var source = new IngestionSource();
        source.setName("docs");
        source.setType(IngestionSource.TYPE_WEB);
        source.setWeb(web);
        return source;
    }

    private static String refusal(IngestionSource source) {
        return assertThrows(IllegalArgumentException.class, source::validate).getMessage();
    }

    private static String refusalWith(Consumer<WebSource> change) {
        var source = web("https://docs.example.com/");
        change.accept(source.getWeb());
        return refusal(source);
    }

    @Test
    void aPlainWebSourceIsValid() {
        assertDoesNotThrow(() -> web("https://docs.example.com/guide").validate());
        assertDoesNotThrow(() -> web("http://203.0.113.10/").validate(), "a public literal address is fine");
    }

    @Test
    void theSourceNeedsANameATypeAndAWebBlock() {
        var unnamed = web("https://x.example");
        unnamed.setName(" ");
        assertTrue(refusal(unnamed).contains("needs a name"));
        unnamed.setName(null);
        assertTrue(refusal(unnamed).contains("needs a name"));

        var untyped = web("https://x.example");
        untyped.setType(null);
        assertTrue(refusal(untyped).contains("needs a type"));
        untyped.setType("");
        assertTrue(refusal(untyped).contains("needs a type"));

        var noWeb = web("https://x.example");
        noWeb.setWeb(null);
        assertTrue(refusal(noWeb).contains("needs a 'web' block"));

        var unknown = web("https://x.example");
        unknown.setType("ftp");
        assertTrue(refusal(unknown).contains("Unsupported ingestion source type 'ftp'"));
    }

    @Test
    void theStartUrlMustBeAnHttpUrlWithARoutableHost() {
        assertTrue(refusal(web(null)).contains("needs a startUrl"));
        assertTrue(refusal(web(" ")).contains("needs a startUrl"));
        assertTrue(refusal(web("ftp://example.com")).contains("must be http or https"));
        assertTrue(refusal(web("https:///path")).contains("has no host"));
        assertTrue(refusal(web("https://exa mple.com/")).contains("is not a valid URL"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1/", "http://localhost:8080/", "http://10.0.0.5/", "http://192.168.1.1/", "http://169.254.169.254/",
            "http://0.0.0.0/", "http://[::1]/"})
    void localAndPrivateLiteralsAreRefused(String url) {
        assertTrue(refusal(web(url)).contains("points at a local or private address"), url);
    }

    @Test
    void sitemapsAreCappedAndHeldToTheSameRules() {
        assertTrue(refusalWith(w -> w.setSitemapUrls(new ArrayList<>(Collections.nCopies(WebSource.MAX_SITEMAP_URLS + 1,
                "https://docs.example.com/sitemap.xml")))).contains("at most 20 sitemaps"));
        var withNull = new ArrayList<String>();
        withNull.add(null);
        assertTrue(refusalWith(w -> w.setSitemapUrls(withNull)).contains("must be http or https URLs"));
        assertTrue(refusalWith(w -> w.setSitemapUrls(List.of("sitemap.xml"))).contains("must be http or https URLs"));
        assertTrue(refusalWith(w -> w.setSitemapUrls(List.of("http://127.0.0.1/sitemap.xml"))).contains("sitemapUrls"));

        var ok = web("https://docs.example.com/");
        ok.getWeb().setSitemapUrls(List.of("https://docs.example.com/sitemap.xml"));
        assertDoesNotThrow(ok::validate);
        ok.getWeb().setSitemapUrls(null);
        assertEquals(List.of(), ok.getWeb().getSitemapUrls(), "null is an empty list");
        ok.getWeb().setExcludePatterns(null);
        assertEquals(List.of(), ok.getWeb().getExcludePatterns());
    }

    @Test
    void crawlLimitsAreBounded() {
        assertTrue(refusalWith(w -> w.setMaxDepth(0)).startsWith("maxDepth"));
        assertTrue(refusalWith(w -> w.setMaxDepth(21)).startsWith("maxDepth"));
        assertTrue(refusalWith(w -> w.setMaxPages(50_001)).startsWith("maxPages"));
        assertTrue(refusalWith(w -> w.setTimeoutSeconds(301)).startsWith("timeoutSeconds"));
        assertTrue(refusalWith(w -> w.setRequestDelayMs(-1)).startsWith("requestDelayMs"));
        assertTrue(refusalWith(w -> w.setRequestDelayMs(60_001)).startsWith("requestDelayMs"));

        var unset = web("https://docs.example.com/");
        unset.getWeb().setMaxDepth(null);
        unset.getWeb().setMaxPages(null);
        unset.getWeb().setTimeoutSeconds(null);
        unset.getWeb().setRequestDelayMs(null);
        assertDoesNotThrow(unset::validate, "unset limits take their defaults");
    }

    private static String settingsRefusal(Consumer<IngestionSettings> change) {
        var source = web("https://docs.example.com/");
        var settings = new IngestionSettings();
        change.accept(settings);
        source.setSettings(settings);
        return refusal(source);
    }

    @Test
    void ingestionSettingsAreBounded() {
        assertTrue(settingsRefusal(s -> s.setMaxContentLength(0)).startsWith("maxContentLength"));
        assertTrue(settingsRefusal(s -> s.setTombstoneAfterMissedRuns(0)).startsWith("tombstoneAfterMissedRuns"));
        assertTrue(settingsRefusal(s -> s.setMaxSegmentsPerRun(0)).startsWith("maxSegmentsPerRun"));
        assertTrue(settingsRefusal(s -> s.setTimeBudgetMinutes(0)).startsWith("timeBudgetMinutes"));
        assertTrue(settingsRefusal(s -> s.setTimeBudgetMinutes(24 * 60 + 1)).startsWith("timeBudgetMinutes"));
        assertTrue(settingsRefusal(s -> s.setCostPerThousandSegments(-0.01)).startsWith("costPerThousandSegments"));
        assertTrue(settingsRefusal(s -> s.setMaxBytesPerPage(0L)).startsWith("maxBytesPerPage"));
    }

    @Test
    void maxBytesPerPageHasACeiling() {
        // Every page is buffered whole up to this cap: Long.MAX_VALUE let one source
        // hold the heap with a single response.
        assertTrue(settingsRefusal(s -> s.setMaxBytesPerPage(Long.MAX_VALUE)).startsWith("maxBytesPerPage"));
        assertTrue(settingsRefusal(s -> s.setMaxBytesPerPage(IngestionSettings.MAX_BYTES_PER_PAGE_CEILING + 1)).startsWith("maxBytesPerPage"));
        var atCeiling = new IngestionSettings();
        atCeiling.setMaxBytesPerPage(IngestionSettings.MAX_BYTES_PER_PAGE_CEILING);
        var source = web("https://docs.example.com/");
        source.setSettings(atCeiling);
        assertDoesNotThrow(source::validate);

        // A document stored before the ceiling existed is clamped, not trusted - and
        // still runs: the run path validates it as stored, so the clamp is reached.
        var stored = new IngestionSettings();
        stored.setMaxBytesPerPage(Long.MAX_VALUE);
        assertEquals(IngestionSettings.MAX_BYTES_PER_PAGE_CEILING, stored.maxBytesPerPageOrDefault());
        var storedSource = web("https://docs.example.com/");
        storedSource.setSettings(stored);
        assertDoesNotThrow(storedSource::validateStored, "a stored source above the ceiling keeps running, clamped");
        assertThrows(IllegalArgumentException.class, storedSource::validate, "saving it again is new input and is refused");
        var nonPositive = new IngestionSettings();
        nonPositive.setMaxBytesPerPage(0L);
        storedSource.setSettings(nonPositive);
        assertThrows(IllegalArgumentException.class, storedSource::validateStored, "a non-positive cap is refused either way");
        assertEquals(CrawlRequest.Limits.MAX_BYTES_PER_PAGE,
                new CrawlRequest.Limits(1, 1, Long.MAX_VALUE, 1, null, null).maxBytesPerPage());
    }

    @Test
    void unsetIngestionSettingsTakeTheirDefaults() {
        var settings = new IngestionSettings();
        settings.setMaxContentLength(null);
        settings.setTombstoneAfterMissedRuns(null);
        settings.setMaxSegmentsPerRun(null);
        settings.setMaxBytesPerPage(null);
        settings.setTimeBudgetMinutes(null);
        settings.setCostPerThousandSegments(0.5);
        var source = web("https://docs.example.com/");
        source.setSettings(settings);
        assertDoesNotThrow(source::validate);
        assertEquals(100_000, settings.maxContentLengthOrDefault());
        assertEquals(2, settings.tombstoneAfterMissedRunsOrDefault());
        assertEquals(20_000, settings.maxSegmentsPerRunOrDefault());
        assertEquals(5L * 1024 * 1024, settings.maxBytesPerPageOrDefault());
        assertEquals(10, settings.timeBudgetMinutesOrDefault());

        var explicit = new IngestionSettings();
        explicit.setMaxContentLength(10);
        explicit.setTombstoneAfterMissedRuns(3);
        explicit.setMaxSegmentsPerRun(7);
        explicit.setMaxBytesPerPage(99L);
        explicit.setTimeBudgetMinutes(5);
        assertEquals(10, explicit.maxContentLengthOrDefault());
        assertEquals(3, explicit.tombstoneAfterMissedRunsOrDefault());
        assertEquals(7, explicit.maxSegmentsPerRunOrDefault());
        assertEquals(99L, explicit.maxBytesPerPageOrDefault());
        assertEquals(5, explicit.timeBudgetMinutesOrDefault());
    }

    @Test
    void uploadSourcesAreBoundedAndConsistent() {
        var source = new IngestionSource();
        source.setName("files");
        source.setType(IngestionSource.TYPE_UPLOAD);
        assertTrue(source.isUpload());
        assertDoesNotThrow(source::validate, "no upload block means all defaults");

        var upload = new UploadSource();
        upload.setMaxFileBytes(10L);
        upload.setMaxTotalBytes(5L);
        source.setUpload(upload);
        assertTrue(refusal(source).contains("larger than upload.maxTotalBytes"));

        upload.setMaxTotalBytes(null);
        upload.setMaxFileBytes(51L * 1024 * 1024);
        assertTrue(refusal(source).startsWith("upload.maxFileBytes"));
        upload.setMaxFileBytes(null);
        upload.setMaxFiles(0);
        assertTrue(refusal(source).startsWith("upload.maxFiles"));
        upload.setMaxFiles(null);
        assertDoesNotThrow(source::validate);
        assertEquals(500, upload.maxFilesOrDefault());
        assertEquals(25L * 1024 * 1024, upload.maxFileBytesOrDefault());
        assertEquals(500L * 1024 * 1024, upload.maxTotalBytesOrDefault());
        upload.setMaxFiles(3);
        upload.setMaxFileBytes(4L);
        upload.setMaxTotalBytes(8L);
        assertEquals(3, upload.maxFilesOrDefault());
        assertEquals(4L, upload.maxFileBytesOrDefault());
        assertEquals(8L, upload.maxTotalBytesOrDefault());
    }

    @Test
    void theEffectiveIdFallsBackToTheName() {
        var source = web("https://docs.example.com/");
        assertEquals("docs", source.effectiveId());
        source.setId(" ");
        assertEquals("docs", source.effectiveId());
        source.setId("src-1");
        assertEquals("src-1", source.effectiveId());
        assertFalse(source.isUpload());
    }
}
