/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.dictionary.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.dictionary.IDictionaryStore;
import ai.labs.eddi.configs.dictionary.model.DictionaryConfiguration;
import ai.labs.eddi.configs.dictionary.model.DictionaryConfiguration.RegExConfiguration;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A dictionary {@code regEx} was compiled only when the agent was deployed, so
 * a typo saved here failed the whole deployment later with a stack trace. The
 * pattern is compiled when the dictionary is saved and refused with Java's own
 * description of the syntax error.
 */
@DisplayName("RestDictionaryStore — an invalid regEx is refused at save time")
class RestDictionaryStoreRegExValidationTest {

    private static final String ID = "aabbccddeeff112233445566";

    private IDictionaryStore dictionaryStore;
    private RestDictionaryStore store;

    @BeforeEach
    void setUp() {
        dictionaryStore = mock(IDictionaryStore.class);
        store = new RestDictionaryStore(dictionaryStore, mock(IDocumentDescriptorStore.class), mock(IJsonSchemaCreator.class),
                mock(ResourceAccessGuard.class));
    }

    @Test
    void createIsRefusedWithTheSyntaxError() throws Exception {
        Response response = store.createRegularDictionary(dictionaryWith("(unclosed"));

        assertEquals(400, response.getStatus());
        String entity = response.getEntity().toString();
        assertTrue(entity.contains("(unclosed"), entity);
        assertTrue(entity.contains("Unclosed group"), entity);
        verify(dictionaryStore, never()).create(any());
    }

    @Test
    void updateIsRefused() throws Exception {
        Response response = store.updateRegularDictionary(ID, 1, dictionaryWith("[a-"));

        assertEquals(400, response.getStatus());
        verify(dictionaryStore, never()).update(anyString(), anyInt(), any());
    }

    @Test
    void duplicateOfAnInvalidDictionaryIsRefused() throws Exception {
        when(dictionaryStore.read(ID, 1)).thenReturn(dictionaryWith("*oops"));

        Response response = store.duplicateRegularDictionary(ID, 1);

        assertEquals(400, response.getStatus());
        verify(dictionaryStore, never()).create(any());
    }

    @Test
    void validPatternsAreSaved() throws Exception {
        var created = mock(IResourceStore.IResourceId.class);
        when(created.getId()).thenReturn(ID);
        when(created.getVersion()).thenReturn(1);
        when(dictionaryStore.create(any())).thenReturn(created);

        Response response = store.createRegularDictionary(dictionaryWith("(\\w)(\\s+)([\\.,])"));

        assertEquals(201, response.getStatus());
        verify(dictionaryStore).create(any());
    }

    private static DictionaryConfiguration dictionaryWith(String regEx) {
        var regExConfiguration = new RegExConfiguration();
        regExConfiguration.setRegEx(regEx);
        regExConfiguration.setExpressions("unused");
        var configuration = new DictionaryConfiguration();
        configuration.getRegExs().add(regExConfiguration);
        return configuration;
    }
}
