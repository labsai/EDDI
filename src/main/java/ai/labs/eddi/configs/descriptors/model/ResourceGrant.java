/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.descriptors.model;

import java.util.Date;

/**
 * One explicit share of one resource with one subject.
 * <p>
 * Grants live on the {@link DocumentDescriptor} rather than in their own
 * collection. That is a deliberate departure from
 * {@link ai.labs.eddi.connections.grants.ConnectionGrant}, which does have its
 * own store, and the reason is the listing path: a separate collection would
 * mean a second query on every descriptor listing, then an {@code id IN (…)}
 * predicate that {@link ai.labs.eddi.datastore.IResourceFilter} cannot express.
 * Keeping grants on the descriptor lets the whole access decision collapse into
 * the single {@code accessIndex} field that listings actually filter on.
 * <p>
 * A grant is a mutable bean rather than a record because the descriptor is
 * deserialised by the same Jackson-based document builder as every other
 * configuration document, which the codebase drives through setters.
 *
 * @author ginccc
 */
public class ResourceGrant {

    private String subject;
    private String level;
    private String grantedBy;
    private Date grantedOn;

    public ResourceGrant() {
    }

    public ResourceGrant(String subject, String level, String grantedBy, Date grantedOn) {
        this.subject = subject;
        this.level = level;
        this.grantedBy = grantedBy;
        this.grantedOn = grantedOn;
    }

    /**
     * The principal or team this grant is for, as a qualified subject —
     * {@code user:<principal>} or {@code team:<group path>}. Built and parsed by
     * {@link ai.labs.eddi.engine.security.spaces.Subjects} so the encoding stays in
     * one place.
     */
    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    /**
     * The {@link AccessLevel} name. Stored as a string so an unknown value from a
     * newer version deserialises rather than failing the whole descriptor.
     */
    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getGrantedBy() {
        return grantedBy;
    }

    public void setGrantedBy(String grantedBy) {
        this.grantedBy = grantedBy;
    }

    public Date getGrantedOn() {
        return grantedOn;
    }

    public void setGrantedOn(Date grantedOn) {
        this.grantedOn = grantedOn;
    }

    /** The parsed level, or {@code null} when unset or unrecognised. */
    public AccessLevel accessLevel() {
        return AccessLevel.parseOrNull(level);
    }
}
