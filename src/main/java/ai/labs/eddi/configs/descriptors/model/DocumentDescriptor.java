/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.descriptors.model;

import java.util.List;

/**
 * @author ginccc
 */
public class DocumentDescriptor extends ResourceDescriptor {
    private String name;
    private String description;
    private String originId; // resource ID from the exporting instance (for merge import)

    private String ownerId;
    private String spaceId;
    private String visibility;
    private List<ResourceGrant> grants;
    private String accessIndex;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getOriginId() {
        return originId;
    }

    public void setOriginId(String originId) {
        this.originId = originId;
    }

    /**
     * The principal that created this resource, as reported by
     * {@code SecurityIdentity}. Null on resources created before ownership was
     * recorded, and on resources created while authentication is disabled — both of
     * which {@code ResourceAccessGuard} treats as unowned legacy data.
     */
    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    /**
     * The space this resource belongs to: {@code user:<principal>} for a personal
     * space, {@code team:<group>} for a team space, or {@code legacy} for rows the
     * backfill migration stamped.
     */
    public String getSpaceId() {
        return spaceId;
    }

    public void setSpaceId(String spaceId) {
        this.spaceId = spaceId;
    }

    /**
     * The {@link ResourceVisibility} wire name. Stored as a string so an unknown
     * value written by a newer version deserialises instead of failing the
     * descriptor; {@link #resourceVisibility()} does the parsing.
     */
    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }

    /** Explicit shares. Null and empty mean the same thing: no explicit shares. */
    public List<ResourceGrant> getGrants() {
        return grants;
    }

    public void setGrants(List<ResourceGrant> grants) {
        this.grants = grants;
    }

    /**
     * The materialised access-control index — every subject that may see this
     * resource, as pipe-delimited tokens.
     * <p>
     * <b>Derived, never authored.</b> It exists because
     * {@link ai.labs.eddi.datastore.IResourceFilter} can AND groups of filters and
     * OR filters within a group, but cannot nest — so the real policy
     * ({@code owner OR (space AND visibility=space) OR granted OR published}) is
     * not expressible as a query. Collapsing it into one field at write time makes
     * a listing one indexed OR-group.
     * <p>
     * Rebuilt from {@code ownerId}, {@code spaceId}, {@code visibility} and
     * {@code grants} by {@code DescriptorAccess.rebuildIndex} on every write. It is
     * authoritative for <em>listing</em> only; a read of a single resource is
     * decided against the structured fields, so a stale index can hide a resource
     * from a listing but can never expose one.
     */
    public String getAccessIndex() {
        return accessIndex;
    }

    public void setAccessIndex(String accessIndex) {
        this.accessIndex = accessIndex;
    }

    /** The parsed visibility, or {@code null} when unset or unrecognised. */
    public ResourceVisibility resourceVisibility() {
        return ResourceVisibility.parseOrNull(visibility);
    }
}
