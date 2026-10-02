/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp;

import ai.labs.eddi.configs.parser.model.ParserConfiguration;
import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.providers.IDictionaryProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * N1 — a parser step that references a {@code parserstore} document through
 * {@code config.uri} (what {@code AgentSetupService}, the MCP setup tools and
 * the Manager's pipeline builder write) runs with that document's dictionaries.
 * It used to run with none, silently.
 */
class InputParserTaskParserDocumentTest {

    private static final String DOCUMENT_URI = "eddi://ai.labs.parser/parserstore/parsers/aabbccddeeff112233445566?version=1";
    private static final String REGULAR = "ai.labs.parser.dictionaries.regular";
    private static final String DICTIONARY_URI = "eddi://ai.labs.dictionary/dictionarystore/dictionaries/bbccddeeff11223344556677?version=1";

    private IResourceClientLibrary resourceClientLibrary;
    private IDictionaryProvider dictionaryProvider;
    private InputParserTask task;

    @BeforeEach
    void setUp() throws Exception {
        resourceClientLibrary = mock(IResourceClientLibrary.class);
        dictionaryProvider = mock(IDictionaryProvider.class);
        when(dictionaryProvider.provide(anyMap())).thenReturn(mock(IDictionary.class));
        Map<String, Provider<IDictionaryProvider>> dictionaryProviders = new HashMap<>();
        dictionaryProviders.put(REGULAR, () -> dictionaryProvider);
        task = new InputParserTask(mock(IExpressionProvider.class), new HashMap<>(), dictionaryProviders, new HashMap<>(), new ObjectMapper(),
                resourceClientLibrary);
    }

    private static ParserConfiguration document(Map<String, Object> config) {
        var document = new ParserConfiguration();
        document.setConfig(config);
        document.setExtensions(Map.of("dictionaries", List.of(Map.of("type", "eddi://" + REGULAR, "config", Map.of("uri", DICTIONARY_URI)))));
        return document;
    }

    @Test
    @DisplayName("the referenced document's dictionaries are loaded")
    void documentDictionariesAreLoaded() throws Exception {
        when(resourceClientLibrary.getResource(URI.create(DOCUMENT_URI), ParserConfiguration.class)).thenReturn(document(null));

        task.configure(new HashMap<>(Map.of("uri", DOCUMENT_URI)), new HashMap<>());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> config = ArgumentCaptor.forClass(Map.class);
        verify(dictionaryProvider).provide(config.capture());
        assertEquals(DICTIONARY_URI, config.getValue().get("uri"));
    }

    @Test
    @DisplayName("inline step extensions are appended after the document's, step config keys win")
    void inlineStepIsMergedOverTheDocument() throws Exception {
        when(resourceClientLibrary.getResource(URI.create(DOCUMENT_URI), ParserConfiguration.class))
                .thenReturn(document(Map.of("includeUnknown", "false", "appendExpressions", "true")));
        Map<String, Object> stepExtensions = new HashMap<>();
        stepExtensions.put("dictionaries", List.of(Map.of("type", "eddi://" + REGULAR, "config", Map.of("uri", "second"))));

        var parser = (IInputParser) task.configure(new HashMap<>(Map.of("uri", DOCUMENT_URI, "appendExpressions", "false")), stepExtensions);

        verify(dictionaryProvider, times(2)).provide(anyMap());
        assertFalse(parser.getConfig().isIncludeUnknown(), "the document's config applies");
        assertFalse(parser.getConfig().isAppendExpressions(), "the step's own config wins over the document's");
    }

    @Test
    @DisplayName("a step without uri does not touch the store")
    void inlineOnlyStepDoesNotFetch() throws Exception {
        task.configure(new HashMap<>(), new HashMap<>());
        verify(resourceClientLibrary, never()).getResource(any(), eq(ParserConfiguration.class));
    }

    @Test
    @DisplayName("an unreadable document fails the deployment instead of running without dictionaries")
    void unreadableDocumentFailsConfiguration() throws Exception {
        when(resourceClientLibrary.getResource(URI.create(DOCUMENT_URI), ParserConfiguration.class))
                .thenThrow(new ServiceException("not found"));

        var e = assertThrows(WorkflowConfigurationException.class, () -> task.configure(new HashMap<>(Map.of("uri", DOCUMENT_URI)), null));
        assertTrue(e.getMessage().contains(DOCUMENT_URI), e.getMessage());
    }
}
