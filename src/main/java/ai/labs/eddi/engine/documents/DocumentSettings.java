/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.documents;

import ai.labs.eddi.engine.attachments.DerivedCaps;
import ai.labs.eddi.engine.documents.spend.DocumentSpendLimits;
import ai.labs.eddi.modules.ingestion.extract.PdfWindowReader;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

import java.time.Duration;
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

    // --- WP-4.2: windowed reading of large PDFs ---

    /**
     * Pages one window of a large PDF holds
     * ({@code eddi.documents.structure.window-pages}, default 50): the document is
     * opened afresh per window, and a crash loses at most one. Values below 1 fall
     * back to the default. Explicit settings return the default.
     */
    public int windowPages() {
        if (bounds != null) {
            return PdfWindowReader.DEFAULT_WINDOW_PAGES;
        }
        int pages = ConfigProvider.getConfig().getOptionalValue("eddi.documents.structure.window-pages", Integer.class)
                .orElse(PdfWindowReader.DEFAULT_WINDOW_PAGES);
        return pages > 0 ? pages : PdfWindowReader.DEFAULT_WINDOW_PAGES;
    }

    /**
     * How long one window may take
     * ({@code eddi.documents.structure.window-deadline-seconds}, default 90).
     * Values below 1 fall back to the default. Explicit settings return the
     * default.
     */
    public Duration windowDeadline() {
        if (bounds != null) {
            return PdfWindowReader.DEFAULT_WINDOW_DEADLINE;
        }
        long seconds = ConfigProvider.getConfig()
                .getOptionalValue("eddi.documents.structure.window-deadline-seconds", Long.class)
                .orElse(PdfWindowReader.DEFAULT_WINDOW_DEADLINE.toSeconds());
        return seconds > 0 ? Duration.ofSeconds(seconds) : PdfWindowReader.DEFAULT_WINDOW_DEADLINE;
    }

    // --- WP-6.1: the deployment's default document index ---

    /**
     * The deployment's default document index: a RAG configuration id or URI,
     * validated at startup ({@code DocumentIndexDefaultCheck}) and before every
     * write. Unset by default: without it, and without a task's
     * {@code documents.index.rag}, document search stays lexical.
     */
    public static final String INDEX_DEFAULT_RAG = "eddi.documents.index.default-rag";

    /** {@link #INDEX_DEFAULT_RAG}, when set to something other than blank. */
    public Optional<String> indexDefaultRag() {
        return ConfigProvider.getConfig().getOptionalValue(INDEX_DEFAULT_RAG, String.class).map(String::strip).filter(value -> !value.isEmpty());
    }

    // --- WP-2b.2a: the pixel cap on embedded images ---

    /** The most pixels one embedded image may declare before it is decoded. */
    public static final String IMAGES_MAX_PIXELS = "eddi.documents.images.max-pixels";

    /** {@link #IMAGES_MAX_PIXELS} unless set: 40 megapixels. */
    public static final long DEFAULT_IMAGES_MAX_PIXELS = 40_000_000L;

    /**
     * {@link #IMAGES_MAX_PIXELS}: an image that declares more pixels, or a mask
     * that does, is refused before anything is allocated for it. A CDI instance
     * reads the deployment's configuration on every call, and a value that is not a
     * positive number falls back to the default; explicit settings ({@link #of},
     * {@link #none}) return the default.
     */
    public long imagesMaxPixels() {
        if (bounds != null) {
            return DEFAULT_IMAGES_MAX_PIXELS;
        }
        try {
            return ConfigProvider.getConfig().getOptionalValue("eddi.documents.images.max-pixels", Long.class)
                    .filter(pixels -> pixels > 0).orElse(DEFAULT_IMAGES_MAX_PIXELS);
        } catch (IllegalArgumentException notANumber) {
            return DEFAULT_IMAGES_MAX_PIXELS;
        }
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
        return new DocumentSpendLimits(usd(config.getOptionalValue(DocumentSpendLimits.MAX_PER_USER_PER_DAY, Double.class)),
                usd(config.getOptionalValue(DocumentSpendLimits.MAX_PER_TEAM_PER_DAY, Double.class)),
                config.getOptionalValue(DocumentSpendLimits.RETENTION_DAYS, Integer.class).orElse(DocumentSpendLimits.DEFAULT_RETENTION_DAYS));
    }

    private static OptionalDouble usd(Optional<Double> value) {
        return value.map(OptionalDouble::of).orElse(OptionalDouble.empty());
    }
}
