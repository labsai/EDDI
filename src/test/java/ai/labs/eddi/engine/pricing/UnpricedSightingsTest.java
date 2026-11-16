/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.pricing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The node-local list of models that ran without a price, which the Manager
 * offers an "add price" shortcut for.
 */
class UnpricedSightingsTest {

    @BeforeEach
    @AfterEach
    void clear() {
        UnpricedSightings.clear();
    }

    private static UnpricedSightings.Sighting only() {
        List<UnpricedSightings.Sighting> rows = UnpricedSightings.list();
        assertEquals(1, rows.size(), rows.toString());
        return rows.getFirst();
    }

    @Test
    void theProviderAndModelAreSplitAtTheFirstSlashAndCountsAccumulate() {
        UnpricedSightings.record("openai/ft:gpt/custom", "llm_task", 2);
        UnpricedSightings.record("openai/ft:gpt/custom", "llm_task", 3);
        var row = only();
        assertEquals("openai", row.provider());
        assertEquals("ft:gpt/custom", row.model());
        assertEquals("llm_task", row.site());
        assertEquals(5, row.count());
    }

    @Test
    void anUnknownOrBareModelIsRecordedAsSuch() {
        UnpricedSightings.record(null, "judge", 1);
        assertEquals("unknown", only().provider());
        assertEquals("unknown", only().model());
        clear();

        UnpricedSightings.record("  ", "judge", 1);
        assertEquals("unknown", only().provider());
        clear();

        UnpricedSightings.record("ollama", "judge", 1);
        assertEquals("ollama", only().provider(), "no slash: the whole name is the provider");
        assertEquals("unknown", only().model());
        clear();

        UnpricedSightings.record("/model-only", "judge", 1);
        assertEquals("/model-only", only().provider(), "a leading slash names no provider");
        assertEquals("model-only", only().model());
    }

    @Test
    void aLongModelNameIsCapped() {
        UnpricedSightings.record("openai/" + "m".repeat(300), "llm_task", 1);
        assertEquals(128, only().model().length());
    }

    @Test
    void noCallsRecordsNothing() {
        UnpricedSightings.record("openai/gpt", "llm_task", 0);
        UnpricedSightings.record("openai/gpt", "llm_task", -1);
        assertTrue(UnpricedSightings.list().isEmpty());
    }

    @Test
    void theListIsBoundedAndMostRecentFirst() {
        for (int i = 0; i < UnpricedSightings.MAX_SIGHTINGS + 5; i++) {
            UnpricedSightings.record("p/m" + i, "site", 1);
        }
        var rows = UnpricedSightings.list();
        assertEquals(UnpricedSightings.MAX_SIGHTINGS, rows.size());
        assertTrue(rows.stream().noneMatch(r -> r.model().equals("m0")), "the eldest row is evicted");
        assertTrue(rows.getFirst().lastSeen().compareTo(rows.getLast().lastSeen()) >= 0);
    }

    @Test
    void meterTagsAreBounded() {
        assertEquals("unknown", UnpricedSightings.tag(null));
        assertEquals("unknown", UnpricedSightings.tag(" "));
        // The tag set is JVM-wide, so other tests may have filled part of it already.
        String first = UnpricedSightings.tag("tag-model-0");
        for (int i = 1; i < UnpricedSightings.MAX_MODEL_TAGS + 5; i++) {
            UnpricedSightings.tag("tag-model-" + i);
        }
        assertEquals(first, UnpricedSightings.tag("tag-model-0"), "a tag, once given, stays the same");
        assertEquals(UnpricedSightings.OTHER, UnpricedSightings.tag("a-model-after-the-cap"));
    }
}
