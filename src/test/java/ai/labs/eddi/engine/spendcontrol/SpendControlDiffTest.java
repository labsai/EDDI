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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

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

        /*
         * Metering charges an unset tier cache price at the base cache price when one
         * is set, else at the TIER input price (BucketPrices.tier, then cost) — so with
         * base input 10 and tier input 20, an unset tier cache read costs 20, not 10.
         */
        @Test
        @DisplayName("a tier cache price set below the tier input price it replaces loosens")
        void tierCacheBelowTierInput() {
            var unset = new LongContextTier(1_000L, 20.0, 20.0, null, null);
            var cheaper = new LongContextTier(1_000L, 20.0, 20.0, 15.0, null);
            var dearer = new LongContextTier(1_000L, 20.0, 20.0, 25.0, null);
            var before = catalog(UnpricedPolicy.ALLOW, new ModelPriceEntry("anthropic", "c", 10.0, 10.0, null, null, unset, null, SRC, null));
            var toCheaper = catalog(UnpricedPolicy.ALLOW, new ModelPriceEntry("anthropic", "c", 10.0, 10.0, null, null, cheaper, null, SRC, null));
            var toDearer = catalog(UnpricedPolicy.ALLOW, new ModelPriceEntry("anthropic", "c", 10.0, 10.0, null, null, dearer, null, SRC, null));
            var result = diff(before, toCheaper);
            assertTrue(result.loosens(), result.changes().toString());
            assertTrue(result.loosening().get(0).contains("long-context cacheRead unset(20.0) -> 15.0"), result.loosening().toString());
            assertFalse(diff(before, toDearer).loosens());
        }

        @Test
        @DisplayName("an unset tier cache price follows the base cache price when the entry sets one")
        void tierCacheFollowsBaseCache() {
            var tier = new LongContextTier(1_000L, 20.0, 20.0, null, null);
            var before = catalog(UnpricedPolicy.ALLOW, new ModelPriceEntry("anthropic", "c", 10.0, 10.0, 5.0, null, tier, null, SRC, null));
            // tier cache read is 5 (the base cache price) before; 6 is a raise
            var after = catalog(UnpricedPolicy.ALLOW,
                    new ModelPriceEntry("anthropic", "c", 10.0, 10.0, 5.0, null, new LongContextTier(1_000L, 20.0, 20.0, 6.0, null), null, SRC,
                            null));
            assertFalse(diff(before, after).loosens());
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

        static Stream<Arguments> additions() {
            var gpt4o = entry("openai", "gpt-4o", 2.5, 10.0);
            var tiered = new ModelPriceEntry("openai", "gpt-4o", 2.5, 10.0, null, null, new LongContextTier(128_000L, 5.0, 20.0, null, null), null,
                    SRC, null);
            // base input 10, tier input 20, no cache prices: its long-context cache reads
            // cost 20
            var tieredNoCache = new ModelPriceEntry("openai", "gpt-4o", 10.0, 10.0, null, null, new LongContextTier(1_000L, 20.0, 20.0, null, null),
                    null, SRC, null);
            return Stream.of(
                    // shape, existing, added, policy in force, loosens
                    Arguments.of("another provider, any price", List.of(gpt4o), entry("anthropic", "claude-x", 0.1, 0.1), UnpricedPolicy.ALLOW,
                            false),
                    Arguments.of("same provider, at least every price there", List.of(gpt4o), entry("openai", "o9", 2.5, 10.0), UnpricedPolicy.ALLOW,
                            false),
                    Arguments.of("exact name cheaper than the wildcard pricing it", List.of(entry("openai", "gpt-4o*", 2.5, 10.0)),
                            entry("openai", "gpt-4o-mini", 0.15, 0.6), UnpricedPolicy.ALLOW, true),
                    // the reported name (a dated snapshot) is matched before the requested one,
                    // so this cheap entry takes over calls gpt-4o prices today
                    Arguments.of("unrelated pattern of the same provider, cheaper", List.of(gpt4o), entry("openai", "gpt-4o-2024*", 0.01, 0.01),
                            UnpricedPolicy.ALLOW, true),
                    Arguments.of("unrelated exact name of the same provider, cheaper", List.of(gpt4o),
                            entry("openai", "my-azure-deployment", 0.01, 20.0),
                            UnpricedPolicy.ALLOW, true),
                    Arguments.of("provider * cheaper than a provider's entry", List.of(gpt4o), entry("*", "*", 0.0, 0.0), UnpricedPolicy.ALLOW, true),
                    Arguments.of("provider entry cheaper than a provider-* entry", List.of(entry("*", "*", 5.0, 20.0)),
                            entry("openai", "o9", 4.0, 30.0),
                            UnpricedPolicy.ALLOW, true),
                    Arguments.of("between the base and the dearer long-context tier of an entry", List.of(tiered),
                            entry("openai", "gpt-4o*", 3.0, 15.0),
                            UnpricedPolicy.ALLOW, true),
                    Arguments.of("its own long-context tier cheaper than an entry",
                            List.of(gpt4o),
                            new ModelPriceEntry("openai", "gpt-4o*", 3.0, 12.0, null, null, new LongContextTier(1_000L, 1.0, 12.0, null, null),
                                    null, SRC, null),
                            UnpricedPolicy.ALLOW, true),
                    Arguments.of("an explicit cache price below an entry's (input) cache price", List.of(gpt4o),
                            new ModelPriceEntry("openai", "gpt-4o*", 3.0, 12.0, 0.3, null, null, null, SRC, null), UnpricedPolicy.ALLOW, true),
                    Arguments.of("a cache price below an entry's unset long-context cache price (its tier input)", List.of(tieredNoCache),
                            new ModelPriceEntry("openai", "o9", 20.0, 20.0, 15.0, 20.0, null, null, SRC, null), UnpricedPolicy.ALLOW, true),
                    Arguments.of("its own unset tier cache price, at its tier input, is not below an entry",
                            List.of(entry("openai", "gpt-4o", 10.0, 10.0)),
                            new ModelPriceEntry("openai", "o9", 10.0, 10.0, null, null, new LongContextTier(1_000L, 20.0, 20.0, null, null), null,
                                    SRC,
                                    null),
                            UnpricedPolicy.ALLOW, false),
                    Arguments.of("an entry without prices next to a priced one", List.of(gpt4o), entry("openai", "gpt-4o*", null, null),
                            UnpricedPolicy.ALLOW,
                            true),
                    Arguments.of("the first entry of an empty catalog", List.of(), entry("openai", "o9", 0.1, 0.1), UnpricedPolicy.ALLOW, false),
                    Arguments.of("any price while unpriced calls are refused", List.of(), entry("openai", "o9", 100.0, 100.0), UnpricedPolicy.REFUSE,
                            true),
                    Arguments.of("any price while unpriced budgeted calls are refused", List.of(gpt4o), entry("anthropic", "c", 100.0, 100.0),
                            UnpricedPolicy.REFUSE_WHEN_BUDGETED, true));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("additions")
        @DisplayName("a new entry")
        void added(String shape, List<ModelPriceEntry> existing, ModelPriceEntry added, UnpricedPolicy policy, boolean loosens) {
            var before = catalog(UnpricedPolicy.ALLOW, existing.toArray(ModelPriceEntry[]::new));
            List<ModelPriceEntry> next = new ArrayList<>(existing);
            next.add(added);
            var result = SpendControlDiff.compareCatalogs(before, catalog(UnpricedPolicy.ALLOW, next.toArray(ModelPriceEntry[]::new)), policy);
            assertEquals(1, result.changes().size(), shape);
            assertTrue(result.changes().get(0).startsWith("added "), shape);
            assertEquals(loosens, result.loosens(), shape + ": " + result.loosening());
        }

        @Test
        @DisplayName("a key differing only by case is a change of that entry, not an addition")
        void caseOnly() {
            var before = catalog(UnpricedPolicy.REFUSE, entry("openai", "gpt-4o", 2.5, 10.0));
            var after = catalog(UnpricedPolicy.REFUSE, entry("OpenAI", "GPT-4O", 3.0, 12.0));
            var result = SpendControlDiff.compareCatalogs(before, after, UnpricedPolicy.REFUSE);
            assertEquals(1, result.changes().size());
            assertTrue(result.changes().get(0).startsWith("changed "), result.changes().toString());
            assertFalse(result.loosens());
        }

        @Test
        @DisplayName("removing an entry and adding a dearer one under another name still loosens")
        void removeAndAdd() {
            var before = catalog(UnpricedPolicy.ALLOW, entry("openai", "gpt-4o", 2.5, 10.0));
            var after = catalog(UnpricedPolicy.ALLOW, entry("openai", "gpt-4o*", 5.0, 20.0));
            var result = diff(before, after);
            assertEquals(2, result.changes().size());
            assertTrue(result.loosening().stream().anyMatch(l -> l.startsWith("removed ")), result.loosening().toString());
        }

        @ParameterizedTest
        @CsvSource({"openai,openai,true", "OpenAI,openai,true", "openai,anthropic,false", "*,anthropic,true", "openai,*,true"})
        @DisplayName("two entries may price one call when the provider matches, whatever their models")
        void sameCall(String a, String b, boolean expected) {
            assertEquals(expected, SpendControlDiff.mayPriceSameCall(entry(a, "x", 1.0, 1.0), entry(b, "y*", 1.0, 1.0)));
            assertEquals(expected, SpendControlDiff.mayPriceSameCall(entry(b, "y*", 1.0, 1.0), entry(a, "x", 1.0, 1.0)));
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
