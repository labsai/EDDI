/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.documents;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.ConfigProvider;

import java.util.Map;
import java.util.Optional;
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
}
