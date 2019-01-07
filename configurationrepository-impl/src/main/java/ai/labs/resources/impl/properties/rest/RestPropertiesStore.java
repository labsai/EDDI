package ai.labs.resources.impl.properties.rest;

import ai.labs.persistence.IResourceStore;
import ai.labs.resources.rest.properties.IPropertiesStore;
import ai.labs.resources.rest.properties.IRestPropertiesStore;
import ai.labs.resources.rest.properties.model.Properties;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.Response;

/**
 * @author ginccc
 */
@Slf4j
public class RestPropertiesStore implements IRestPropertiesStore {
    private final IPropertiesStore propertiesStore;

    @Inject
    public RestPropertiesStore(IPropertiesStore propertiesStore) {
        this.propertiesStore = propertiesStore;
    }

    @Override
    public Properties readProperties(String userId) {
        try {
            return propertiesStore.readProperties(userId);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage());
        }
    }

    @Override
    public Response mergeProperties(String userId, Properties properties) {
        try {
            propertiesStore.mergeProperties(userId, properties);
            return Response.ok().build();
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage());
        }
    }

    @Override
    public Response deleteProperties(String userId) {
        try {
            propertiesStore.deleteProperties(userId);
            return Response.noContent().build();
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage());
        }
    }
}
