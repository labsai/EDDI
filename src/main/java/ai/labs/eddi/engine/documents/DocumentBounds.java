/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.documents;

import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The operator bounds on designer {@code documents} values: for a dotted
 * designer path (e.g. {@code sharing.maxDepth}), the highest value an agent
 * designer may set. A value above its bound is refused at save
 * ({@code DocumentsConfigValidator}) and clamped with a warning at deploy
 * ({@link EffectiveDocumentsConfig#resolve}).
 *
 * <p>
 * Read statically through {@link ConfigProvider} because the store validators
 * that need it are static (the
 * {@code TaskToolApprovalsResolver.configuredMode()} precedent). Only fields
 * the documents design introduces are bounded; no existing field is newly
 * clamped.
 * </p>
 */
public final class DocumentBounds {

    /**
     * Designer path → the operator property that bounds it. <b>Append-only</b>:
     * each package that ships a bounded key adds its line in a delimited block.
     */
    static final Map<String, String> BOUND_PROPERTIES = Map.ofEntries(
    // --- WP-0.1: the mechanism only; no key is live, so none is bounded yet ---
    );

    private static final Logger LOGGER = Logger.getLogger(DocumentBounds.class);

    private static final DocumentBounds NONE = new DocumentBounds(Map.of());

    /** Exact decimals: a byte bound above 2^53 must not round to its neighbour. */
    private final Map<String, BigDecimal> bounds;

    private DocumentBounds(Map<String, BigDecimal> bounds) {
        this.bounds = Map.copyOf(bounds);
    }

    /**
     * The bounds this deployment configures. A bound that cannot be parsed, or is
     * negative or not finite, is skipped with a WARN: a mistyped operator property
     * must neither break every LLM configuration save nor clamp values below zero.
     */
    public static DocumentBounds current() {
        if (BOUND_PROPERTIES.isEmpty()) {
            return NONE;
        }
        Map<String, BigDecimal> read = new LinkedHashMap<>();
        var config = ConfigProvider.getConfig();
        BOUND_PROPERTIES.forEach((path, property) -> {
            try {
                config.getOptionalValue(property, String.class).ifPresent(text -> {
                    BigDecimal value = parseBound(text);
                    if (value != null) {
                        read.put(path, value);
                    } else {
                        LOGGER.warnf("%s must be a non-negative number; the bound is ignored", property);
                    }
                });
            } catch (RuntimeException e) {
                LOGGER.warnf("%s cannot be read; the bound is ignored", property);
            }
        });
        return read.isEmpty() ? NONE : new DocumentBounds(read);
    }

    /**
     * Explicit bounds (tests, and callers that already hold the values). The same
     * rule as {@link #current()}: a bound that is negative or not finite is skipped
     * (zero is a valid bound).
     */
    public static DocumentBounds of(Map<String, ? extends Number> bounds) {
        Map<String, BigDecimal> copy = new LinkedHashMap<>();
        bounds.forEach((path, value) -> {
            // toString keeps a Long exact and turns NaN / Infinity into text parseBound
            // refuses
            BigDecimal parsed = value == null ? null : parseBound(value.toString());
            if (parsed != null) {
                copy.put(path, parsed);
            }
        });
        return new DocumentBounds(copy);
    }

    /** A non-negative decimal, exactly as written; null for anything else. */
    private static BigDecimal parseBound(String text) {
        try {
            BigDecimal value = new BigDecimal(text.strip());
            return value.signum() >= 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static DocumentBounds none() {
        return NONE;
    }

    /** The bound on {@code path}, if the operator set one. */
    public Optional<BigDecimal> boundFor(String path) {
        return Optional.ofNullable(bounds.get(path));
    }

    /**
     * A bound as an operator wrote it, for messages ({@code 6000}, {@code 5.5}).
     */
    public static String format(BigDecimal bound) {
        return bound.stripTrailingZeros().toPlainString();
    }

    /** The operator property that bounds {@code path}, for messages. */
    public static String propertyFor(String path) {
        return BOUND_PROPERTIES.getOrDefault(path, "the operator bound");
    }
}
