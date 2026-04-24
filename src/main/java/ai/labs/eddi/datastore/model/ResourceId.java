/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.model;

import ai.labs.eddi.datastore.IResourceStore;

public class ResourceId implements IResourceStore.IResourceId {
    private String id;
    private Integer version;

    public ResourceId(String id, Integer version) {
        this.id = id;
        this.version = version;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        ResourceId that = (ResourceId) o;
        return java.util.Objects.equals(id, that.id) && java.util.Objects.equals(version, that.version);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(id, version);
    }

    @Override
    public String toString() {
        return "ResourceId(" + "id=" + id + ", version=" + version + ")";
    }
}