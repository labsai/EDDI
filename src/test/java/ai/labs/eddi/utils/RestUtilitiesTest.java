/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.utils;

import ai.labs.eddi.datastore.IResourceStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    /**
     * B10 — a malformed URI must be reported through the return value (id == null),
     * never by throwing. An authority-only URI such as "eddi://ai.labs.agent" has
     * no '/' after the scheme at all.
     */
    @ParameterizedTest
    @ValueSource(strings = {"eddi://ai.labs.agent", "eddi://host", "eddi://ai.labs.agent?version=1", "eddi://ai.labs.agent#fragment",
            "eddi://ai.labs.agent/", "agents", "/agents", ""})
    void extractResourceId_withMalformedUri_returnsNullIdWithoutThrowing(String malformedUri) {
        IResourceStore.IResourceId resourceId = RestUtilities.extractResourceId(URI.create(malformedUri));

        assertNotNull(resourceId, "a non-null URI must still yield a resource id object");
        assertNull(resourceId.getId(), "no usable id can be extracted from " + malformedUri);
    }

    /**
     * The parameterized case above feeds this same URI but only ever asserts the
     * id, which is why the version loss went unnoticed: an authority-only URI has
     * no '/' after the scheme, and discarding everything from that point took the
     * query with it. The reported version then fell back to 0 — the value callers
     * already use to mean "unspecified", so an explicit {@code ?version=1} was
     * indistinguishable from no version at all.
     */
    @Test
    void extractResourceId_withQueryButNoPath_stillReadsTheVersion() {
        IResourceStore.IResourceId resourceId = RestUtilities.extractResourceId(URI.create("eddi://ai.labs.agent?version=1"));

        assertNotNull(resourceId);
        assertNull(resourceId.getId(), "an authority-only URI carries no id");
        assertEquals(Integer.valueOf(1), resourceId.getVersion(), "the version query param must survive the missing path");
    }

    @Test
    void extractResourceId_withNoPathAndNoQuery_reportsNoVersion() {
        IResourceStore.IResourceId resourceId = RestUtilities.extractResourceId(URI.create("eddi://ai.labs.agent"));

        assertNotNull(resourceId);
        assertNull(resourceId.getId());
        assertEquals(Integer.valueOf(0), resourceId.getVersion(), "no query means no version, which callers read as 'current'");
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
