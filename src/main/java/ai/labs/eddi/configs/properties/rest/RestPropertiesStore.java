package ai.labs.eddi.configs.properties.rest;

import ai.labs.eddi.configs.properties.IPropertiesStore;
import ai.labs.eddi.configs.properties.IRestPropertiesStore;
import ai.labs.eddi.configs.properties.model.Properties;
import ai.labs.eddi.datastore.IResourceStore;
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
