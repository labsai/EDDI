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
 *
 * @param redirectUri
 *            what to register at each OAuth provider, derived from
 *            {@code publicBaseUrl}; null while that is not set
 * @param updatedAt
 *            ISO-8601 instant of the last stored write, or null
 * @param updatedBy
 *            principal of the last stored write, or null
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
     */
    public record Setting<T>(T value, Source source, String property) {
    }
}
