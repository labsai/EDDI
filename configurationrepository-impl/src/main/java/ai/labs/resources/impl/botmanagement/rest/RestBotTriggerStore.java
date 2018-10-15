package ai.labs.resources.impl.botmanagement.rest;

import ai.labs.models.BotTriggerConfiguration;
import ai.labs.persistence.IResourceStore;
import ai.labs.resources.rest.botmanagement.IBotTriggerStore;
import ai.labs.resources.rest.botmanagement.IRestBotTriggerStore;
import lombok.extern.slf4j.Slf4j;
import org.jboss.resteasy.spi.NoLogWebApplicationException;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;

/**
 * @author ginccc
 */
@Slf4j
public class RestBotTriggerStore implements IRestBotTriggerStore {
    private final IBotTriggerStore botTriggerStore;

    @Inject
    public RestBotTriggerStore(IBotTriggerStore botTriggerStore) {
        this.botTriggerStore = botTriggerStore;
    }

    @Override
    public BotTriggerConfiguration readBotTrigger(String intent) {
        try {
            return botTriggerStore.readBotTrigger(intent);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(404);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    @Override
    public void updateBotTrigger(String intent, BotTriggerConfiguration botTriggerConfiguration) {
        try {
            botTriggerStore.updateBotTrigger(intent, botTriggerConfiguration);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(404);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    @Override
    public void createBotTrigger(BotTriggerConfiguration botTriggerConfiguration) {
        try {
            botTriggerStore.createBotTrigger(botTriggerConfiguration);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        } catch (IResourceStore.ResourceAlreadyExistsException e) {
            throw new NoLogWebApplicationException(409);
        }
    }

    @Override
    public void deleteBotTrigger(String intent) {
        try {
            botTriggerStore.deleteBotTrigger(intent);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }
}
