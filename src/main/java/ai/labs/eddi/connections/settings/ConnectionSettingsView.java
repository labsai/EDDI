/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.settings;

import java.util.List;

/**
 * The effective connection settings, as {@code GET} and {@code PUT} on
 * {@code /connectionstore/settings} answer them.
 * <p>
 * Each value says where it came from, because "I saved it and nothing changed"
 * has exactly one explanation — the property pins it — and a settings page that
 * cannot say so leaves the administrator to guess.
 * <p>
 * Serialized with EDDI's global {@code NON_NULL} inclusion: a null field is
 * <em>absent</em> from the JSON, not {@code null}. Clients must treat a missing
 * {@code value}, {@code redirectUri}, {@code updatedAt}, {@code updatedBy} or
 * {@code shadowedStoredValue} as unset.
 *
 * @param redirectUri
 *            what to register at each OAuth provider, derived from
 *            {@code publicBaseUrl}; absent while that is not usable
 * @param updatedAt
 *            ISO-8601 instant of the last stored write, or absent
 * @param updatedBy
 *            principal of the last stored write, or absent
 * @param warnings
 *            configurations that save cleanly and still leave part of the
 *            feature unable to work
 */
public record ConnectionSettingsView(Setting<Boolean> enabled, Setting<String> publicBaseUrl, Setting<List<String>> credentialEndpointAllowlist,
        Setting<Boolean> allowPlaintextRemoteOrigins, String redirectUri, String updatedAt, String updatedBy, List<String> warnings) {

    /** Where an effective value came from. */
    public enum Source {
        /** A property or environment variable sets it; writes to it are refused. */
        PINNED,
        /** The stored settings document sets it. */
        STORED,
        /** Nothing sets it; the built-in default applies. */
        DEFAULT
    }

    /**
     * One effective value.
     *
     * @param property
     *            the property that pins it when set — named whatever the source, so
     *            an operator knows which variable would take it out of
     *            administrators' hands
     * @param shadowedStoredValue
     *            for a {@code PINNED} value only: a different value that is stored
     *            and hidden by the pin, which takes effect as soon as the property
     *            is removed; absent otherwise
     */
    public record Setting<T>(T value, Source source, String property, T shadowedStoredValue) {
    }
}
