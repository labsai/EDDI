/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.client.configuration;

import ai.labs.eddi.engine.runtime.service.ServiceException;

import jakarta.ws.rs.core.Response;
import java.net.URI;

/**
 * @author ginccc
 */
public interface IResourceClientLibrary {
    void init() throws ResourceClientLibraryException;

    <T> T getResource(URI uri, Class<T> clazz) throws ServiceException;

    Response duplicateResource(URI uri) throws ServiceException;

    Response deleteResource(URI uri, boolean permanent) throws ServiceException;

    class ResourceClientLibraryException extends RuntimeException {
        public ResourceClientLibraryException(String message, Exception e) {
            super(message, e);
        }
    }
}
