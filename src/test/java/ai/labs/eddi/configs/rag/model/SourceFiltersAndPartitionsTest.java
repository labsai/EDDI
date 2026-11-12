/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.model;

import ai.labs.eddi.configs.rag.model.SourceFilters.FormatClass;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceFiltersAndPartitionsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Nested
    @DisplayName("filters")
    class Filters {

        @Test
        @DisplayName("reads the documented JSON shape, formats in lower case")
        void readsJson() throws Exception {
            SourceFilters filters = mapper.readValue("""
                    {"formats": ["document", "PDF"], "excludeNamePatterns": ["~$*"],
                     "includePaths": ["/Handbook/"], "maxFileBytes": 1024}
                    """, SourceFilters.class);

            assertEquals(List.of(FormatClass.DOCUMENT, FormatClass.PDF), filters.getFormats());
            assertEquals(List.of("~$*"), filters.getExcludeNamePatterns());
            assertEquals(List.of("/Handbook/"), filters.getIncludePaths());
            assertEquals(1024L, filters.getMaxFileBytes());
            assertDoesNotThrow(() -> filters.validate("s"));
            assertTrue(mapper.writeValueAsString(filters).contains("\"document\""));
        }

        @Test
        @DisplayName("an unknown format is refused with the accepted list")
        void unknownFormat() {
            var e = assertThrows(JsonMappingException.class,
                    () -> mapper.readValue("{\"formats\": [\"spreadshet\"]}", SourceFilters.class));
            assertTrue(e.getMessage().contains("spreadsheet"), e.getMessage());
        }

        @Test
        @DisplayName("absent filters accept every format")
        void emptyAcceptsAll() {
            var filters = new SourceFilters();
            assertDoesNotThrow(() -> filters.validate("s"));
            assertTrue(Arrays.stream(FormatClass.values()).allMatch(filters::acceptsFormat));

            filters.setFormats(List.of(FormatClass.PDF));
            assertTrue(filters.acceptsFormat(FormatClass.PDF));
            assertFalse(filters.acceptsFormat(FormatClass.HTML));
        }

        @Test
        @DisplayName("blank entries and a non-positive size are refused, naming the source")
        void invalid() {
            var blankPattern = new SourceFilters();
            blankPattern.setExcludeNamePatterns(List.of(" "));
            var e = assertThrows(IllegalArgumentException.class, () -> blankPattern.validate("drive"));
            assertTrue(e.getMessage().contains("filters.excludeNamePatterns"));
            assertTrue(e.getMessage().contains("'drive'"));

            var blankPath = new SourceFilters();
            blankPath.setIncludePaths(Arrays.asList("/a/", null));
            assertThrows(IllegalArgumentException.class, () -> blankPath.validate("drive"));

            var nullFormat = new SourceFilters();
            nullFormat.setFormats(Arrays.asList(FormatClass.PDF, null));
            assertThrows(IllegalArgumentException.class, () -> nullFormat.validate("drive"));

            var zero = new SourceFilters();
            zero.setMaxFileBytes(0L);
            assertThrows(IllegalArgumentException.class, () -> zero.validate("drive"));
        }
    }

    @Nested
    @DisplayName("partitions")
    class Partitions {

        @Test
        @DisplayName("LISTED is the default and needs ids")
        void listedNeedsIds() {
            var partitions = new SourcePartitions();
            assertEquals(SourcePartitions.Mode.LISTED, partitions.modeOrDefault());
            var e = assertThrows(IllegalArgumentException.class, () -> partitions.validate("drive"));
            assertTrue(e.getMessage().contains("ALL_ACCESSIBLE"));

            partitions.setIds(List.of("0AbC"));
            assertDoesNotThrow(() -> partitions.validate("drive"));

            partitions.setMode(null);
            assertEquals(SourcePartitions.Mode.LISTED, partitions.modeOrDefault());
        }

        @Test
        @DisplayName("ALL_ACCESSIBLE refuses ids rather than ignoring them")
        void allAccessibleRefusesIds() throws Exception {
            SourcePartitions all = mapper.readValue("{\"mode\": \"ALL_ACCESSIBLE\"}", SourcePartitions.class);
            assertDoesNotThrow(() -> all.validate("drive"));

            all.setIds(List.of("0AbC"));
            assertThrows(IllegalArgumentException.class, () -> all.validate("drive"));
        }

        @Test
        @DisplayName("a blank id is refused")
        void blankId() {
            var partitions = new SourcePartitions();
            partitions.setIds(List.of("ok", " "));
            assertThrows(IllegalArgumentException.class, () -> partitions.validate("drive"));
        }
    }
}
