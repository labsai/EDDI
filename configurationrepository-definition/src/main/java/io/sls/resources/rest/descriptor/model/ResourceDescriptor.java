package io.sls.resources.rest.descriptor.model;

import java.net.URI;
import java.util.Date;

/**
 * Copyright by Spoken Language System. All rights reserved.
 * User: jarisch
 * Date: 18.11.12
 * Time: 17:19
 */
public class ResourceDescriptor {
    private URI resource;
    private URI createdBy;
    private Date createdOn;
    private URI lastModifiedBy;
    private Date lastModifiedOn;
    private boolean deleted;

    public URI getResource() {
        return resource;
    }

    public void setResource(URI resource) {
        this.resource = resource;
    }

    public URI getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(URI createdBy) {
        this.createdBy = createdBy;
    }

    public URI getLastModifiedBy() {
        return lastModifiedBy;
    }

    public void setLastModifiedBy(URI lastModifiedBy) {
        this.lastModifiedBy = lastModifiedBy;
    }

    public Date getCreatedOn() {
        return createdOn;
    }

    public void setCreatedOn(Date createdOn) {
        this.createdOn = createdOn;
    }

    public Date getLastModifiedOn() {
        return lastModifiedOn;
    }

    public void setLastModifiedOn(Date lastModifiedOn) {
        this.lastModifiedOn = lastModifiedOn;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }
}
