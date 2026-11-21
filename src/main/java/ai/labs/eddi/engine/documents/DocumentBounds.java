/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.documents;

import org.eclipse.microprofile.config.Config;
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

    // --- WP-1.2: converseWithAgent(…, attachments) ---
    /** Bound on {@code sharing.maxFilesPerShare}; built-in 5. */
    public static final String MAX_FILES_PER_SHARE_PROPERTY = "eddi.documents.sharing.max-files-per-share";
    /** Bound on {@code sharing.maxSharesPerTurn}; built-in 10. */
    public static final String MAX_SHARES_PER_TURN_PROPERTY = "eddi.documents.sharing.max-shares-per-turn";
    /** Bound on {@code sharing.maxDepth}; built-in 3. */
    public static final String MAX_DEPTH_PROPERTY = "eddi.documents.sharing.max-depth";
    // --- end WP-1.2 ---

    /**
     * Designer path → the operator property that bounds it. <b>Append-only</b>:
     * each package that ships a bounded key adds its line in a delimited block.
     */
    static final Map<String, String> BOUND_PROPERTIES = Map.ofEntries(
            // --- WP-0.1: the mechanism only; no key is live, so none is bounded yet ---
            // --- WP-1.2: converseWithAgent(…, attachments) ---
            Map.entry("sharing.maxFilesPerShare", MAX_FILES_PER_SHARE_PROPERTY),
            Map.entry("sharing.maxSharesPerTurn", MAX_SHARES_PER_TURN_PROPERTY), Map.entry("sharing.maxDepth", MAX_DEPTH_PROPERTY)
    // --- end WP-1.2 ---
    );

    /**
     * Designer path → the bound that applies when the operator sets no property: a
     * bounded key is never unbounded by omission. <b>Append-only</b>, beside
     * {@link #BOUND_PROPERTIES}; every path here must also be there.
     */
    static final Map<String, String> BOUND_DEFAULTS = Map.ofEntries(
            // --- WP-1.2: converseWithAgent(…, attachments) ---
            Map.entry("sharing.maxFilesPerShare", "5"), Map.entry("sharing.maxSharesPerTurn", "10"), Map.entry("sharing.maxDepth", "3")
    // --- end WP-1.2 ---
    );

    private static final Logger LOGGER = Logger.getLogger(DocumentBounds.class);

    private static final DocumentBounds NONE = new DocumentBounds(Map.of());

    /** Exact decimals: a byte bound above 2^53 must not round to its neighbour. */
    private final Map<String, BigDecimal> bounds;

    private DocumentBounds(Map<String, BigDecimal> bounds) {
        this.bounds = Map.copyOf(bounds);
    }

    /**
     * The bounds this deployment configures; where the operator sets no property,
     * the built-in bound of {@link #BOUND_DEFAULTS}. A bound that cannot be parsed,
     * or is negative or not finite, falls back to the built-in bound with a WARN
     * (or is skipped, for a path without one): a mistyped operator property must
     * neither break every LLM configuration save nor clamp values below zero.
     */
    public static DocumentBounds current() {
        if (BOUND_PROPERTIES.isEmpty()) {
            return NONE;
        }
        Map<String, BigDecimal> read = new LinkedHashMap<>();
        Config config;
        try {
            config = ConfigProvider.getConfig();
        } catch (RuntimeException e) {
            config = null;
        }
        for (var bounded : BOUND_PROPERTIES.entrySet()) {
            String path = bounded.getKey();
            String property = bounded.getValue();
            BigDecimal value = null;
            try {
                var text = config != null ? configured(config, property) : Optional.<String>empty();
                if (text.isPresent()) {
                    value = parseBound(text.get());
                    if (value == null) {
                        LOGGER.warnf("%s must be a non-negative number; the built-in bound applies", property);
                    }
                }
            } catch (RuntimeException e) {
                LOGGER.warnf("%s cannot be read; the built-in bound applies", property);
            }
            if (value == null && BOUND_DEFAULTS.containsKey(path)) {
                value = parseBound(BOUND_DEFAULTS.get(path));
            }
            if (value != null) {
                read.put(path, value);
            }
        }
        return read.isEmpty() ? NONE : new DocumentBounds(read);
    }

    /**
     * The operator's text for one bound property. Each property is looked up by its
     * own constant, so that the configuration reference check
     * ({@code ConfigurationReferenceCoverageTest}) sees what is read.
     * <b>Append-only</b>: a property added to {@link #BOUND_PROPERTIES} needs its
     * case here, or only its built-in bound applies
     * ({@code DocumentSettingsDefaultsTest} sets every property and fails on one
     * that is not read).
     */
    private static Optional<String> configured(Config config, String property) {
        return switch (property) {
            // --- WP-1.2 ---
            case MAX_FILES_PER_SHARE_PROPERTY -> config.getOptionalValue(MAX_FILES_PER_SHARE_PROPERTY, String.class);
            case MAX_SHARES_PER_TURN_PROPERTY -> config.getOptionalValue(MAX_SHARES_PER_TURN_PROPERTY, String.class);
            case MAX_DEPTH_PROPERTY -> config.getOptionalValue(MAX_DEPTH_PROPERTY, String.class);
            // --- end WP-1.2 ---
            default -> Optional.empty();
        };
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
