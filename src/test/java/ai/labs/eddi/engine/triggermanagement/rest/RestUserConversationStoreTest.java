/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.triggermanagement.rest;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.security.OwnershipValidator;
import jakarta.ws.rs.BadRequestException;
import ai.labs.eddi.engine.triggermanagement.IRestUserConversationStore;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import ai.labs.eddi.engine.model.Deployment;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.List;

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
    @Mock
    private SecurityIdentity identity;
    @Mock
    private OwnershipValidator ownershipValidator;

    private RestUserConversationStore restStore;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        openMocks(this);
        when(cacheFactory.<String, UserConversation>getCache("userConversations")).thenReturn(cache);
        restStore = new RestUserConversationStore(userConversationStore, cacheFactory, identity, ownershipValidator);
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

        /**
         * The ownership guard authorises the PATH userId, but it is the BODY that gets
         * persisted. Without this check a caller authorised for their own path could
         * post a body naming someone else and write that user's mapping — defeating the
         * guard entirely — while the cache entry went in under the path key, so a later
         * read for the path user served the other user's record.
         */
        @Test
        @DisplayName("a body naming a different user is rejected, not persisted")
        void bodyUserIdMustMatchPath() throws Exception {
            UserConversation uc = new UserConversation("intent1", "victim",
                    Deployment.Environment.production, "agent1", "conv1");

            assertThrows(BadRequestException.class,
                    () -> restStore.createUserConversation("intent1", "attacker", uc));

            verify(userConversationStore, never()).createUserConversation(any());
            verify(cache, never()).put(anyString(), any());
        }

        @Test
        @DisplayName("a body naming a different intent is rejected, not persisted")
        void bodyIntentMustMatchPath() throws Exception {
            UserConversation uc = new UserConversation("other-intent", "user1",
                    Deployment.Environment.production, "agent1", "conv1");

            assertThrows(BadRequestException.class,
                    () -> restStore.createUserConversation("intent1", "user1", uc));

            verify(userConversationStore, never()).createUserConversation(any());
            // A regression that skipped the store but still wrote the cache would
            // poison the (intent, user) key with a record for a different intent.
            verify(cache, never()).put(anyString(), any());
        }

        @Test
        @DisplayName("a body that omits them inherits the authorised path values")
        void bodyWithoutIdsInheritsPathValues() throws Exception {
            UserConversation uc = new UserConversation();

            Response response = restStore.createUserConversation("intent1", "user1", uc);

            assertEquals(200, response.getStatus());
            assertEquals("user1", uc.getUserId());
            assertEquals("intent1", uc.getIntent());
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

    // ==================== A6: conversation-id oracle ====================

    @Nested
    @DisplayName("ownership enforcement")
    class OwnershipEnforcementTests {

        /**
         * Mirrors the real validator for a non-admin caller "user1": their own userId
         * passes, any other userId raises ForbiddenException.
         */
        private void denyEveryoneExcept(String ownUserId) {
            doThrow(new ForbiddenException("Access denied: you do not own this user's data"))
                    .when(ownershipValidator).validateUserAccess(same(identity), argThat(id -> !ownUserId.equals(id)));
        }

        @Test
        @DisplayName("read of another user's mapping must be denied before the conversationId is revealed")
        void readDeniesForeignUser() throws Exception {
            denyEveryoneExcept("user1");
            UserConversation victim = new UserConversation("intent1", "victim",
                    Deployment.Environment.production, "agent1", "secret-conversation-id");
            when(cache.get("intent1::victim")).thenReturn(victim);

            assertThrows(ForbiddenException.class, () -> restStore.readUserConversation("intent1", "victim"));
            verify(cache, never()).get("intent1::victim");
            verify(userConversationStore, never()).readUserConversation(anyString(), anyString());
        }

        @Test
        @DisplayName("create for another user must be denied before the store is written")
        void createDeniesForeignUser() throws Exception {
            denyEveryoneExcept("user1");
            UserConversation uc = new UserConversation("intent1", "victim",
                    Deployment.Environment.production, "agent1", "conv1");

            assertThrows(ForbiddenException.class, () -> restStore.createUserConversation("intent1", "victim", uc));
            verify(userConversationStore, never()).createUserConversation(any(UserConversation.class));
            verify(cache, never()).put(anyString(), any(UserConversation.class));
        }

        @Test
        @DisplayName("delete for another user must be denied before the store is touched")
        void deleteDeniesForeignUser() throws Exception {
            denyEveryoneExcept("user1");

            assertThrows(ForbiddenException.class, () -> restStore.deleteUserConversation("intent1", "victim"));
            verify(userConversationStore, never()).deleteUserConversation(anyString(), anyString());
            verify(cache, never()).remove(anyString());
        }

        @Test
        @DisplayName("own mapping is still served")
        void ownUserStillServed() throws Exception {
            denyEveryoneExcept("user1");
            UserConversation uc = new UserConversation("intent1", "user1",
                    Deployment.Environment.production, "agent1", "conv1");
            when(cache.get("intent1::user1")).thenReturn(uc);

            assertSame(uc, restStore.readUserConversation("intent1", "user1"));
        }

        @Test
        @DisplayName("every operation consults the validator with the path userId")
        void everyOperationConsultsValidator() throws Exception {
            restStore.createUserConversation("intent1", "user1", new UserConversation());
            restStore.deleteUserConversation("intent1", "user1");

            verify(ownershipValidator, times(2)).validateUserAccess(identity, "user1");
        }
    }

    // ==================== A6: role gate ====================

    @Test
    @DisplayName("the store must be admin-gated — it hands out live conversation ids")
    void restInterfaceIsAdminOnly() {
        RolesAllowed roles = IRestUserConversationStore.class.getAnnotation(RolesAllowed.class);

        assertNotNull(roles, "/userconversationstore/userconversations must not be role-less");
        assertEquals(List.of("eddi-admin"), List.of(roles.value()));
    }
}
