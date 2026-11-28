/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.descriptors.model;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * @author ginccc
 */
public class DocumentDescriptor extends ResourceDescriptor {
    /** See {@link #recordUnmodeledField}. */
    private static final int MAX_RECORDED_UNMODELED_FIELDS = 100;

    /**
     * The {@link JsonProperty.Access#READ_ONLY} properties, which reach
     * {@link #recordUnmodeledField} when a body carries them.
     */
    private static final Set<String> READ_ONLY_PROPERTIES = Set.of("callerLevel", "ownerName");

    private String name;
    private String description;
    private String originId; // resource ID from the exporting instance (for merge import)
    /**
     * The version a sync or an import last wrote. A current version beyond it means
     * the resource was edited on this instance since, which is what lets a sync
     * tell a local hotfix from a copy it may overwrite. Null for a resource no sync
     * or import has written — such a resource has no baseline and is never reported
     * as changed locally.
     */
    private Integer syncedVersion;

    private String ownerId;
    private String spaceId;
    private String visibility;
    private List<ResourceGrant> grants;
    private String accessIndex;
    private String callerLevel;
    private String ownerName;

    /**
     * Keys a JSON body carried that this model does not declare — never serialised,
     * never stored. See {@link #unmodeledFields()}.
     */
    @JsonIgnore
    private transient Set<String> unmodeledFields;

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

    public Integer getSyncedVersion() {
        return syncedVersion;
    }

    public void setSyncedVersion(Integer syncedVersion) {
        this.syncedVersion = syncedVersion;
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

    /**
     * What the <em>calling</em> user may do with this resource — {@code USE},
     * {@code VIEW}, {@code EDIT} or {@code OWN} — or {@code null} when workspaces
     * are not being enforced.
     *
     * <h3>Per-request, not per-resource</h3> Every other field here describes the
     * resource. This one describes the relationship between the resource and
     * whoever asked, so the same document serialises differently for two callers.
     * It exists because a client cannot work it out: the grant list is disclosed at
     * {@code OWN} only, so a listing gives a recipient no way to tell whether they
     * may edit a row, delete it, or merely converse with it — and the alternative
     * is offering every action and letting the server refuse, which teaches the
     * user the product is broken rather than that the resource is not theirs.
     *
     * <h3>Derived, and deliberately hard to persist by accident</h3> It is stamped
     * on the way out by {@code ResourceAccessGuard} and is <b>never</b> stored:
     * {@code PersistenceMapperProducer} registers a mix-in that drops it, so a
     * descriptor that is read, stamped and written back cannot carry one caller's
     * access level into another caller's read. It is also {@code READ_ONLY}, so
     * nothing a client sends can set it.
     * <p>
     * Null when enforcement is off, rather than {@code OWN}. Everyone may do
     * everything in that state, so a level would be true but meaningless — and
     * omitting it keeps the wire identical to a deployment that has never heard of
     * workspaces, which is the property this whole feature is built to preserve.
     */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public String getCallerLevel() {
        return callerLevel;
    }

    public void setCallerLevel(String callerLevel) {
        this.callerLevel = callerLevel;
    }

    /**
     * The owner as a person would recognise them — their name from the user
     * directory — or {@code null} when the directory does not know them or
     * workspaces are not enforced.
     * <p>
     * Derived per request exactly like {@link #getCallerLevel()}, and for the same
     * reasons never stored and never accepted: a name is a property of the
     * directory, not of the resource, and a stale copy on the document would keep
     * showing somebody's old name after they changed it.
     */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    /**
     * Records a key the JSON being read carried and this model does not declare.
     * Only the key is kept: the value is dropped exactly as before, so a stored
     * descriptor with legacy fields still loads unchanged. Capped, so a body made
     * of nothing but unknown keys cannot grow it without bound.
     */
    @JsonAnySetter
    void recordUnmodeledField(String key, Object ignoredValue) {
        if (READ_ONLY_PROPERTIES.contains(key)) {
            // Declared, but READ_ONLY: Jackson hands a read-only property to the any
            // setter rather than dropping it. It is no unknown key — a client echoing a
            // GET back carries it — and it is discarded exactly as before.
            return;
        }
        if (unmodeledFields == null) {
            unmodeledFields = new LinkedHashSet<>();
        }
        if (unmodeledFields.size() < MAX_RECORDED_UNMODELED_FIELDS) {
            unmodeledFields.add(key);
        }
    }

    /**
     * The keys a deserialised body carried that this model does not declare, in the
     * order they arrived; empty for a descriptor built in Java.
     * <p>
     * The REST layer reads JSON with unknown properties ignored, which is right for
     * stored documents and wrong for a write: {@code PATCH
     * /descriptorstore/descriptors/{id}} answered 204 to a body setting
     * {@code resourceVisibility}, a name no client can set anything with. This is
     * how that endpoint can tell such a body from one naming only what it applies.
     * Not a getter, so it is never part of the wire or the stored document.
     */
    public Set<String> unmodeledFields() {
        return unmodeledFields == null ? Set.of() : Collections.unmodifiableSet(unmodeledFields);
    }

    /** The parsed caller level, or {@code null} when unset or unrecognised. */
    public AccessLevel callerAccessLevel() {
        return AccessLevel.parseOrNull(callerLevel);
    }

    /** The parsed visibility, or {@code null} when unset or unrecognised. */
    public ResourceVisibility resourceVisibility() {
        return ResourceVisibility.parseOrNull(visibility);
    }
}
