/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.rest.interceptors;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
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
        @DisplayName("should skip GET requests")
        void skipsGet() throws Exception {
            var request = mock(ContainerRequestContext.class);
            var response = mock(ContainerResponseContext.class);
            when(response.getStatus()).thenReturn(200);
            when(request.getMethod()).thenReturn("GET");

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

            var resourceId = mock(IResourceStore.IResourceId.class);
            when(resourceId.getId()).thenReturn("abc");
            when(resourceId.getVersion()).thenReturn(1);

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

            var resourceId = mock(IResourceStore.IResourceId.class);
            when(resourceId.getId()).thenReturn("abc");
            when(resourceId.getVersion()).thenReturn(0);

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
    }
}
