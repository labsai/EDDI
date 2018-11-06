package ai.labs.resources.impl.botmanagement.rest;

import ai.labs.caching.ICache;
import ai.labs.caching.ICacheFactory;
import ai.labs.models.Deployment;
import ai.labs.models.UserConversation;
import ai.labs.persistence.IResourceStore;
import ai.labs.resources.rest.botmanagement.IRestUserConversationStore;
import ai.labs.resources.rest.botmanagement.IUserConversationStore;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import javax.ws.rs.core.Response;

import static ai.labs.resources.impl.botmanagement.rest.RestUserConversationStore.calculateCacheKey;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class RestUserConversationStoreTest {
    private IRestUserConversationStore restUserConversationStore;
    private final String intent = "ask-a-question";
    private final String userId = "12345";
    private final String botId = "botId";
    private ICache<String, UserConversation> userConversationCache;
    private IUserConversationStore userConversationStore;
    private String conversationId = "67890";

    @Before
    @SuppressWarnings("unchecked")
    public void setup() {
        userConversationStore = mock(IUserConversationStore.class);
        ICacheFactory cacheFactory = mock(ICacheFactory.class);
        userConversationCache = mock(ICache.class);
        when(cacheFactory.getCache(eq("userConversations"))).thenAnswer(invocation -> userConversationCache);
        restUserConversationStore = new RestUserConversationStore(userConversationStore, cacheFactory);
    }

    @Test
    public void readUserConversation_CacheHit() throws IResourceStore.ResourceStoreException {
        //setup
        final String conversationId = "67890";
        final String cacheKey = calculateCacheKey(intent, userId);
        UserConversation expected = new UserConversation(
                intent, userId, Deployment.Environment.unrestricted, botId, conversationId);
        when(userConversationCache.get(eq(cacheKey))).
                then(invocation -> expected);

        //test
        UserConversation actual = restUserConversationStore.readUserConversation(intent, userId);

        //assert
        Assert.assertEquals(expected, actual);
        Mockito.verify(userConversationCache, times(1)).get(eq(cacheKey));
        Mockito.verify(userConversationStore, never()).readUserConversation(eq(intent), eq(userId));
        Mockito.verify(userConversationCache, never()).put(eq(cacheKey), any(UserConversation.class));
    }

    @Test
    public void readUserConversation_CacheMiss() throws IResourceStore.ResourceStoreException {
        //setup
        final String cacheKey = calculateCacheKey(intent, userId);
        UserConversation expected = new UserConversation(
                intent, userId, Deployment.Environment.unrestricted, botId, conversationId);
        when(userConversationStore.readUserConversation(eq(intent), eq(userId))).
                thenAnswer(invocation -> expected);
        when(userConversationCache.get(eq(cacheKey))).
                then(invocation -> null);

        //test
        UserConversation actual = restUserConversationStore.readUserConversation(intent, userId);

        //assert
        Assert.assertEquals(expected, actual);
        Mockito.verify(userConversationCache, times(1)).get(eq(cacheKey));
        Mockito.verify(userConversationStore, times(1)).readUserConversation(eq(intent), eq(userId));
        Mockito.verify(userConversationCache, times(1)).put(eq(cacheKey), eq(expected));
    }

    @Test
    public void readUserConversation_Null() throws IResourceStore.ResourceStoreException {
        //setup
        final String cacheKey = calculateCacheKey(intent, userId);
        when(userConversationStore.readUserConversation(eq(intent), eq(userId))).
                thenAnswer(invocation -> null);
        when(userConversationCache.get(eq(cacheKey))).
                then(invocation -> null);

        //test
        UserConversation actual = restUserConversationStore.readUserConversation(intent, userId);

        //assert
        Assert.assertNull(actual);
        Mockito.verify(userConversationCache, times(1)).get(eq(cacheKey));
        Mockito.verify(userConversationStore, times(1)).readUserConversation(eq(intent), eq(userId));
        Mockito.verify(userConversationCache, never()).put(eq(cacheKey), any());
    }

    @Test
    public void createUserConversation() throws IResourceStore.ResourceStoreException, IResourceStore.ResourceAlreadyExistsException {
        //setup
        UserConversation expected = new UserConversation(intent, userId,
                Deployment.Environment.unrestricted, botId, conversationId);

        //test
        Response response = restUserConversationStore.createUserConversation(intent, userId, expected);

        //assert
        Assert.assertEquals(200, response.getStatus());
        Mockito.verify(userConversationStore, times(1)).createUserConversation(eq(expected));
        Mockito.verify(userConversationCache, times(1)).put(eq(calculateCacheKey(intent, userId)), eq(expected));
    }

    @Test
    public void deleteUserConversation() throws IResourceStore.ResourceStoreException {
        //test
        Response response = restUserConversationStore.deleteUserConversation(intent, userId);

        //assert
        Assert.assertEquals(200, response.getStatus());
        Mockito.verify(userConversationStore, times(1)).deleteUserConversation(eq(intent), eq(userId));
        Mockito.verify(userConversationCache, times(1)).remove(eq(calculateCacheKey(intent, userId)));
    }
}