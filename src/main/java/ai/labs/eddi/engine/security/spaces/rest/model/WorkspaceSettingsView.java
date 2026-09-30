/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces.rest.model;

import java.util.List;

/**
 * The workspace settings in effect, and where each value came from.
 *
 * @param enforcing
 *            whether workspaces are enforced — a startup property, shown here
 *            read-only so the page is complete
 * @param groupsClaim
 *            the JWT claim team membership is read from — startup only
 * @param defaultSpace
 *            the team new resources land in when a request names none; a null
 *            value means the creator's personal space
 * @param legacyVisibility
 *            {@code shared} or {@code admin-only}
 * @param updatedAt
 *            when the stored settings were last written, or null
 * @param updatedBy
 *            who wrote them, or null
 * @param warnings
 *            things an administrator should know about the effective values
 */
public record WorkspaceSettingsView(boolean enforcing, String groupsClaim, Setting defaultSpace, Setting legacyVisibility, String updatedAt,
        String updatedBy, List<String> warnings) {

    /** Where a value comes from. */
    public enum Source {
        /** Set by a property; the API refuses to change it. */
        PINNED,
        /** Written by an administrator. */
        STORED,
        /** Neither — the built-in default. */
        DEFAULT
    }

    /**
     * One effective value.
     *
     * @param value
     *            what applies now
     * @param source
     *            where it came from
     * @param property
     *            the property that pins it — named so an administrator who cannot
     *            change a value knows what to ask an operator for
     */
    public record Setting(String value, Source source, String property) {
    }

    /**
     * What {@code PUT /workspaces/settings} accepts. A null field is unset and
     * falls back to its default.
     *
     * @param defaultSpace
     *            a group name, or {@code team:<group>}; blank or null for personal
     *            spaces
     * @param legacyVisibility
     *            {@code shared}, {@code admin-only}, or null
     */
    public record Update(String defaultSpace, String legacyVisibility) {
    }
}
