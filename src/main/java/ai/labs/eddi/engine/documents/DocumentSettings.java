/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.documents;

import ai.labs.eddi.engine.attachments.DerivedCaps;
import ai.labs.eddi.engine.documents.spend.DocumentSpendLimits;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Function;

/**
 * The operator properties the documents feature reads at runtime.
 *
 * <p>
 * <b>Deployment defaults</b> follow one convention:
 * {@code eddi.documents.defaults.<path>}, where {@code <path>} is the dotted
 * designer path of the agent's {@code documents} block — e.g.
 * {@code eddi.documents.defaults.split.maxUnitTokens=8000}. A default applies
 * where the designer left the key unset, and only to a key that is available in
 * this version. <b>Bounds</b> come from {@link DocumentBounds}.
 * </p>
 *
 * <p>
 * Append-only: each package that adds an operator property for documents adds
 * its accessor here in a delimited block.
 * </p>
 */
@ApplicationScoped
public class DocumentSettings {

    /** Prefix of every deployment default for a designer key. */
    public static final String DEFAULTS_PREFIX = "eddi.documents.defaults.";

    private static final DocumentSettings NONE = new DocumentSettings(path -> Optional.empty(), DocumentBounds.none());

    private Function<String, Optional<String>> defaults;
    private DocumentBounds bounds;

    /** CDI: the deployment's configuration. */
    public DocumentSettings() {
        this(path -> ConfigProvider.getConfig().getOptionalValue(DEFAULTS_PREFIX + path, String.class), null);
    }

    private DocumentSettings(Function<String, Optional<String>> defaults, DocumentBounds bounds) {
        this.defaults = defaults;
        this.bounds = bounds;
    }

    /**
     * Explicit defaults (keyed by designer path, without the prefix) and bounds.
     */
    public static DocumentSettings of(Map<String, String> defaults, DocumentBounds bounds) {
        Map<String, String> copy = Map.copyOf(defaults);
        return new DocumentSettings(path -> Optional.ofNullable(copy.get(path)), bounds);
    }

    /** No operator default and no bound: the built-in defaults apply. */
    public static DocumentSettings none() {
        return NONE;
    }

    /** The operator default for a designer path, as configured (unparsed). */
    public Optional<String> defaultFor(String path) {
        String value = defaults.apply(path).map(String::strip).orElse(null);
        return value == null || value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    /**
     * The operator bounds. A CDI instance reads them <b>on every call</b>, never
     * caching: the save validator reads {@link DocumentBounds#current()} fresh on
     * every save, so a deploy must see the same values a save would (a resolution
     * reads them once, at its start). Explicit settings ({@link #of}) return the
     * bounds they were given.
     */
    public DocumentBounds bounds() {
        return bounds != null ? bounds : DocumentBounds.current();
    }

    // --- WP-0.1: the mechanism only; later packages append below ---

    // --- WP-2a.2: per-source caps on derived blobs ---

    /**
     * The per-source caps on derived blobs ({@code eddi.attachments.derived.*}).
     * The attachment stores enforce them, reading the same properties; this is for
     * runtime code that wants to know a cap before it produces a view. A CDI
     * instance reads the deployment's configuration on every call; explicit
     * settings ({@link #of}, {@link #none}) return the built-in defaults.
     */
    public DerivedCaps.Limits derivedCaps() {
        if (bounds != null) {
            return DerivedCaps.Limits.defaults();
        }
        Config config = ConfigProvider.getConfig();
        return new DerivedCaps.Limits(
                derived(config, "eddi.attachments.derived.max-per-source", DerivedCaps.DEFAULT_MAX_PER_SOURCE),
                derived(config, "eddi.attachments.derived.max-total-bytes-per-source", DerivedCaps.DEFAULT_MAX_TOTAL_BYTES_PER_SOURCE),
                derived(config, "eddi.attachments.derived.max-slices-per-source", DerivedCaps.DEFAULT_MAX_SLICES_PER_SOURCE),
                derived(config, "eddi.attachments.derived.max-slice-bytes-per-source", DerivedCaps.DEFAULT_MAX_SLICE_BYTES_PER_SOURCE),
                derived(config, "eddi.attachments.derived.max-text-bytes-per-source", DerivedCaps.DEFAULT_MAX_TEXT_BYTES_PER_SOURCE));
    }

    private static long derived(Config config, String property, long fallback) {
        return config.getOptionalValue(property, Long.class).orElse(fallback);
    }

    // --- WP-4b.1: daily document spend ceilings ---

    /**
     * The cumulative daily ceilings and the bucket retention
     * ({@code eddi.documents.spend.*}). A CDI instance reads the deployment's
     * configuration on every call; explicit settings ({@link #of}, {@link #none})
     * return the built-in defaults (no ceiling, 35 days).
     */
    public DocumentSpendLimits spendLimits() {
        if (bounds != null) {
            return DocumentSpendLimits.defaults();
        }
        Config config = ConfigProvider.getConfig();
        return new DocumentSpendLimits(usd(config, DocumentSpendLimits.MAX_PER_USER_PER_DAY), usd(config, DocumentSpendLimits.MAX_PER_TEAM_PER_DAY),
                config.getOptionalValue(DocumentSpendLimits.RETENTION_DAYS, Integer.class).orElse(DocumentSpendLimits.DEFAULT_RETENTION_DAYS));
    }

    private static OptionalDouble usd(Config config, String property) {
        return config.getOptionalValue(property, Double.class).map(OptionalDouble::of).orElse(OptionalDouble.empty());
    }
}
