/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.triggermanagement.rest;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import ai.labs.eddi.engine.model.Deployment;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

@DisplayName("RestUserConversationStore Tests")
class RestUserConversationStoreTest {

    @Mock
    private IUserConversationStore userConversationStore;
    @Mock
    private ICacheFactory cacheFactory;
    @Mock
    private ICache<String, UserConversation> cache;

    private RestUserConversationStore restStore;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        openMocks(this);
        when(cacheFactory.<String, UserConversation>getCache("userConversations")).thenReturn(cache);
        restStore = new RestUserConversationStore(userConversationStore, cacheFactory);
    }

    // ==================== calculateCacheKey ====================

    @Nested
    @DisplayName("calculateCacheKey")
    class CalculateCacheKeyTests {

        @Test
        @DisplayName("should combine intent and userId with :: separator")
        void combinesIntentAndUserId() {
            String key = RestUserConversationStore.calculateCacheKey("greeting", "user-1");
            assertEquals("greeting::user-1", key);
        }

        @Test
        @DisplayName("should handle null intent")
        void nullIntent() {
            String key = RestUserConversationStore.calculateCacheKey(null, "user-1");
            assertEquals("null::user-1", key);
        }
    }

    // ==================== readUserConversation ====================

    @Nested
    @DisplayName("readUserConversation")
    class ReadUserConversationTests {

        @Test
        @DisplayName("should return cached conversation when available")
        void returnsCachedConversation() throws Exception {
            UserConversation uc = new UserConversation("intent1", "user1",
                    Deployment.Environment.production, "agent1", "conv1");
            when(cache.get("intent1::user1")).thenReturn(uc);

            UserConversation result = restStore.readUserConversation("intent1", "user1");

            assertSame(uc, result);
            verify(userConversationStore, never()).readUserConversation(anyString(), anyString());
        }

        @Test
        @DisplayName("should fetch from store and cache when not in cache")
        void fetchesFromStoreAndCaches() throws Exception {
            UserConversation uc = new UserConversation("intent1", "user1",
                    Deployment.Environment.production, "agent1", "conv1");
            when(cache.get("intent1::user1")).thenReturn(null);
            when(userConversationStore.readUserConversation("intent1", "user1")).thenReturn(uc);

            UserConversation result = restStore.readUserConversation("intent1", "user1");

            assertSame(uc, result);
            verify(cache).put("intent1::user1", uc);
        }

        @Test
        @DisplayName("should throw ResourceNotFoundException when not in cache or store")
        void throwsWhenNotFound() throws Exception {
            when(cache.get("intent1::user1")).thenReturn(null);
            when(userConversationStore.readUserConversation("intent1", "user1")).thenReturn(null);

            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> restStore.readUserConversation("intent1", "user1"));
        }

        @Test
        @DisplayName("should not cache when store returns null")
        void doesNotCacheNull() throws Exception {
            when(cache.get("intent1::user1")).thenReturn(null);
            when(userConversationStore.readUserConversation("intent1", "user1")).thenReturn(null);

            try {
                restStore.readUserConversation("intent1", "user1");
            } catch (Exception ignored) {
            }

            verify(cache, never()).put(anyString(), any(UserConversation.class));
        }

        @Test
        @DisplayName("should propagate ResourceStoreException from store")
        void propagatesStoreException() throws Exception {
            when(cache.get("intent1::user1")).thenReturn(null);
            when(userConversationStore.readUserConversation("intent1", "user1"))
                    .thenThrow(new IResourceStore.ResourceStoreException("DB error"));

            assertThrows(IResourceStore.ResourceStoreException.class,
                    () -> restStore.readUserConversation("intent1", "user1"));
        }
    }

    // ==================== createUserConversation ====================

    @Nested
    @DisplayName("createUserConversation")
    class CreateUserConversationTests {

        @Test
        @DisplayName("should create and cache, returning 200")
        void createsAndCaches() throws Exception {
            UserConversation uc = new UserConversation("intent1", "user1",
                    Deployment.Environment.production, "agent1", "conv1");

            Response response = restStore.createUserConversation("intent1", "user1", uc);

            assertEquals(200, response.getStatus());
            verify(userConversationStore).createUserConversation(uc);
            verify(cache).put("intent1::user1", uc);
        }

        @Test
        @DisplayName("should propagate ResourceAlreadyExistsException")
        void propagatesAlreadyExists() throws Exception {
            UserConversation uc = new UserConversation();
            doThrow(new IResourceStore.ResourceAlreadyExistsException("exists"))
                    .when(userConversationStore).createUserConversation(uc);

            assertThrows(IResourceStore.ResourceAlreadyExistsException.class,
                    () -> restStore.createUserConversation("intent1", "user1", uc));
        }

        @Test
        @DisplayName("should propagate ResourceStoreException")
        void propagatesStoreException() throws Exception {
            UserConversation uc = new UserConversation();
            doThrow(new IResourceStore.ResourceStoreException("DB error"))
                    .when(userConversationStore).createUserConversation(uc);

            assertThrows(IResourceStore.ResourceStoreException.class,
                    () -> restStore.createUserConversation("intent1", "user1", uc));
        }
    }

    // ==================== deleteUserConversation ====================

    @Nested
    @DisplayName("deleteUserConversation")
    class DeleteUserConversationTests {

        @Test
        @DisplayName("should delete from store and remove from cache, returning 200")
        void deletesAndRemovesCache() throws Exception {
            Response response = restStore.deleteUserConversation("intent1", "user1");

            assertEquals(200, response.getStatus());
            verify(userConversationStore).deleteUserConversation("intent1", "user1");
            verify(cache).remove("intent1::user1");
        }

        @Test
        @DisplayName("should propagate ResourceStoreException from delete")
        void propagatesStoreException() throws Exception {
            doThrow(new IResourceStore.ResourceStoreException("DB error"))
                    .when(userConversationStore).deleteUserConversation("intent1", "user1");

            assertThrows(IResourceStore.ResourceStoreException.class,
                    () -> restStore.deleteUserConversation("intent1", "user1"));
        }
    }
}
