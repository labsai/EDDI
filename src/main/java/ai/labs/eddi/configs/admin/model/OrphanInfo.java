/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.admin.model;

import java.net.URI;

/**
 * Represents an orphaned resource that is not referenced by any Agent or
 * workflow.
 */
public class OrphanInfo {
    private URI resourceUri;
    private String type; // e.g., "ai.labs.workflow", "ai.labs.rules"
    private String name; // human-readable name from descriptor
    private boolean deleted; // soft-deleted?

    public OrphanInfo() {
    }

    public OrphanInfo(URI resourceUri, String type, String name, boolean deleted) {
        this.resourceUri = resourceUri;
        this.type = type;
        this.name = name;
        this.deleted = deleted;
    }

    public URI getResourceUri() {
        return resourceUri;
    }

    public void setResourceUri(URI resourceUri) {
        this.resourceUri = resourceUri;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }
}
