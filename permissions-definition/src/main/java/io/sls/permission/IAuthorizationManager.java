package io.sls.permission;

import io.sls.persistence.IResourceStore;

import java.net.URI;

/**
 * User: jarisch
 * Date: 29.08.12
 * Time: 11:41
 */
public interface IAuthorizationManager {
    boolean isUserAuthorized(String resourceId, Integer version, URI user, IAuthorization.Type authorizationType) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;
}
