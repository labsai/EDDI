/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.properties.rest;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.security.OwnershipValidator;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Extended branch coverage tests for {@link RestUserMemoryStore}. Focuses on
 * error paths (ResourceStoreException) for every method.
 */
@DisplayName("RestUserMemoryStore Extended Branch Coverage Tests")
class RestUserMemoryStoreBranchTest {

    private IUserMemoryStore store;
    private SecurityIdentity identity;
    private OwnershipValidator ownershipValidator;
    private RestUserMemoryStore rest;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        store = mock(IUserMemoryStore.class);
        identity = mock(SecurityIdentity.class);
        ownershipValidator = mock(OwnershipValidator.class);
        rest = new RestUserMemoryStore(store, identity, ownershipValidator);
    }

    // === getVisibleMemories error path ===

    @Nested
    @DisplayName("getVisibleMemories")
    class GetVisibleMemories {

        @Test
        @DisplayName("should throw InternalServerErrorException on store error")
        void throwsOnStoreError() throws Exception {
            when(store.getVisibleEntries("u1", "a1", List.of(), "most_recent", 50))
                    .thenThrow(new IResourceStore.ResourceStoreException("db fail"));

            assertThrows(InternalServerErrorException.class,
                    () -> rest.getVisibleMemories("u1", "a1", null, "most_recent", 50));
        }

        @Test
        @DisplayName("should pass groupIds directly when not null")
        void passesGroupIds() throws Exception {
            var groups = List.of("g1", "g2");
            when(store.getVisibleEntries("u1", "a1", groups, "most_recent", 50))
                    .thenReturn(List.of());

            var result = rest.getVisibleMemories("u1", "a1", groups, "most_recent", 50);
            assertTrue(result.isEmpty());
            verify(store).getVisibleEntries("u1", "a1", groups, "most_recent", 50);
        }
    }

    // === searchMemories error path ===

    @Nested
    @DisplayName("searchMemories")
    class SearchMemories {

        @Test
        @DisplayName("should throw InternalServerErrorException on store error")
        void throwsOnStoreError() throws Exception {
            when(store.filterEntries("u1", "q"))
                    .thenThrow(new IResourceStore.ResourceStoreException("db fail"));

            assertThrows(InternalServerErrorException.class,
                    () -> rest.searchMemories("u1", "q"));
        }
    }

    // === getMemoriesByCategory error path ===

    @Nested
    @DisplayName("getMemoriesByCategory")
    class GetMemoriesByCategory {

        @Test
        @DisplayName("should throw InternalServerErrorException on store error")
        void throwsOnStoreError() throws Exception {
            when(store.getEntriesByCategory("u1", "cat"))
                    .thenThrow(new IResourceStore.ResourceStoreException("db fail"));

            assertThrows(InternalServerErrorException.class,
                    () -> rest.getMemoriesByCategory("u1", "cat"));
        }
    }

    // === getMemoryByKey error path ===

    @Nested
    @DisplayName("getMemoryByKey")
    class GetMemoryByKey {

        @Test
        @DisplayName("should throw InternalServerErrorException on store error")
        void throwsOnStoreError() throws Exception {
            when(store.getByKey("u1", "key"))
                    .thenThrow(new IResourceStore.ResourceStoreException("db fail"));

            assertThrows(InternalServerErrorException.class,
                    () -> rest.getMemoryByKey("u1", "key"));
        }
    }

    // === upsertMemory error path ===

    @Nested
    @DisplayName("upsertMemory")
    class UpsertMemory {

        @Test
        @DisplayName("should throw InternalServerErrorException on store error")
        void throwsOnStoreError() throws Exception {
            var entry = entry("1", "key", "val");
            when(store.upsert(entry))
                    .thenThrow(new IResourceStore.ResourceStoreException("db fail"));

            assertThrows(InternalServerErrorException.class,
                    () -> rest.upsertMemory(entry));
        }

        @Test
        @DisplayName("successful upsert returns 200")
        void successfulUpsert() throws Exception {
            var entry = entry("1", "key", "val");
            when(store.upsert(entry)).thenReturn("entry-id-1");

            Response response = rest.upsertMemory(entry);
            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("null entry returns 400")
        void nullEntryReturns400() {
            Response response = rest.upsertMemory(null);
            assertEquals(400, response.getStatus());
        }
    }

    // === deleteMemory error paths ===

    @Nested
    @DisplayName("deleteMemory")
    class DeleteMemory {

        @Test
        @DisplayName("should throw InternalServerErrorException on store error in findEntryById")
        void throwsOnFindError() throws Exception {
            when(store.findEntryById("entry-1"))
                    .thenThrow(new IResourceStore.ResourceStoreException("db fail"));

            assertThrows(InternalServerErrorException.class,
                    () -> rest.deleteMemory("entry-1"));
        }

        @Test
        @DisplayName("should throw InternalServerErrorException on store error in deleteEntry")
        void throwsOnDeleteError() throws Exception {
            var memEntry = entry("entry-1", "key", "value");
            when(store.findEntryById("entry-1")).thenReturn(Optional.of(memEntry));
            doThrow(new IResourceStore.ResourceStoreException("db fail"))
                    .when(store).deleteEntry("entry-1");

            assertThrows(InternalServerErrorException.class,
                    () -> rest.deleteMemory("entry-1"));
        }
    }

    // === deleteAllForUser error path ===

    @Nested
    @DisplayName("deleteAllForUser")
    class DeleteAllForUser {

        @Test
        @DisplayName("should throw InternalServerErrorException on store error")
        void throwsOnStoreError() throws Exception {
            doThrow(new IResourceStore.ResourceStoreException("db fail"))
                    .when(store).deleteAllForUser("u1");

            assertThrows(InternalServerErrorException.class,
                    () -> rest.deleteAllForUser("u1"));
        }
    }

    // === countMemories error path ===

    @Nested
    @DisplayName("countMemories")
    class CountMemories {

        @Test
        @DisplayName("should throw InternalServerErrorException on store error")
        void throwsOnStoreError() throws Exception {
            when(store.countEntries("u1"))
                    .thenThrow(new IResourceStore.ResourceStoreException("db fail"));

            assertThrows(InternalServerErrorException.class,
                    () -> rest.countMemories("u1"));
        }
    }

    // === Helper ===

    private UserMemoryEntry entry(String id, String key, Object value) {
        return new UserMemoryEntry(id, "user-1", key, value, "fact",
                Visibility.self, "agent-1", List.of(), "conv-1", false, 0,
                Instant.now(), Instant.now());
    }
}
