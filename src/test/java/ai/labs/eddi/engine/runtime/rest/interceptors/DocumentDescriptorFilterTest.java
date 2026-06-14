/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.rest.interceptors;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.descriptors.model.ResourceDescriptor;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DocumentDescriptorFilter}.
 */
class DocumentDescriptorFilterTest {

    private IDocumentDescriptorStore documentDescriptorStore;
    private IConversationDescriptorStore conversationDescriptorStore;
    private DocumentDescriptorFilter filter;
    private UriInfo uriInfo;

    @BeforeEach
    void setUp() throws Exception {
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        conversationDescriptorStore = mock(IConversationDescriptorStore.class);
        uriInfo = mock(UriInfo.class);
        filter = new DocumentDescriptorFilter(documentDescriptorStore, conversationDescriptorStore);

        // Inject UriInfo
        Field uriInfoField = DocumentDescriptorFilter.class.getDeclaredField("uriInfo");
        uriInfoField.setAccessible(true);
        uriInfoField.set(filter, uriInfo);
    }

    @Nested
    @DisplayName("filter — skip non-success responses")
    class SkipNonSuccess {

        @Test
        @DisplayName("should skip 4xx responses")
        void skips4xx() throws Exception {
            var request = mock(ContainerRequestContext.class);
            var response = mock(ContainerResponseContext.class);
            when(response.getStatus()).thenReturn(404);

            filter.filter(request, response);

            verify(documentDescriptorStore, never()).createDescriptor(any(), anyInt(), any());
        }

        @Test
        @DisplayName("should skip 5xx responses")
        void skips5xx() throws Exception {
            var request = mock(ContainerRequestContext.class);
            var response = mock(ContainerResponseContext.class);
            when(response.getStatus()).thenReturn(500);

            filter.filter(request, response);

            verify(documentDescriptorStore, never()).createDescriptor(any(), anyInt(), any());
        }

        @Test
        @DisplayName("should skip 1xx responses")
        void skips1xx() throws Exception {
            var request = mock(ContainerRequestContext.class);
            var response = mock(ContainerResponseContext.class);
            when(response.getStatus()).thenReturn(100);

            filter.filter(request, response);

            verify(documentDescriptorStore, never()).createDescriptor(any(), anyInt(), any());
        }

        @Test
        @DisplayName("should skip 3xx responses")
        void skips3xx() throws Exception {
            var request = mock(ContainerRequestContext.class);
            var response = mock(ContainerResponseContext.class);
            when(response.getStatus()).thenReturn(301);

            filter.filter(request, response);

            verify(documentDescriptorStore, never()).createDescriptor(any(), anyInt(), any());
        }

        @Test
        @DisplayName("should skip GET requests")
        void skipsGet() throws Exception {
            var request = mock(ContainerRequestContext.class);
            var response = mock(ContainerResponseContext.class);
            when(response.getStatus()).thenReturn(200);
            when(request.getMethod()).thenReturn("GET");

            filter.filter(request, response);

            verify(documentDescriptorStore, never()).createDescriptor(any(), anyInt(), any());
        }

        @Test
        @DisplayName("should skip HEAD requests")
        void skipsHead() throws Exception {
            var request = mock(ContainerRequestContext.class);
            var response = mock(ContainerResponseContext.class);
            when(response.getStatus()).thenReturn(200);
            when(request.getMethod()).thenReturn("HEAD");

            filter.filter(request, response);

            verify(documentDescriptorStore, never()).createDescriptor(any(), anyInt(), any());
        }

        @Test
        @DisplayName("should skip OPTIONS requests")
        void skipsOptions() throws Exception {
            var request = mock(ContainerRequestContext.class);
            var response = mock(ContainerResponseContext.class);
            when(response.getStatus()).thenReturn(200);
            when(request.getMethod()).thenReturn("OPTIONS");

            filter.filter(request, response);

            verify(documentDescriptorStore, never()).createDescriptor(any(), anyInt(), any());
        }
    }

    @Nested
    @DisplayName("createNewVersionOfResource (private)")
    class CreateNewVersionOfResource {

        @Test
        @DisplayName("should append version to URI without existing version")
        void appendsVersion() throws Exception {
            Method method = DocumentDescriptorFilter.class.getDeclaredMethod(
                    "createNewVersionOfResource", URI.class, Integer.class);
            method.setAccessible(true);

            URI result = (URI) method.invoke(null,
                    URI.create("eddi://ai.labs.agent/agentstore/agents/abc123"), 2);

            assertEquals("eddi://ai.labs.agent/agentstore/agents/abc123?version=2", result.toString());
        }

        @Test
        @DisplayName("should replace existing version in URI")
        void replacesVersion() throws Exception {
            Method method = DocumentDescriptorFilter.class.getDeclaredMethod(
                    "createNewVersionOfResource", URI.class, Integer.class);
            method.setAccessible(true);

            URI result = (URI) method.invoke(null,
                    URI.create("eddi://ai.labs.agent/agentstore/agents/abc123?version=1"), 3);

            assertEquals("eddi://ai.labs.agent/agentstore/agents/abc123?version=3", result.toString());
        }

        @Test
        @DisplayName("should handle version bump from 1 to 2")
        void versionBump() throws Exception {
            Method method = DocumentDescriptorFilter.class.getDeclaredMethod(
                    "createNewVersionOfResource", URI.class, Integer.class);
            method.setAccessible(true);

            URI result = (URI) method.invoke(null,
                    URI.create("eddi://ai.labs.agent/agentstore/agents/aabbcc?version=1"), 2);

            assertEquals("eddi://ai.labs.agent/agentstore/agents/aabbcc?version=2", result.toString());
        }

        @Test
        @DisplayName("should handle high version number")
        void highVersion() throws Exception {
            Method method = DocumentDescriptorFilter.class.getDeclaredMethod(
                    "createNewVersionOfResource", URI.class, Integer.class);
            method.setAccessible(true);

            URI result = (URI) method.invoke(null,
                    URI.create("eddi://ai.labs.agent/agentstore/agents/abc123?version=99"), 100);

            assertEquals("eddi://ai.labs.agent/agentstore/agents/abc123?version=100", result.toString());
        }
    }

    @Nested
    @DisplayName("isDescriptorStore (private)")
    class IsDescriptorStore {

        @Test
        @DisplayName("should return true for descriptor store path")
        void descriptorPath() throws Exception {
            Method method = DocumentDescriptorFilter.class.getDeclaredMethod(
                    "isDescriptorStore", String.class);
            method.setAccessible(true);

            assertTrue((boolean) method.invoke(null, "/descriptorstore/descriptors/something"));
        }

        @Test
        @DisplayName("should return false for non-descriptor path")
        void nonDescriptorPath() throws Exception {
            Method method = DocumentDescriptorFilter.class.getDeclaredMethod(
                    "isDescriptorStore", String.class);
            method.setAccessible(true);

            assertFalse((boolean) method.invoke(null, "agentstore/agents/123"));
        }

        @Test
        @DisplayName("should return false for null path")
        void nullPath() throws Exception {
            Method method = DocumentDescriptorFilter.class.getDeclaredMethod(
                    "isDescriptorStore", String.class);
            method.setAccessible(true);

            assertFalse((boolean) method.invoke(null, (String) null));
        }

        @Test
        @DisplayName("should return false for empty string")
        void emptyPath() throws Exception {
            Method method = DocumentDescriptorFilter.class.getDeclaredMethod(
                    "isDescriptorStore", String.class);
            method.setAccessible(true);

            assertFalse((boolean) method.invoke(null, ""));
        }
    }

    @Nested
    @DisplayName("isResourceIdValid (private)")
    class IsResourceIdValid {

        @Test
        @DisplayName("should return true for valid resource ID")
        void valid() throws Exception {
            Method method = DocumentDescriptorFilter.class.getDeclaredMethod(
                    "isResourceIdValid", IResourceStore.IResourceId.class);
            method.setAccessible(true);

            var resourceId = new IResourceStore.IResourceId() {
                @Override
                public String getId() {
                    return "aabbccdd11223344eeff5566";
                }

                @Override
                public Integer getVersion() {
                    return 1;
                }
            };

            assertTrue((boolean) method.invoke(null, resourceId));
        }

        @Test
        @DisplayName("should return false for null resource ID")
        void nullId() throws Exception {
            Method method = DocumentDescriptorFilter.class.getDeclaredMethod(
                    "isResourceIdValid", IResourceStore.IResourceId.class);
            method.setAccessible(true);

            assertFalse((boolean) method.invoke(null, (IResourceStore.IResourceId) null));
        }

        @Test
        @DisplayName("should return false for version 0")
        void zeroVersion() throws Exception {
            Method method = DocumentDescriptorFilter.class.getDeclaredMethod(
                    "isResourceIdValid", IResourceStore.IResourceId.class);
            method.setAccessible(true);

            var resourceId = new IResourceStore.IResourceId() {
                @Override
                public String getId() {
                    return "aabbccdd11223344eeff5566";
                }

                @Override
                public Integer getVersion() {
                    return 0;
                }
            };

            assertFalse((boolean) method.invoke(null, resourceId));
        }

        @Test
        @DisplayName("should return false for negative version")
        void negativeVersion() throws Exception {
            Method method = DocumentDescriptorFilter.class.getDeclaredMethod(
                    "isResourceIdValid", IResourceStore.IResourceId.class);
            method.setAccessible(true);

            var resourceId = new IResourceStore.IResourceId() {
                @Override
                public String getId() {
                    return "aabbccdd11223344eeff5566";
                }

                @Override
                public Integer getVersion() {
                    return -1;
                }
            };

            assertFalse((boolean) method.invoke(null, resourceId));
        }

        @Test
        @DisplayName("should return false when id is null")
        void nullIdField() throws Exception {
            Method method = DocumentDescriptorFilter.class.getDeclaredMethod(
                    "isResourceIdValid", IResourceStore.IResourceId.class);
            method.setAccessible(true);

            var resourceId = new IResourceStore.IResourceId() {
                @Override
                public String getId() {
                    return null;
                }

                @Override
                public Integer getVersion() {
                    return 1;
                }
            };

            assertFalse((boolean) method.invoke(null, resourceId));
        }
    }

    @Nested
    @DisplayName("HTTP method helpers (private)")
    class HttpMethodHelpers {

        @Test
        @DisplayName("should detect PUT correctly")
        void detectsPut() throws Exception {
            Method method = DocumentDescriptorFilter.class.getDeclaredMethod("isPUT", String.class);
            method.setAccessible(true);

            assertTrue((boolean) method.invoke(null, "PUT"));
            assertTrue((boolean) method.invoke(null, "put"));
            assertFalse((boolean) method.invoke(null, "GET"));
        }

        @Test
        @DisplayName("should detect POST correctly")
        void detectsPost() throws Exception {
            Method method = DocumentDescriptorFilter.class.getDeclaredMethod("isPOST", String.class);
            method.setAccessible(true);

            assertTrue((boolean) method.invoke(null, "POST"));
            assertFalse((boolean) method.invoke(null, "GET"));
        }

        @Test
        @DisplayName("should detect DELETE correctly")
        void detectsDelete() throws Exception {
            Method method = DocumentDescriptorFilter.class.getDeclaredMethod("isDELETE", String.class);
            method.setAccessible(true);

            assertTrue((boolean) method.invoke(null, "DELETE"));
            assertFalse((boolean) method.invoke(null, "POST"));
        }

        @Test
        @DisplayName("should detect PATCH correctly")
        void detectsPatch() throws Exception {
            Method method = DocumentDescriptorFilter.class.getDeclaredMethod("isPATCH", String.class);
            method.setAccessible(true);

            assertTrue((boolean) method.invoke(null, "PATCH"));
            assertFalse((boolean) method.invoke(null, "PUT"));
        }
    }

    @Nested
    @DisplayName("filter — POST 201 creates descriptor")
    class PostCreatesDescriptor {

        @Test
        @DisplayName("should create descriptor for new resource on POST 201")
        void createsDescriptor() throws Exception {
            var request = mock(ContainerRequestContext.class);
            var response = mock(ContainerResponseContext.class);
            when(response.getStatus()).thenReturn(201);
            when(request.getMethod()).thenReturn("POST");
            when(response.getHeaderString("Location"))
                    .thenReturn("eddi://ai.labs.agent/agentstore/agents/abcdef1234567890ab?version=1");

            when(documentDescriptorStore.readDescriptor("abcdef1234567890ab", 1))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("Not found"));

            filter.filter(request, response);

            verify(documentDescriptorStore).createDescriptor(eq("abcdef1234567890ab"), eq(1), any());
        }

        @Test
        @DisplayName("should skip descriptor creation for conversation URIs")
        void skipsConversationUri() throws Exception {
            var request = mock(ContainerRequestContext.class);
            var response = mock(ContainerResponseContext.class);
            when(response.getStatus()).thenReturn(201);
            when(request.getMethod()).thenReturn("POST");
            when(response.getHeaderString("Location"))
                    .thenReturn("eddi://ai.labs.conversation/conversationstore/conversations/abcdef1234567890ab?version=1");

            filter.filter(request, response);

            verify(documentDescriptorStore, never()).createDescriptor(any(), anyInt(), any());
        }

        @Test
        @DisplayName("should not duplicate descriptor if one already exists")
        void doesNotDuplicateDescriptor() throws Exception {
            var request = mock(ContainerRequestContext.class);
            var response = mock(ContainerResponseContext.class);
            when(response.getStatus()).thenReturn(201);
            when(request.getMethod()).thenReturn("POST");
            when(response.getHeaderString("Location"))
                    .thenReturn("eddi://ai.labs.agent/agentstore/agents/aabbccdd11223344eeff5566?version=1");

            // readDescriptor does NOT throw → descriptor already exists
            var existingDescriptor = mock(DocumentDescriptor.class);
            doReturn(existingDescriptor).when(documentDescriptorStore)
                    .readDescriptor("aabbccdd11223344eeff5566", 1);

            filter.filter(request, response);

            verify(documentDescriptorStore, never()).createDescriptor(any(), anyInt(), any());
        }

        @Test
        @DisplayName("should skip POST with 200 status (not 201)")
        void skipsPost200() throws Exception {
            var request = mock(ContainerRequestContext.class);
            var response = mock(ContainerResponseContext.class);
            when(response.getStatus()).thenReturn(200);
            when(request.getMethod()).thenReturn("POST");
            when(response.getHeaderString("Location"))
                    .thenReturn("eddi://ai.labs.agent/agentstore/agents/aabbccdd11223344eeff5566?version=1");

            filter.filter(request, response);

            verify(documentDescriptorStore, never()).createDescriptor(any(), anyInt(), any());
        }

        @Test
        @DisplayName("should skip POST with null Location header")
        void skipsNullLocation() throws Exception {
            var request = mock(ContainerRequestContext.class);
            var response = mock(ContainerResponseContext.class);
            when(response.getStatus()).thenReturn(201);
            when(request.getMethod()).thenReturn("POST");
            when(response.getHeaderString("Location")).thenReturn(null);

            filter.filter(request, response);

            verify(documentDescriptorStore, never()).createDescriptor(any(), anyInt(), any());
        }

        @Test
        @DisplayName("should skip POST with non-URI Location header")
        void skipsNonUriLocation() throws Exception {
            var request = mock(ContainerRequestContext.class);
            var response = mock(ContainerResponseContext.class);
            when(response.getStatus()).thenReturn(201);
            when(request.getMethod()).thenReturn("POST");
            // Location without "://" scheme is not processed
            when(response.getHeaderString("Location")).thenReturn("relative/path/only");

            filter.filter(request, response);

            verify(documentDescriptorStore, never()).createDescriptor(any(), anyInt(), any());
        }
    }

    @Nested
    @DisplayName("filter — DELETE marks descriptor as deleted")
    class DeleteDescriptor {

        @Test
        @DisplayName("should mark descriptor as deleted on DELETE")
        void marksDeleted() throws Exception {
            var request = mock(ContainerRequestContext.class);
            var response = mock(ContainerResponseContext.class);
            when(response.getStatus()).thenReturn(200);
            when(request.getMethod()).thenReturn("DELETE");
            when(response.getHeaderString("Location")).thenReturn(null);
            when(uriInfo.getRequestUri())
                    .thenReturn(URI.create("http://localhost:7070/agentstore/agents/aabbccdd11223344eeff5566?version=1"));
            when(uriInfo.getPath()).thenReturn("/agentstore/agents/aabbccdd11223344eeff5566");

            var descriptor = mock(DocumentDescriptor.class);
            doReturn(descriptor).when(documentDescriptorStore)
                    .readDescriptor("aabbccdd11223344eeff5566", 1);

            filter.filter(request, response);

            verify(descriptor).setDeleted(true);
            verify(documentDescriptorStore).setDescriptor(eq("aabbccdd11223344eeff5566"), eq(1), eq(descriptor));
        }
    }

    @Nested
    @DisplayName("filter — PUT/PATCH updates descriptor")
    class PutPatchUpdates {

        @Test
        @DisplayName("should update descriptor version on PUT")
        void updatesOnPut() throws Exception {
            var request = mock(ContainerRequestContext.class);
            var response = mock(ContainerResponseContext.class);
            when(response.getStatus()).thenReturn(200);
            when(request.getMethod()).thenReturn("PUT");
            when(response.getHeaderString("Location"))
                    .thenReturn("eddi://ai.labs.agent/agentstore/agents/aabbccdd11223344eeff5566?version=2");
            when(uriInfo.getPath()).thenReturn("/agentstore/agents/aabbccdd11223344eeff5566");

            var descriptor = mock(DocumentDescriptor.class);
            when(descriptor.getResource())
                    .thenReturn(URI.create("eddi://ai.labs.agent/agentstore/agents/aabbccdd11223344eeff5566?version=1"));
            doReturn(descriptor).when(documentDescriptorStore)
                    .readDescriptor("aabbccdd11223344eeff5566", 1);

            filter.filter(request, response);

            verify(descriptor).setLastModifiedOn(any());
            verify(descriptor).setResource(any());
            verify(documentDescriptorStore).updateDescriptor(eq("aabbccdd11223344eeff5566"), eq(1), eq(descriptor));
        }

        @Test
        @DisplayName("should skip PUT for descriptor store paths")
        void skipsDescriptorStorePath() throws Exception {
            var request = mock(ContainerRequestContext.class);
            var response = mock(ContainerResponseContext.class);
            when(response.getStatus()).thenReturn(200);
            when(request.getMethod()).thenReturn("PUT");
            when(response.getHeaderString("Location"))
                    .thenReturn("eddi://ai.labs.descriptor/descriptorstore/descriptors/aabbccdd11223344eeff5566?version=2");
            when(uriInfo.getPath()).thenReturn("/descriptorstore/descriptors/aabbccdd11223344eeff5566");

            filter.filter(request, response);

            verify(documentDescriptorStore, never()).updateDescriptor(any(), anyInt(), any());
        }
    }

    @Nested
    @DisplayName("filter — error handling")
    class ErrorHandling {

        @Test
        @DisplayName("should throw NotFoundException for ResourceNotFoundException")
        void resourceNotFound() throws Exception {
            var request = mock(ContainerRequestContext.class);
            var response = mock(ContainerResponseContext.class);
            when(response.getStatus()).thenReturn(200);
            when(request.getMethod()).thenReturn("DELETE");
            when(response.getHeaderString("Location")).thenReturn(null);
            when(uriInfo.getRequestUri())
                    .thenReturn(URI.create("http://localhost/agentstore/agents/aabbccdd11223344eeff5566?version=1"));
            when(uriInfo.getPath()).thenReturn("/agentstore/agents/aabbccdd11223344eeff5566");

            doThrow(new IResourceStore.ResourceNotFoundException("Not found"))
                    .when(documentDescriptorStore).readDescriptor("aabbccdd11223344eeff5566", 1);

            assertThrows(NotFoundException.class, () -> filter.filter(request, response));
        }

        @Test
        @DisplayName("should throw BadRequestException for ResourceModifiedException")
        void resourceModified() throws Exception {
            var request = mock(ContainerRequestContext.class);
            var response = mock(ContainerResponseContext.class);
            when(response.getStatus()).thenReturn(200);
            when(request.getMethod()).thenReturn("PUT");
            when(response.getHeaderString("Location"))
                    .thenReturn("eddi://ai.labs.agent/agentstore/agents/aabbccdd11223344eeff5566?version=2");
            when(uriInfo.getPath()).thenReturn("/agentstore/agents/aabbccdd11223344eeff5566");

            var descriptor = mock(DocumentDescriptor.class);
            when(descriptor.getResource())
                    .thenReturn(URI.create("eddi://ai.labs.agent/agentstore/agents/aabbccdd11223344eeff5566?version=1"));
            doReturn(descriptor).when(documentDescriptorStore)
                    .readDescriptor("aabbccdd11223344eeff5566", 1);

            doThrow(new IResourceStore.ResourceModifiedException("Modified"))
                    .when(documentDescriptorStore).updateDescriptor(eq("aabbccdd11223344eeff5566"), eq(1), any());

            assertThrows(BadRequestException.class, () -> filter.filter(request, response));
        }
    }

    @Nested
    @DisplayName("getDescriptorStore routing (private)")
    class DescriptorStoreRouting {

        @Test
        @DisplayName("should return conversationDescriptorStore for conversation URI")
        void conversationUri() throws Exception {
            Method method = DocumentDescriptorFilter.class.getDeclaredMethod(
                    "getDescriptorStore", String.class);
            method.setAccessible(true);

            var result = method.invoke(filter, "eddi://ai.labs.conversation/conversationstore/conversations/abc");
            assertSame(conversationDescriptorStore, result);
        }

        @Test
        @DisplayName("should return documentDescriptorStore for non-conversation URI")
        void nonConversationUri() throws Exception {
            Method method = DocumentDescriptorFilter.class.getDeclaredMethod(
                    "getDescriptorStore", String.class);
            method.setAccessible(true);

            var result = method.invoke(filter, "eddi://ai.labs.agent/agentstore/agents/abc");
            assertSame(documentDescriptorStore, result);
        }
    }
}
