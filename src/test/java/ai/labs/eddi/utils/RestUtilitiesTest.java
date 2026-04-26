/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.utils;

import ai.labs.eddi.datastore.IResourceStore;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

class RestUtilitiesTest {

    // --- createURI ---

    @Test
    void createURI_withMultipleParts_concatenatesThem() {
        URI uri = RestUtilities.createURI("eddi://ai.labs.agents/", "abc123", "?version=", 1);
        assertEquals("eddi://ai.labs.agents/abc123?version=1", uri.toString());
    }

    @Test
    void createURI_withSinglePart_returnsThatPart() {
        URI uri = RestUtilities.createURI("eddi://ai.labs.agents/abc");
        assertEquals("eddi://ai.labs.agents/abc", uri.toString());
    }

    // --- extractResourceId ---

    @Test
    void extractResourceId_withValidEddiUri_extractsIdAndVersion() {
        URI uri = URI.create("eddi://ai.labs.agents/agentsstore/agents/5262b802dc6c4008b54c7c0b58100f97?version=3");
        IResourceStore.IResourceId resourceId = RestUtilities.extractResourceId(uri);

        assertNotNull(resourceId);
        assertEquals("5262b802dc6c4008b54c7c0b58100f97", resourceId.getId());
        assertEquals(3, resourceId.getVersion());
    }

    @Test
    void extractResourceId_withUuidFormat_extractsId() {
        URI uri = URI.create("eddi://ai.labs.agents/agentsstore/agents/5262b802-dc6c-4008-b54c-7c0b58100f97?version=1");
        IResourceStore.IResourceId resourceId = RestUtilities.extractResourceId(uri);

        assertNotNull(resourceId);
        assertEquals("5262b802-dc6c-4008-b54c-7c0b58100f97", resourceId.getId());
        assertEquals(1, resourceId.getVersion());
    }

    @Test
    void extractResourceId_withNoVersion_returnsZeroVersion() {
        URI uri = URI.create("eddi://ai.labs.agents/agentsstore/agents/5262b802dc6c4008b54c7c0b58100f97");
        IResourceStore.IResourceId resourceId = RestUtilities.extractResourceId(uri);

        assertNotNull(resourceId);
        assertEquals("5262b802dc6c4008b54c7c0b58100f97", resourceId.getId());
        assertEquals(0, resourceId.getVersion());
    }

    @Test
    void extractResourceId_withNullUri_returnsNull() {
        assertNull(RestUtilities.extractResourceId(null));
    }

    @Test
    void extractResourceId_withRelativeUri_extractsId() {
        URI uri = URI.create("/agentsstore/agents/5262b802dc6c4008b54c7c0b58100f97?version=2");
        IResourceStore.IResourceId resourceId = RestUtilities.extractResourceId(uri);

        assertNotNull(resourceId);
        assertEquals("5262b802dc6c4008b54c7c0b58100f97", resourceId.getId());
        assertEquals(2, resourceId.getVersion());
    }

    @Test
    void extractResourceId_withShortPath_returnsNullId() {
        // Path with <=2 segments should return null ID
        URI uri = URI.create("/agents/?version=1");
        IResourceStore.IResourceId resourceId = RestUtilities.extractResourceId(uri);

        assertNotNull(resourceId);
        assertNull(resourceId.getId());
    }

    @Test
    void extractResourceId_withInvalidVersion_throwsIllegalArgument() {
        URI uri = URI.create("eddi://ai.labs.agents/agentsstore/agents/5262b802dc6c4008b54c7c0b58100f97?version=abc");
        assertThrows(IllegalArgumentException.class, () -> RestUtilities.extractResourceId(uri));
    }

    @Test
    void extractResourceId_withTrailingSlash_handlesGracefully() {
        URI uri = URI.create("eddi://ai.labs.agents/agentsstore/agents/5262b802dc6c4008b54c7c0b58100f97/");
        IResourceStore.IResourceId resourceId = RestUtilities.extractResourceId(uri);

        assertNotNull(resourceId);
        // Trailing slash is stripped, last segment is the ID
        assertEquals("5262b802dc6c4008b54c7c0b58100f97", resourceId.getId());
    }
}
