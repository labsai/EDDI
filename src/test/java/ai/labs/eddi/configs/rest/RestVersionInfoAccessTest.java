/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.security.spaces.AccessScope;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.quarkus.security.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RestVersionInfo} is the single body behind all fifteen configuration
 * resource types and the only place their access is checked, so these
 * assertions stand in for fifteen stores at once. If the guard call is removed
 * from any method here, every one of those types silently loses its access
 * control.
 */
class RestVersionInfoAccessTest {

    private static final String RESOURCE_URI = "eddi://ai.labs.agent/agentstore/agents/";
    private static final String ID = "abcdef1234567890abcdef";

    private IResourceStore<Object> resourceStore;
    private IDocumentDescriptorStore descriptorStore;
    private ResourceAccessGuard accessGuard;
    private RestVersionInfo<Object> restVersionInfo;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        resourceStore = mock(IResourceStore.class);
        descriptorStore = mock(IDocumentDescriptorStore.class);
        accessGuard = mock(ResourceAccessGuard.class);
        restVersionInfo = new RestVersionInfo<>(RESOURCE_URI, resourceStore, descriptorStore, accessGuard);
    }

    @Test
    @DisplayName("listing passes the caller's scope to the query, rather than filtering afterwards")
    void listingIsScopedInTheQuery() throws Exception {
        AccessScope scope = AccessScope.unrestricted();
        when(accessGuard.listingScope()).thenReturn(scope);
        when(descriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean(), any())).thenReturn(List.of());

        restVersionInfo.readDescriptors("filter", 0, 20);

        verify(descriptorStore).readDescriptors(eq("ai.labs.agent"), eq("filter"), eq(0), eq(20), eq(false), eq(scope));
        verify(descriptorStore, never()).readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean());
    }

    @Test
    @DisplayName("read requires VIEW, and does not touch the store when refused")
    void readRequiresView() throws Exception {
        doThrow(new ForbiddenException("no")).when(accessGuard).requireAccess(eq(ID), eq(AccessLevel.VIEW), anyString());

        assertThrows(ForbiddenException.class, () -> restVersionInfo.read(ID, 1));
        verify(resourceStore, never()).read(anyString(), anyInt());
    }

    @Test
    @DisplayName("update requires EDIT, and does not write when refused")
    void updateRequiresEdit() throws Exception {
        doThrow(new ForbiddenException("no")).when(accessGuard).requireAccess(eq(ID), eq(AccessLevel.EDIT), anyString());

        assertThrows(ForbiddenException.class, () -> restVersionInfo.update(ID, 1, new Object()));
        verify(resourceStore, never()).update(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("delete requires OWN — EDIT deliberately does not carry it")
    void deleteRequiresOwn() throws Exception {
        doThrow(new ForbiddenException("no")).when(accessGuard).requireAccess(eq(ID), eq(AccessLevel.OWN), anyString());

        assertThrows(ForbiddenException.class, () -> restVersionInfo.delete(ID, 1, false));
        verify(resourceStore, never()).delete(anyString(), anyInt());
        verify(resourceStore, never()).deleteAllPermanently(anyString());
    }

    @Test
    @DisplayName("creating needs no existing access — you cannot lack access to something that does not exist yet")
    void createIsUnguarded() throws Exception {
        when(resourceStore.create(any())).thenReturn(new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return ID;
            }

            @Override
            public Integer getVersion() {
                return 1;
            }
        });

        restVersionInfo.create(new Object());

        verify(accessGuard, never()).requireAccess(anyString(), any(), anyString());
    }
}
