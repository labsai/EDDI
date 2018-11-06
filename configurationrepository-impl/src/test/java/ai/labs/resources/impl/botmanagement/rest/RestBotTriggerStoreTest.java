package ai.labs.resources.impl.botmanagement.rest;

import ai.labs.caching.ICache;
import ai.labs.caching.ICacheFactory;
import ai.labs.models.BotDeployment;
import ai.labs.models.BotTriggerConfiguration;
import ai.labs.models.Deployment;
import ai.labs.persistence.IResourceStore;
import ai.labs.resources.rest.botmanagement.IBotTriggerStore;
import ai.labs.resources.rest.botmanagement.IRestBotTriggerStore;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class RestBotTriggerStoreTest {
    private ICache<String, BotTriggerConfiguration> botTriggersCache;
    private IRestBotTriggerStore restBotTriggerStore;
    private IBotTriggerStore botTriggerStore;
    //setup
    private String intent = "ask-a-question";

    @Before
    @SuppressWarnings("unchecked")
    public void setup() {
        botTriggerStore = mock(IBotTriggerStore.class);
        botTriggersCache = mock(ICache.class);
        ICacheFactory cacheFactory = mock(ICacheFactory.class);
        when(cacheFactory.getCache(eq("botTriggers"))).thenAnswer(invocation -> botTriggersCache);
        restBotTriggerStore = new RestBotTriggerStore(botTriggerStore, cacheFactory);
    }


    @Test
    public void readBotTrigger_CacheHit() throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        //setup
        final BotTriggerConfiguration expected = createExpectedBotTriggerConfiguration();
        when(botTriggersCache.get(eq(intent))).thenAnswer(invocation -> expected);

        //test
        BotTriggerConfiguration actual = restBotTriggerStore.readBotTrigger(intent);

        //assert
        Assert.assertEquals(expected, actual);
        Mockito.verify(botTriggersCache, Mockito.times(1)).get(eq(intent));
        Mockito.verify(botTriggersCache, Mockito.never()).put(eq(intent), any());
        Mockito.verify(botTriggerStore, Mockito.never()).readBotTrigger(eq(intent));
    }

    @Test
    public void readBotTrigger_CacheMiss() throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        //setup
        final BotTriggerConfiguration expected = createExpectedBotTriggerConfiguration();
        when(botTriggersCache.get(eq(intent))).thenAnswer(invocation -> null);
        when(botTriggerStore.readBotTrigger(eq(intent))).thenAnswer(invocation -> expected);

        //test
        BotTriggerConfiguration actual = restBotTriggerStore.readBotTrigger(intent);

        //assert
        Assert.assertEquals(expected, actual);
        Mockito.verify(botTriggersCache, Mockito.times(1)).get(eq(intent));
        Mockito.verify(botTriggersCache, Mockito.times(1)).put(eq(intent), eq(expected));
        Mockito.verify(botTriggerStore, Mockito.times(1)).readBotTrigger(eq(intent));
    }

    @Test
    public void updateBotTrigger() throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        //setup
        BotTriggerConfiguration expected = createExpectedBotTriggerConfiguration();

        //test
        Response response = restBotTriggerStore.updateBotTrigger(intent, expected);

        //assert
        Assert.assertEquals(200, response.getStatus());
        Mockito.verify(botTriggerStore, times(1)).updateBotTrigger(eq(intent), eq(expected));
        Mockito.verify(botTriggersCache, times(1)).put(eq(intent), eq(expected));
    }

    @Test
    public void createBotTrigger() throws IResourceStore.ResourceStoreException, IResourceStore.ResourceAlreadyExistsException {
        //setup
        BotTriggerConfiguration expected = createExpectedBotTriggerConfiguration();

        //test
        Response response = restBotTriggerStore.createBotTrigger(expected);

        //assert
        Assert.assertEquals(200, response.getStatus());
        Mockito.verify(botTriggerStore, times(1)).createBotTrigger(eq(expected));
        Mockito.verify(botTriggersCache, times(1)).put(eq(intent), eq(expected));
    }

    @Test
    public void createBotTrigger_conflict() throws IResourceStore.ResourceStoreException, IResourceStore.ResourceAlreadyExistsException {
        //setup
        BotTriggerConfiguration expected = createExpectedBotTriggerConfiguration();

        final String errorMessage = "BotTriggerConfiguration with intent=" + intent + " already exists";
        doThrow(new IResourceStore.ResourceAlreadyExistsException(
                errorMessage)).
                when(botTriggerStore).createBotTrigger(eq(expected));

        //test
        boolean exceptionThrown = false;
        try {
            restBotTriggerStore.createBotTrigger(expected);
        } catch (WebApplicationException e) {
            Assert.assertEquals(409, e.getResponse().getStatus());
            exceptionThrown = true;
        }

        //assert
        Assert.assertTrue(exceptionThrown);
        Mockito.verify(botTriggerStore, times(1)).createBotTrigger(eq(expected));
        Mockito.verify(botTriggersCache, never()).put(eq(intent), eq(expected));
    }

    @Test
    public void deleteBotTrigger() throws IResourceStore.ResourceStoreException {
        //test
        Response response = restBotTriggerStore.deleteBotTrigger(intent);

        //assert
        Assert.assertEquals(200, response.getStatus());
        Mockito.verify(botTriggerStore, times(1)).deleteBotTrigger(eq(intent));
        Mockito.verify(botTriggersCache, times(1)).remove(eq(intent));
    }

    private BotTriggerConfiguration createExpectedBotTriggerConfiguration() {
        final BotTriggerConfiguration expected = new BotTriggerConfiguration();
        expected.setIntent(intent);
        BotDeployment expectedBotDeployment = new BotDeployment();
        expectedBotDeployment.setBotId("botId");
        expectedBotDeployment.setEnvironment(Deployment.Environment.unrestricted);
        expected.setBotDeployments(Collections.singletonList(expectedBotDeployment));
        return expected;
    }
}