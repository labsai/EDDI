/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.extensions.dictionaries.providers;

import ai.labs.eddi.configs.dictionary.model.DictionaryConfiguration;
import ai.labs.eddi.configs.dictionary.model.DictionaryConfiguration.WordConfiguration;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * The {@code lang} field of a dictionary configuration has to reach the
 * dictionary instance — otherwise the parser's language filter can never skip a
 * dictionary and an English and a German dictionary both match every input.
 */
@DisplayName("RegularDictionaryProvider — language wiring")
class RegularDictionaryProviderLanguageTest {

    private static final String URI_STRING = "eddi://ai.labs.regulardictionary/regulardictionarystore/"
            + "regulardictionaries/aabbccdd11223344eeff5566?version=1";

    private IResourceClientLibrary resourceClientLibrary;
    private RegularDictionaryProvider provider;

    @BeforeEach
    void setUp() {
        resourceClientLibrary = mock(IResourceClientLibrary.class);
        IExpressionProvider expressionProvider = mock(IExpressionProvider.class);
        doReturn(new Expressions(new Expression("greeting"))).when(expressionProvider).parseExpressions(anyString());
        provider = new RegularDictionaryProvider(resourceClientLibrary, expressionProvider);
    }

    @Test
    @DisplayName("configured lang is applied to the dictionary")
    void langIsAppliedToDictionary() throws Exception {
        var dictionary = provide(dictionaryConfiguration("de"));

        assertEquals("de", dictionary.getLanguageCode());
    }

    @Test
    @DisplayName("configured lang is applied to the dictionary's words")
    void langIsAppliedToWords() throws Exception {
        var dictionary = provide(dictionaryConfiguration("de"));

        assertEquals("de", dictionary.getWords().getFirst().getLanguageCode());
    }

    @Test
    @DisplayName("a dictionary without lang stays language agnostic")
    void withoutLangDictionaryStaysLanguageAgnostic() throws Exception {
        var dictionary = provide(dictionaryConfiguration(null));

        assertNull(dictionary.getLanguageCode());
    }

    private IDictionary provide(DictionaryConfiguration configuration) throws Exception {
        doReturn(configuration).when(resourceClientLibrary).getResource(eq(URI.create(URI_STRING)), eq(DictionaryConfiguration.class));
        return provider.provide(Map.of("uri", URI_STRING));
    }

    private static DictionaryConfiguration dictionaryConfiguration(String lang) {
        var configuration = new DictionaryConfiguration();
        configuration.setLang(lang);

        var word = new WordConfiguration();
        word.setWord("hallo");
        word.setExpressions("greeting(hallo)");
        configuration.setWords(List.of(word));

        return configuration;
    }
}
