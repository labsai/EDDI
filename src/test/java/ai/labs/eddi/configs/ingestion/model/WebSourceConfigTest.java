/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.ingestion.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WebSourceConfigTest {

    @Nested
    class ScopeTests {

        @Test
        void maxPages_zero_throws() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> new WebSourceConfig.Scope(true, "/", 1, 0, List.of()));
            assertTrue(ex.getMessage().contains("maxPages"));
        }

        @Test
        void maxPages_negative_throws() {
            assertThrows(IllegalArgumentException.class,
                    () -> new WebSourceConfig.Scope(true, "/", 1, -5, List.of()));
        }

        @Test
        void maxPages_one_ok() {
            var s = new WebSourceConfig.Scope(true, "/", 1, 1, List.of());
            assertEquals(1, s.maxPages());
        }

        @Test
        void maxDepth_zero_throws() {
            assertThrows(IllegalArgumentException.class,
                    () -> new WebSourceConfig.Scope(true, "/", 0, 200, List.of()));
        }

        @Test
        void maxDepth_negative_throws() {
            assertThrows(IllegalArgumentException.class,
                    () -> new WebSourceConfig.Scope(true, "/", -3, 200, List.of()));
        }

        @Test
        void pathPrefix_null_throws() {
            assertThrows(IllegalArgumentException.class,
                    () -> new WebSourceConfig.Scope(true, null, 3, 200, List.of()));
        }

        @Test
        void excludePatterns_null_defaultsToEmpty() {
            var s = new WebSourceConfig.Scope(true, "/", 3, 200, null);
            assertNotNull(s.excludePatterns());
            assertTrue(s.excludePatterns().isEmpty());
        }

        @Test
        void validate_maxPagesExceedsLimit_throws() {
            var s = new WebSourceConfig.Scope(true, "/", 3, 10_001, List.of());
            var ex = assertThrows(IllegalArgumentException.class, s::validate);
            assertTrue(ex.getMessage().contains("maxPages"));
        }

        @Test
        void validate_maxDepthExceedsLimit_throws() {
            var s = new WebSourceConfig.Scope(true, "/", 21, 200, List.of());
            var ex = assertThrows(IllegalArgumentException.class, s::validate);
            assertTrue(ex.getMessage().contains("maxDepth"));
        }

        @Test
        void noArgConstructor_defaults() {
            var s = new WebSourceConfig.Scope();
            assertTrue(s.sameDomainOnly());
            assertEquals("/", s.pathPrefix());
            assertEquals(3, s.maxDepth());
            assertEquals(200, s.maxPages());
            assertTrue(s.excludePatterns().isEmpty());
        }
    }

    @Nested
    class CrawlSettingsTests {

        @Test
        void requestDelayMs_negative_throws() {
            assertThrows(IllegalArgumentException.class,
                    () -> new WebSourceConfig.CrawlSettings(-1, 15, "UA"));
        }

        @Test
        void requestDelayMs_zero_ok() {
            var c = new WebSourceConfig.CrawlSettings(0, 15, "UA");
            assertEquals(0, c.requestDelayMs());
        }

        @Test
        void timeoutSeconds_zero_throws() {
            assertThrows(IllegalArgumentException.class,
                    () -> new WebSourceConfig.CrawlSettings(500, 0, "UA"));
        }

        @Test
        void timeoutSeconds_negative_throws() {
            assertThrows(IllegalArgumentException.class,
                    () -> new WebSourceConfig.CrawlSettings(500, -1, "UA"));
        }

        @Test
        void userAgent_null_throws() {
            assertThrows(IllegalArgumentException.class,
                    () -> new WebSourceConfig.CrawlSettings(500, 15, null));
        }

        @Test
        void validate_requestDelayExceedsLimit_throws() {
            var c = new WebSourceConfig.CrawlSettings(30_001, 15, "UA");
            var ex = assertThrows(IllegalArgumentException.class, c::validate);
            assertTrue(ex.getMessage().contains("requestDelayMs"));
        }

        @Test
        void validate_timeoutExceedsLimit_throws() {
            var c = new WebSourceConfig.CrawlSettings(500, 301, "UA");
            var ex = assertThrows(IllegalArgumentException.class, c::validate);
            assertTrue(ex.getMessage().contains("timeoutSeconds"));
        }

        @Test
        void noArgConstructor_defaults() {
            var c = new WebSourceConfig.CrawlSettings();
            assertEquals(500, c.requestDelayMs());
            assertEquals(15, c.timeoutSeconds());
            assertEquals("EDDI-Crawler/1.0", c.userAgent());
        }
    }

    @Nested
    class WebSourceConfigTests {

        @Test
        void scope_null_defaults() {
            var w = new WebSourceConfig("https://example.com", null, null);
            assertNotNull(w.scope());
            assertNotNull(w.crawlSettings());
        }

        @Test
        void validate_startUrlBlank_throws() {
            var w = new WebSourceConfig(" ", new WebSourceConfig.Scope(), new WebSourceConfig.CrawlSettings());
            var ex = assertThrows(IllegalArgumentException.class, w::validate);
            assertTrue(ex.getMessage().contains("startUrl"));
        }

        @Test
        void validate_startUrlInvalid_throws() {
            var w = new WebSourceConfig("not-a-url", new WebSourceConfig.Scope(), new WebSourceConfig.CrawlSettings());
            assertThrows(IllegalArgumentException.class, w::validate);
        }

        @Test
        void validate_startUrlValid_ok() {
            var w = new WebSourceConfig("https://example.com",
                    new WebSourceConfig.Scope(), new WebSourceConfig.CrawlSettings());
            assertDoesNotThrow(w::validate);
        }

        @Test
        void jacksonRoundTrip() throws Exception {
            var original = new WebSourceConfig("https://docs.labs.ai",
                    new WebSourceConfig.Scope(true, "/docs", 5, 100, List.of("**/api/**")),
                    new WebSourceConfig.CrawlSettings(1000, 30, "EDDI-Crawler/2.0"));

            var mapper = new ObjectMapper();
            var json = mapper.writeValueAsString(original);
            var deserialized = mapper.readValue(json, WebSourceConfig.class);

            assertEquals(original.startUrl(), deserialized.startUrl());
            assertEquals(original.scope().maxDepth(), deserialized.scope().maxDepth());
            assertEquals(original.scope().maxPages(), deserialized.scope().maxPages());
            assertEquals(original.crawlSettings().requestDelayMs(), deserialized.crawlSettings().requestDelayMs());
        }
    }
}
