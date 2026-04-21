package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.api.IRestGroupConversation.DiscussRequest;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestGroupConversation}.
 */
class RestGroupConversationTest {

    private IGroupConversationService groupService;
    private IJsonSerialization jsonSerialization;
    private RestGroupConversation restGroupConversation;

    @BeforeEach
    void setUp() {
        groupService = mock(IGroupConversationService.class);
        jsonSerialization = mock(IJsonSerialization.class);
        restGroupConversation = new RestGroupConversation(groupService, jsonSerialization);
    }

    @Nested
    @DisplayName("discuss")
    class Discuss {

        @Test
        @DisplayName("should return 201 with location header on success")
        void successfulDiscussion() throws Exception {
            var gc = new GroupConversation();
            gc.setId("gc-1");
            when(groupService.discuss("group-1", "What is AI?", "user-1", 0)).thenReturn(gc);

            Response response = restGroupConversation.discuss("group-1",
                    new DiscussRequest("What is AI?", "user-1"));

            assertEquals(201, response.getStatus());
            assertNotNull(response.getLocation());
            assertTrue(response.getLocation().toString().contains("gc-1"));
        }

        @Test
        @DisplayName("should use 'anonymous' when userId is null")
        void anonymousUser() throws Exception {
            var gc = new GroupConversation();
            gc.setId("gc-2");
            when(groupService.discuss("group-1", "Hello", "anonymous", 0)).thenReturn(gc);

            Response response = restGroupConversation.discuss("group-1",
                    new DiscussRequest("Hello", null));

            assertEquals(201, response.getStatus());
            verify(groupService).discuss("group-1", "Hello", "anonymous", 0);
        }

        @Test
        @DisplayName("should return 400 for GroupDepthExceededException")
        void depthExceeded() throws Exception {
            when(groupService.discuss(anyString(), anyString(), anyString(), anyInt()))
                    .thenThrow(new IGroupConversationService.GroupDepthExceededException("Max depth reached"));

            Response response = restGroupConversation.discuss("group-1",
                    new DiscussRequest("Question", "user-1"));

            assertEquals(400, response.getStatus());
        }

        @Test
        @DisplayName("should return 404 for ResourceNotFoundException")
        void groupNotFound() throws Exception {
            when(groupService.discuss(anyString(), anyString(), anyString(), anyInt()))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("Group not found"));

            Response response = restGroupConversation.discuss("nonexistent",
                    new DiscussRequest("Question", "user-1"));

            assertEquals(404, response.getStatus());
        }

        @Test
        @DisplayName("should propagate unexpected exceptions")
        void unexpectedException() throws Exception {
            when(groupService.discuss(anyString(), anyString(), anyString(), anyInt()))
                    .thenThrow(new RuntimeException("Unexpected"));

            assertThrows(RuntimeException.class,
                    () -> restGroupConversation.discuss("group-1",
                            new DiscussRequest("Q", "u")));
        }
    }

    @Nested
    @DisplayName("readGroupConversation")
    class ReadGroupConversation {

        @Test
        @DisplayName("should return group conversation")
        void success() throws Exception {
            var gc = new GroupConversation();
            gc.setId("gc-1");
            when(groupService.readGroupConversation("gc-1")).thenReturn(gc);

            GroupConversation result = restGroupConversation.readGroupConversation("group-1", "gc-1");

            assertEquals("gc-1", result.getId());
        }

        @Test
        @DisplayName("should propagate ResourceNotFoundException")
        void notFound() throws Exception {
            when(groupService.readGroupConversation("gc-missing"))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("Not found"));

            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> restGroupConversation.readGroupConversation("group-1", "gc-missing"));
        }

        @Test
        @DisplayName("should propagate ResourceStoreException")
        void storeError() throws Exception {
            when(groupService.readGroupConversation("gc-1"))
                    .thenThrow(new IResourceStore.ResourceStoreException("DB error"));

            assertThrows(IResourceStore.ResourceStoreException.class,
                    () -> restGroupConversation.readGroupConversation("group-1", "gc-1"));
        }
    }

    @Nested
    @DisplayName("deleteGroupConversation")
    class DeleteGroupConversation {

        @Test
        @DisplayName("should return 200 on successful delete")
        void success() throws Exception {
            Response response = restGroupConversation.deleteGroupConversation("group-1", "gc-1");

            assertEquals(200, response.getStatus());
            verify(groupService).deleteGroupConversation("gc-1");
        }

        @Test
        @DisplayName("should propagate ResourceStoreException")
        void storeError() throws Exception {
            doThrow(new IResourceStore.ResourceStoreException("Delete failed"))
                    .when(groupService).deleteGroupConversation("gc-1");

            assertThrows(IResourceStore.ResourceStoreException.class,
                    () -> restGroupConversation.deleteGroupConversation("group-1", "gc-1"));
        }
    }

    @Nested
    @DisplayName("listGroupConversations")
    class ListGroupConversations {

        @Test
        @DisplayName("should return list of conversations")
        void success() throws Exception {
            var gc1 = new GroupConversation();
            gc1.setId("gc-1");
            var gc2 = new GroupConversation();
            gc2.setId("gc-2");
            when(groupService.listGroupConversations("group-1", 0, 10))
                    .thenReturn(List.of(gc1, gc2));

            List<GroupConversation> result = restGroupConversation.listGroupConversations("group-1", 0, 10);

            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("should return empty list when no conversations")
        void empty() throws Exception {
            when(groupService.listGroupConversations("group-1", 0, 10))
                    .thenReturn(List.of());

            List<GroupConversation> result = restGroupConversation.listGroupConversations("group-1", 0, 10);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should propagate ResourceStoreException")
        void storeError() throws Exception {
            when(groupService.listGroupConversations("group-1", 0, 10))
                    .thenThrow(new IResourceStore.ResourceStoreException("DB error"));

            assertThrows(IResourceStore.ResourceStoreException.class,
                    () -> restGroupConversation.listGroupConversations("group-1", 0, 10));
        }
    }
}
