/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.branding.model;

import com.fasterxml.jackson.annotation.JsonValue;

/** Where the brand currently in effect comes from. */
public enum BrandSource {
    /** The stored brand document has at least one value set. */
    DATABASE("database"),
    /**
     * {@code eddi.branding.*} properties: the seed, or the whole brand when locked.
     */
    PROPERTIES("properties"),
    /** Nothing is set: the EDDI brand. */
    DEFAULT("default");

    private final String json;

    BrandSource(String json) {
        this.json = json;
    }

    @JsonValue
    public String json() {
        return json;
    }
}
