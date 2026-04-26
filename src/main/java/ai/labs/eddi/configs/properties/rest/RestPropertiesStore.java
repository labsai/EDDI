/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.properties.rest;

import ai.labs.eddi.configs.properties.IRestPropertiesStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Properties;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * REST endpoint for flat property operations. Delegates to
 * {@link IUserMemoryStore}, which operates on {@code global} entries in the
 * {@code usermemories} collection.
 */
@ApplicationScoped
public class RestPropertiesStore implements IRestPropertiesStore {
    private final IUserMemoryStore userMemoryStore;

    private static final Logger log = Logger.getLogger(RestPropertiesStore.class);

    @Inject
    public RestPropertiesStore(IUserMemoryStore userMemoryStore) {
        this.userMemoryStore = userMemoryStore;
    }

    @Override
    public Properties readProperties(String userId) {
        try {
            return userMemoryStore.readProperties(userId);
        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
            throw new RuntimeException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public Response mergeProperties(String userId, Properties properties) {
        try {
            userMemoryStore.mergeProperties(userId, properties);
            return Response.ok().build();
        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
            throw new RuntimeException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public Response deleteProperties(String userId) {
        try {
            userMemoryStore.deleteProperties(userId);
            return Response.ok().build();
        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
            throw new RuntimeException(e.getLocalizedMessage(), e);
        }
    }
}
