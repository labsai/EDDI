/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.parser.rest;

import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.parser.IParserStore;
import ai.labs.eddi.configs.parser.model.ParserConfiguration;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.datastore.IResourceStore;
import jakarta.ws.rs.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestParserStore}.
 */
class RestParserStoreTest {

    private IParserStore parserStore;
    private RestParserStore restStore;

    @BeforeEach
    void setUp() {
        parserStore = mock(IParserStore.class);
        var descriptorStore = mock(IDocumentDescriptorStore.class);
        restStore = new RestParserStore(parserStore, descriptorStore, mock(ResourceAccessGuard.class));
    }

    @Test
    @DisplayName("readParser delegates to store")
    void readParser() throws Exception {
        var config = new ParserConfiguration();
        when(parserStore.read("p-1", 1)).thenReturn(config);
        assertNotNull(restStore.readParser("p-1", 1));
    }

    @Test
    @DisplayName("createParser returns 201")
    void createParser() throws Exception {
        var resourceId = mock(IResourceStore.IResourceId.class);
        when(resourceId.getId()).thenReturn("new-id");
        when(resourceId.getVersion()).thenReturn(1);
        when(parserStore.create(any())).thenReturn(resourceId);
        assertEquals(201, restStore.createParser(new ParserConfiguration()).getStatus());
    }

    @Test
    @DisplayName("deleteParser delegates")
    void deleteParser() throws Exception {
        restStore.deleteParser("p-1", 1, false);
        verify(parserStore).delete("p-1", 1);
    }

    @Test
    @DisplayName("duplicateParser reads then creates")
    void duplicateParser() throws Exception {
        var config = new ParserConfiguration();
        when(parserStore.read("p-1", 1)).thenReturn(config);
        var resourceId = mock(IResourceStore.IResourceId.class);
        when(resourceId.getId()).thenReturn("dup-id");
        when(resourceId.getVersion()).thenReturn(1);
        when(parserStore.create(any())).thenReturn(resourceId);
        assertEquals(201, restStore.duplicateParser("p-1", 1).getStatus());
    }

    @Test
    @DisplayName("getResourceURI returns non-null")
    void getResourceURI() {
        assertNotNull(restStore.getResourceURI());
    }

    @Test
    @DisplayName("getCurrentResourceId delegates")
    void getCurrentResourceId() throws Exception {
        var resourceId = mock(IResourceStore.IResourceId.class);
        when(resourceId.getVersion()).thenReturn(2);
        when(parserStore.getCurrentResourceId("p-1")).thenReturn(resourceId);
        assertEquals(2, restStore.getCurrentResourceId("p-1").getVersion());
    }
    private static ParserConfiguration parserWithDictionary(String dictionaryId) {
        var config = new ParserConfiguration();
        List<Object> dictionaries = new ArrayList<>();
        dictionaries.add(Map.of("type", "eddi://ai.labs.parser.dictionaries.regular", "config",
                Map.of("uri", "eddi://ai.labs.dictionary/dictionarystore/dictionaries/" + dictionaryId + "?version=1")));
        config.setExtensions(new HashMap<>(Map.of("dictionaries", dictionaries)));
        return config;
    }

    @Test
    @DisplayName("a parser naming another space's private dictionary is refused on create, update and duplicate")
    void foreignDictionaryReferenceIsRefused() throws Exception {
        var guard = mock(ResourceAccessGuard.class);
        doThrow(new ForbiddenException("denied")).when(guard).requireAccess(eq("ffeeddccbbaa998877665544"), eq(AccessLevel.VIEW), anyString());
        var sut = new RestParserStore(parserStore, mock(IDocumentDescriptorStore.class), guard);
        var current = mock(IResourceStore.IResourceId.class);
        when(current.getVersion()).thenReturn(1);
        when(parserStore.getCurrentResourceId("p-1")).thenReturn(current);
        when(parserStore.read("p-1", 1)).thenReturn(new ParserConfiguration());

        assertThrows(ForbiddenException.class, () -> sut.createParser(parserWithDictionary("ffeeddccbbaa998877665544")));
        assertThrows(ForbiddenException.class, () -> sut.updateParser("p-1", 1, parserWithDictionary("ffeeddccbbaa998877665544")));
        when(parserStore.read("p-1", 1)).thenReturn(parserWithDictionary("ffeeddccbbaa998877665544"));
        assertThrows(ForbiddenException.class, () -> sut.duplicateParser("p-1", 1));
        verify(parserStore, never()).create(any());
        verify(parserStore, never()).update(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("an update may keep a dictionary reference the stored version already had")
    void keptReferenceNeedsNothingNew() throws Exception {
        var guard = mock(ResourceAccessGuard.class);
        doThrow(new ForbiddenException("denied")).when(guard).requireAccess(eq("ffeeddccbbaa998877665544"), eq(AccessLevel.VIEW), anyString());
        var sut = new RestParserStore(parserStore, mock(IDocumentDescriptorStore.class), guard);
        var current = mock(IResourceStore.IResourceId.class);
        when(current.getVersion()).thenReturn(1);
        when(parserStore.getCurrentResourceId("p-1")).thenReturn(current);
        when(parserStore.read("p-1", 1)).thenReturn(parserWithDictionary("ffeeddccbbaa998877665544"));
        when(parserStore.update(eq("p-1"), eq(1), any())).thenReturn(2);

        sut.updateParser("p-1", 1, parserWithDictionary("ffeeddccbbaa998877665544"));

        verify(guard, never()).requireAccess(eq("ffeeddccbbaa998877665544"), any(), anyString());
    }
}
