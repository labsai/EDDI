/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.descriptors.model;

/**
 * Who can reach a configuration resource before any explicit grant is
 * considered.
 * <p>
 * Deliberately <em>not</em> named after
 * {@link ai.labs.eddi.configs.properties.model.Property.Visibility}, whose
 * {@code self / group / global} refers to <em>agent</em> groups and persistent
 * user memory. The two vocabularies sit two packages apart and mean entirely
 * different things; sharing words between them would be a durable source of
 * confusion in configuration, in the API, and in the Manager UI.
 *
 * @author ginccc
 */
public enum ResourceVisibility {

    /** Only the owner, plus whoever holds an explicit grant. */
    privateAccess("private"),

    /**
     * Everyone whose accessible spaces include the resource's space. The default
     * for newly created resources: a personal space keeps it personal, a team space
     * shares it with that team, and neither needs the creator to do anything.
     */
    space("space"),

    /**
     * Everyone with access to this deployment. Use for shared templates and agents
     * meant to be discovered rather than handed out.
     */
    published("published");

    private final String wireName;

    ResourceVisibility(String wireName) {
        this.wireName = wireName;
    }

    /**
     * The name used in JSON and in the access index. {@code private} is a Java
     * keyword, so the constant cannot carry it — this is what closes that gap
     * rather than exposing {@code privateAccess} to API clients.
     */
    public String wireName() {
        return wireName;
    }

    /**
     * Parses a stored or submitted visibility.
     *
     * @return the matching constant, or {@code null} when the value is absent or
     *         unrecognised — callers decide the default rather than inheriting
     *         {@link #published} by accident.
     */
    public static ResourceVisibility parseOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        for (ResourceVisibility visibility : values()) {
            if (visibility.wireName.equalsIgnoreCase(trimmed) || visibility.name().equalsIgnoreCase(trimmed)) {
                return visibility;
            }
        }
        return null;
    }
}
