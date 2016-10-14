package io.sls.persistence.impl.resources.rest;

import io.sls.persistence.IResourceStore;
import io.sls.resources.rest.IRestVersionInfo;

import javax.ws.rs.NotFoundException;

/**
 * User: jarisch
 * Date: 17.08.12
 * Time: 15:09
 */
public abstract class RestVersionInfo implements IRestVersionInfo {
    @Override
    public Integer getCurrentVersion(String id) {
        try {
            IResourceStore.IResourceId currentResourceId = getCurrentResourceId(id);
            return currentResourceId.getVersion();
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage(), e);
        }
    }

    protected abstract IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException;
}
