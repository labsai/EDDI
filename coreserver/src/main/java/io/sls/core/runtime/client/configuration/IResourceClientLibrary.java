package io.sls.core.runtime.client.configuration;

import io.sls.core.runtime.service.ServiceException;

import java.net.URI;

/**
 * @author ginccc
 */
public interface IResourceClientLibrary {
    void init() throws ResourceClientLibrary.ResourceClientLibraryException;

    <T> T getResource(URI uri, Class<T> clazz) throws ServiceException;
}
