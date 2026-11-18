/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.documents;

import org.eclipse.microprofile.config.ConfigProvider;

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

    private static final DocumentBounds NONE = new DocumentBounds(Map.of());

    private final Map<String, Double> bounds;

    private DocumentBounds(Map<String, Double> bounds) {
        this.bounds = Map.copyOf(bounds);
    }

    /** The bounds this deployment configures. */
    public static DocumentBounds current() {
        Map<String, Double> read = new LinkedHashMap<>();
        var config = ConfigProvider.getConfig();
        BOUND_PROPERTIES.forEach((path, property) -> config.getOptionalValue(property, Double.class)
                .ifPresent(value -> read.put(path, value)));
        return read.isEmpty() ? NONE : new DocumentBounds(read);
    }

    /** Explicit bounds (tests, and callers that already hold the values). */
    public static DocumentBounds of(Map<String, ? extends Number> bounds) {
        Map<String, Double> copy = new LinkedHashMap<>();
        bounds.forEach((path, value) -> copy.put(path, value.doubleValue()));
        return new DocumentBounds(copy);
    }

    public static DocumentBounds none() {
        return NONE;
    }

    /** The bound on {@code path}, if the operator set one. */
    public Optional<Double> boundFor(String path) {
        return Optional.ofNullable(bounds.get(path));
    }

    /** The operator property that bounds {@code path}, for messages. */
    public static String propertyFor(String path) {
        return BOUND_PROPERTIES.getOrDefault(path, "the operator bound");
    }
}
