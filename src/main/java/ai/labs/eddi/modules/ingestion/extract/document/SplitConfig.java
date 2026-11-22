/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract.document;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * How to cut a document into units, and the budget each unit is fitted to.
 *
 * <p>
 * A value that is not positive falls back to the default, as
 * {@code ExtractionLimits} does: {@link SplitStrategy#AUTO}, 10 pages per unit,
 * 12,000 tokens at most, 500 at least, 60 units. {@code minUnitTokens} may be 0
 * (never merge); one at or above {@code maxUnitTokens} is lowered to half of
 * it. Bounds an operator sets are applied where the configuration is resolved,
 * not here.
 *
 * @param strategy
 *            how to find the units
 * @param pagesPerUnit
 *            pages in one window when cutting by pages
 * @param maxUnitTokens
 *            a unit estimated above this is cut into parts at page boundaries
 * @param minUnitTokens
 *            a unit estimated below this is merged into the one after it (the
 *            one before, at the end of a list)
 * @param maxUnits
 *            the most leaf units a document is cut into; past it the last units
 *            are merged, whatever their size
 */
public record SplitConfig(SplitStrategy strategy, int pagesPerUnit, int maxUnitTokens, int minUnitTokens,
        int maxUnits) {

    public static final int DEFAULT_PAGES_PER_UNIT = 10;
    public static final int DEFAULT_MAX_UNIT_TOKENS = 12_000;
    public static final int DEFAULT_MIN_UNIT_TOKENS = 500;
    public static final int DEFAULT_MAX_UNITS = 60;

    /**
     * The version of the cutting rules, part of {@link #hash()}. Raised whenever
     * the same configuration starts producing different units — when
     * {@link SplitStrategy#HEADINGS} stops meaning {@link SplitStrategy#AUTO}, say
     * — so a manifest cached under the old rules is not taken for one made under
     * the new.
     */
    static final int RULES_VERSION = 1;

    public SplitConfig {
        strategy = strategy == null ? SplitStrategy.AUTO : strategy;
        pagesPerUnit = pagesPerUnit > 0 ? pagesPerUnit : DEFAULT_PAGES_PER_UNIT;
        maxUnitTokens = maxUnitTokens > 0 ? maxUnitTokens : DEFAULT_MAX_UNIT_TOKENS;
        minUnitTokens = minUnitTokens >= 0 ? minUnitTokens : DEFAULT_MIN_UNIT_TOKENS;
        if (minUnitTokens >= maxUnitTokens) {
            minUnitTokens = maxUnitTokens / 2;
        }
        maxUnits = maxUnits > 0 ? maxUnits : DEFAULT_MAX_UNITS;
    }

    /** Every value at its default. */
    public static SplitConfig defaults() {
        return new SplitConfig(SplitStrategy.AUTO, DEFAULT_PAGES_PER_UNIT, DEFAULT_MAX_UNIT_TOKENS,
                DEFAULT_MIN_UNIT_TOKENS, DEFAULT_MAX_UNITS);
    }

    /**
     * Identifies this configuration together with the cutting rules: 16 lower-case
     * hex characters of a SHA-256 over the strategy's name and the four numbers.
     * The same on every node and every run, so it can key a cache and travel with a
     * unit label to say which split the label belongs to.
     */
    public String hash() {
        String canonical = "split|v" + RULES_VERSION + "|" + strategy.name() + "|" + pagesPerUnit + "|"
                + maxUnitTokens + "|" + minUnitTokens + "|" + maxUnits;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every Java runtime", e);
        }
    }
}
