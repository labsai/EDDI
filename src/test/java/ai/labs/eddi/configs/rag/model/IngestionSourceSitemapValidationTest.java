/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code web.sitemapUrls} is held to the same rules as {@code startUrl}: the
 * crawler fetches both.
 */
class IngestionSourceSitemapValidationTest {

    private static IngestionSource webSource(List<String> sitemapUrls) {
        var web = new IngestionSource.WebSource();
        web.setStartUrl("https://docs.example.com/");
        web.setSitemapUrls(sitemapUrls);
        var source = new IngestionSource();
        source.setName("docs");
        source.setWeb(web);
        return source;
    }

    @Test
    void acceptsHttpsSitemaps() {
        assertDoesNotThrow(() -> webSource(List.of("https://docs.example.com/sitemap.xml")).validate());
    }

    @Test
    void nullMeansNone() {
        var source = webSource(null);
        assertDoesNotThrow(source::validate);
        assertTrue(source.getWeb().getSitemapUrls().isEmpty());
    }

    @Test
    void refusesANonHttpScheme() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> webSource(List.of("file:///etc/passwd")).validate());
        assertTrue(e.getMessage().contains("sitemapUrls"), e.getMessage());
    }

    @Test
    void refusesAPrivateAddress() {
        // The fetcher would refuse it on every run; saying so at save time beats a
        // source that fails in the run history.
        var e = assertThrows(IllegalArgumentException.class,
                () -> webSource(List.of("http://169.254.169.254/sitemap.xml")).validate());
        assertTrue(e.getMessage().contains("sitemapUrls"), e.getMessage());
    }

    @Test
    void refusesMoreSitemapsThanACrawlReads() {
        var many = new ArrayList<>(Collections.nCopies(21, "https://docs.example.com/sitemap.xml"));
        assertThrows(IllegalArgumentException.class, () -> webSource(many).validate());
    }
}
