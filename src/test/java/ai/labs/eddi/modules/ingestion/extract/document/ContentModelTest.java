/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract.document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentModelTest {

    @Nested
    @DisplayName("SplitConfig")
    class SplitConfigs {

        @Test
        @DisplayName("hashes to the same value on every run and every JVM")
        void hashIsPinned() {
            // Pinned: the hash keys stored manifests, so a change to it — the canonical
            // string, the digest, the length — orphans every manifest already stored.
            // Raise RULES_VERSION on purpose instead, and update this value with it.
            assertEquals("fcf0e74eca24f85b", SplitConfig.defaults().hash());
        }

        @Test
        @DisplayName("hashes every field, so no two configurations share a hash")
        void hashCoversEveryField() {
            List<SplitConfig> configs = new ArrayList<>();
            for (SplitStrategy strategy : SplitStrategy.values()) {
                configs.add(new SplitConfig(strategy, 10, 12_000, 500, 60));
            }
            configs.add(new SplitConfig(SplitStrategy.AUTO, 11, 12_000, 500, 60));
            configs.add(new SplitConfig(SplitStrategy.AUTO, 10, 12_001, 500, 60));
            configs.add(new SplitConfig(SplitStrategy.AUTO, 10, 12_000, 501, 60));
            configs.add(new SplitConfig(SplitStrategy.AUTO, 10, 12_000, 500, 61));
            Set<String> hashes = new HashSet<>();
            for (SplitConfig config : configs) {
                String hash = config.hash();
                assertTrue(hash.matches("[0-9a-f]{16}"), hash);
                hashes.add(hash);
            }
            assertEquals(configs.size(), hashes.size());
        }

        @Test
        @DisplayName("falls back to the defaults for values that are not positive")
        void normalizes() {
            var config = new SplitConfig(null, 0, -1, -1, 0);

            assertEquals(SplitConfig.defaults(), config);
            assertEquals(SplitConfig.defaults().hash(), config.hash());
        }

        @Test
        @DisplayName("keeps the minimum below the maximum")
        void minimumBelowMaximum() {
            var config = new SplitConfig(SplitStrategy.PAGES, 5, 1_000, 5_000, 10);

            assertEquals(500, config.minUnitTokens());
            assertEquals(0, new SplitConfig(SplitStrategy.PAGES, 5, 1_000, 0, 10).minUnitTokens());
        }
    }

    @Nested
    @DisplayName("blocks and the manifest")
    class Records {

        @Test
        @DisplayName("orders blocks by page, then from the top down, then by their order")
        void readingOrder() {
            Block lower = new TextBlock(1, 300f, 0, "lower", false);
            Block upperRight = new ImageBlock(1, 100f, 1, "i1", 10, 10, null, ImageBlock.Origin.EMBEDDED);
            Block upperLeft = new TextBlock(1, 100f, 0, "upper left", true);
            Block nextPage = new AudioBlock(2, 0f, 0, "a1", "audio/mpeg", 10, null, null);
            Block table = new TableBlock(1, 500f, 0, "t1", List.of(), 0.9f, 1, 2);
            List<Block> blocks = new ArrayList<>(List.of(nextPage, table, lower, upperRight, upperLeft));

            blocks.sort(Block.READING_ORDER);

            assertEquals(List.of(upperLeft, upperRight, lower, table, nextPage), blocks);
        }

        @Test
        @DisplayName("copies the lists it is given, so a caller cannot change it later")
        void copiesLists() {
            List<Cell> row = new ArrayList<>(List.of(Cell.of("a")));
            List<List<Cell>> rows = new ArrayList<>(List.of(row));
            var table = new TableBlock(1, 0f, 0, "t", rows, 1f, 1, 1);
            row.add(Cell.of("b"));
            rows.add(List.of());

            assertEquals(List.of(List.of(Cell.of("a"))), table.rows());
            assertThrows(UnsupportedOperationException.class, () -> table.rows().get(0).add(Cell.of("c")));

            List<String> warnings = new ArrayList<>(List.of("w"));
            var manifest = new DocumentManifest("r", "s", 1, SplitStrategy.PAGES, null, null, warnings);
            warnings.add("later");
            assertEquals(List.of("w"), manifest.warnings());
            assertEquals(List.of(), manifest.units());
        }

        @Test
        @DisplayName("a cell spans at least one row and one column")
        void cellSpans() {
            assertEquals(new Cell("x", 1, 1), new Cell("x", 0, -3));
            assertNotEquals(new Cell("x", 2, 1), Cell.of("x"));
        }

        @Test
        @DisplayName("lists as leaves the units that no other unit is part of, in document order")
        void leaves() {
            var units = List.of(new DocumentUnit("u1", "a", 1, null, 1, 4, 0, 0, 0, 0),
                    new DocumentUnit("u1.1", "a1", 2, "u1", 1, 2, 0, 0, 0, 0),
                    new DocumentUnit("u1.2", "a2", 2, "u1", 3, 4, 0, 0, 0, 0),
                    new DocumentUnit("u2", "b", 1, null, 5, 5, 0, 0, 0, 0));
            var manifest = new DocumentManifest("r", "s", 5, SplitStrategy.OUTLINE, units, List.of(), List.of());

            assertEquals(List.of("u1.1", "u1.2", "u2"), manifest.leaves().stream().map(DocumentUnit::id).toList());
            assertEquals(2, units.get(1).pageCount());
        }
    }
}
