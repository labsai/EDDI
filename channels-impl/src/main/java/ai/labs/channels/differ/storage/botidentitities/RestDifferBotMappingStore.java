package ai.labs.channels.differ.storage.botidentitities;

import ai.labs.caching.ICache;
import ai.labs.caching.ICacheFactory;
import ai.labs.channels.differ.model.DifferBotMapping;
import ai.labs.channels.differ.storage.IDifferBotMappingStore;
import ai.labs.channels.differ.storage.IRestDifferBotMappingStore;
import ai.labs.persistence.IResourceStore;
import lombok.extern.slf4j.Slf4j;
import org.jboss.resteasy.spi.NoLogWebApplicationException;

import javax.inject.Inject;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.Response;
import java.util.List;

import static javax.ws.rs.core.Response.Status.CREATED;

@Slf4j
public class RestDifferBotMappingStore implements IRestDifferBotMappingStore {
    private final IDifferBotMappingStore differBotMappingStore;
    private final ICache<String, String> availableBotUserIds;

    @Inject
    public RestDifferBotMappingStore(IDifferBotMappingStore differBotMappingStore,
                                     ICacheFactory cacheFactory) {
        this.differBotMappingStore = differBotMappingStore;

        this.availableBotUserIds = cacheFactory.getCache("differ.availableBotUserIds");
    }

    @Override
    public DifferBotMapping readDifferBotMapping(String botIntent) {
        try {
            return differBotMappingStore.readDifferBotMapping(botIntent);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        }
    }

    @Override
    public List<DifferBotMapping> readAllDifferBotMappings() {
        try {
            return differBotMappingStore.readAllDifferBotMappings();
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    @Override
    public Response createDifferBotMapping(DifferBotMapping differBotMapping) {
        try {
            differBotMappingStore.createDifferBotMapping(differBotMapping);
            differBotMapping.getDifferBotUserIds().forEach(differBotUserId -> {
                availableBotUserIds.put(differBotUserId, differBotMapping.getBotIntent());
            });
            return Response.status(CREATED).build();
        } catch (IResourceStore.ResourceAlreadyExistsException e) {
            throw new BadRequestException(e.getLocalizedMessage());
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    @Override
    public void addBotUserToDifferBotMapping(String botIntent, String userId) {
        try {
            differBotMappingStore.addBotUserIdToDifferBotMapping(botIntent, userId);
            availableBotUserIds.put(userId, botIntent);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    @Override
    public void deleteBotUserIdFromDifferBotMappings(String userId) {
        try {
            differBotMappingStore.deleteBotUserIdFromDifferBotMappings(userId);
            availableBotUserIds.remove(userId);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    @Override
    public void deleteDifferBotMapping(String botIntent) {
        try {
            var differBotMapping = differBotMappingStore.readDifferBotMapping(botIntent);
            differBotMappingStore.deleteDifferBotMapping(botIntent);
            differBotMapping.getDifferBotUserIds().forEach(availableBotUserIds::remove);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        }
    }
}
