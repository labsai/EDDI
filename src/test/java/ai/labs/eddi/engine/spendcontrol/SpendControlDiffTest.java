/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.spendcontrol;

import ai.labs.eddi.engine.pricing.model.LongContextTier;
import ai.labs.eddi.engine.pricing.model.ModelPriceCatalog;
import ai.labs.eddi.engine.pricing.model.ModelPriceEntry;
import ai.labs.eddi.engine.pricing.model.UnpricedPolicy;
import ai.labs.eddi.engine.tenancy.model.TenantQuota;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SpendControlDiff")
class SpendControlDiffTest {

    private static final String SRC = "https://example.com/pricing";

    private static ModelPriceEntry entry(String provider, String model, Double in, Double out) {
        return new ModelPriceEntry(provider, model, in, out, null, null, null, null, SRC, null);
    }

    private static ModelPriceCatalog catalog(UnpricedPolicy policy, ModelPriceEntry... entries) {
        return new ModelPriceCatalog(3, policy, null, null, List.of(entries));
    }

    private static SpendControlDiff.Result diff(ModelPriceCatalog before, ModelPriceCatalog after) {
        return SpendControlDiff.compareCatalogs(before, after, before.unpricedPolicy());
    }

    @Nested
    @DisplayName("price catalog")
    class Catalog {

        @Test
        @DisplayName("raising a price is stricter")
        void raise() {
            var before = catalog(UnpricedPolicy.ALLOW, entry("openai", "gpt-4o", 2.5, 10.0));
            var after = catalog(UnpricedPolicy.ALLOW, entry("openai", "gpt-4o", 3.0, 12.0));
            var result = diff(before, after);
            assertEquals(1, result.changes().size());
            assertFalse(result.loosens());
        }

        @Test
        @DisplayName("lowering one side of a price loosens")
        void lower() {
            var before = catalog(UnpricedPolicy.ALLOW, entry("openai", "gpt-4o", 2.5, 10.0));
            var after = catalog(UnpricedPolicy.ALLOW, entry("openai", "gpt-4o", 3.0, 0.0));
            assertTrue(diff(before, after).loosens());
        }

        @Test
        @DisplayName("clearing a price loosens even when its fallback is equal")
        void cleared() {
            var before = catalog(UnpricedPolicy.ALLOW,
                    new ModelPriceEntry("openai", "gpt-4o", 2.5, 10.0, 2.5, null, null, null, SRC, null));
            var after = catalog(UnpricedPolicy.ALLOW, entry("openai", "gpt-4o", 2.5, 10.0));
            assertTrue(diff(before, after).loosens());
        }

        @Test
        @DisplayName("a cache price below the input price it replaces loosens")
        void cacheBelowInput() {
            var before = catalog(UnpricedPolicy.ALLOW, entry("anthropic", "claude-x", 3.0, 15.0));
            var after = catalog(UnpricedPolicy.ALLOW, new ModelPriceEntry("anthropic", "claude-x", 3.0, 15.0, 0.3, null, null, null, SRC, null));
            assertTrue(diff(before, after).loosens());
        }

        @Test
        @DisplayName("removing an entry loosens")
        void removed() {
            var before = catalog(UnpricedPolicy.ALLOW, entry("openai", "gpt-4o", 2.5, 10.0));
            var after = catalog(UnpricedPolicy.ALLOW);
            assertTrue(diff(before, after).loosens());
        }

        @Test
        @DisplayName("a new entry under ALLOW that overlaps nothing is stricter")
        void addedAlone() {
            var before = catalog(UnpricedPolicy.ALLOW, entry("openai", "gpt-4o", 2.5, 10.0));
            var after = catalog(UnpricedPolicy.ALLOW, entry("openai", "gpt-4o", 2.5, 10.0), entry("anthropic", "claude-x", 3.0, 15.0));
            assertFalse(diff(before, after).loosens());
        }

        @Test
        @DisplayName("a new exact entry cheaper than the wildcard pricing it today loosens")
        void addedUnderWildcard() {
            var before = catalog(UnpricedPolicy.ALLOW, entry("openai", "gpt-4o*", 2.5, 10.0));
            var after = catalog(UnpricedPolicy.ALLOW, entry("openai", "gpt-4o*", 2.5, 10.0), entry("openai", "gpt-4o-mini", 0.15, 0.6));
            assertTrue(diff(before, after).loosens());
        }

        @Test
        @DisplayName("a new entry for provider * is compared with every provider's overlapping entries")
        void addedStarProvider() {
            var before = catalog(UnpricedPolicy.ALLOW, entry("openai", "*", 5.0, 20.0));
            var after = catalog(UnpricedPolicy.ALLOW, entry("openai", "*", 5.0, 20.0), entry("*", "*", 0.0, 0.0));
            assertTrue(diff(before, after).loosens());
        }

        @Test
        @DisplayName("any new entry loosens while unpriced calls are refused: it admits them")
        void addedUnderRefuse() {
            var before = catalog(UnpricedPolicy.REFUSE);
            var after = catalog(UnpricedPolicy.REFUSE, entry("openai", "gpt-4o", 100.0, 100.0));
            assertTrue(diff(before, after).loosens());
            // the policy in force is what decides, not only the catalog's own field
            var allowDoc = catalog(UnpricedPolicy.ALLOW);
            assertTrue(SpendControlDiff.compareCatalogs(allowDoc, catalog(UnpricedPolicy.ALLOW, entry("openai", "x", 1.0, 1.0)),
                    UnpricedPolicy.REFUSE_WHEN_BUDGETED).loosens());
        }

        @Test
        @DisplayName("a stricter policy is stricter, a looser one loosens")
        void policy() {
            assertFalse(diff(catalog(UnpricedPolicy.ALLOW), catalog(UnpricedPolicy.REFUSE)).loosens());
            assertTrue(diff(catalog(UnpricedPolicy.REFUSE), catalog(UnpricedPolicy.REFUSE_WHEN_BUDGETED)).loosens());
        }

        @Test
        @DisplayName("lowering the long-context threshold over a dearer tier is stricter, raising it loosens")
        void threshold() {
            var tier = new LongContextTier(200_000L, 6.0, 22.5, null, null);
            var lower = new LongContextTier(100_000L, 6.0, 22.5, null, null);
            var before = catalog(UnpricedPolicy.ALLOW, new ModelPriceEntry("anthropic", "c", 3.0, 15.0, null, null, tier, null, SRC, null));
            var after = catalog(UnpricedPolicy.ALLOW, new ModelPriceEntry("anthropic", "c", 3.0, 15.0, null, null, lower, null, SRC, null));
            assertFalse(diff(before, after).loosens());
            assertTrue(diff(after, before).loosens());
        }

        @Test
        @DisplayName("metadata only is a change that loosens nothing")
        void metadata() {
            var before = catalog(UnpricedPolicy.ALLOW, entry("openai", "gpt-4o", 2.5, 10.0));
            var after = catalog(UnpricedPolicy.ALLOW, new ModelPriceEntry("openai", "gpt-4o", 2.5, 10.0, null, null, null, "2026-11-01", SRC, "n"));
            var result = diff(before, after);
            assertFalse(result.isEmpty());
            assertFalse(result.loosens());
        }

        @ParameterizedTest
        @CsvSource({"gpt-4o,gpt-4o,true", "gpt-4o,gpt-4o-mini,false", "gpt-4o*,gpt-4o-mini,true", "gpt-4o*,claude*,false",
                "gpt-4o*,gpt*,true", "*-mini,*o-mini,true", "*-mini,*-max,false", "gpt*,*mini,true", "*,x,true"})
        @DisplayName("pattern overlap")
        void overlap(String a, String b, boolean expected) {
            assertEquals(expected, SpendControlDiff.patternsOverlap(a, b));
            assertEquals(expected, SpendControlDiff.patternsOverlap(b, a));
        }
    }

    @Nested
    @DisplayName("tenant quota")
    class Quota {

        private TenantQuota quota(int conversations, double cost, boolean enabled) {
            return new TenantQuota("default", conversations, -1, -1, cost, enabled);
        }

        @Test
        @DisplayName("lowering a limit or enabling is stricter")
        void stricter() {
            assertFalse(SpendControlDiff.compareQuotas(quota(100, 50, true), quota(50, 20, true)).loosens());
            assertFalse(SpendControlDiff.compareQuotas(quota(-1, -1, false), quota(-1, 100, true)).loosens());
        }

        @Test
        @DisplayName("raising, making unlimited or disabling loosens")
        void looser() {
            assertTrue(SpendControlDiff.compareQuotas(quota(100, 50, true), quota(100, 60, true)).loosens());
            assertTrue(SpendControlDiff.compareQuotas(quota(100, 50, true), quota(100, -1, true)).loosens());
            assertTrue(SpendControlDiff.compareQuotas(quota(100, 50, true), quota(100, 50, false)).loosens());
        }

        @Test
        @DisplayName("nothing changed is empty")
        void same() {
            assertTrue(SpendControlDiff.compareQuotas(quota(1, 2, true), quota(1, 2, true)).isEmpty());
        }
    }
}
