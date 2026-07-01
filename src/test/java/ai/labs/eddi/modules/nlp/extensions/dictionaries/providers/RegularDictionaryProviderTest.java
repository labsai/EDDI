/* Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.extensions.dictionaries.providers;

import ai.labs.eddi.configs.dictionary.model.DictionaryConfiguration;
import ai.labs.eddi.configs.dictionary.model.DictionaryConfiguration.PhraseConfiguration;
import ai.labs.eddi.configs.dictionary.model.DictionaryConfiguration.RegExConfiguration;
import ai.labs.eddi.configs.dictionary.model.DictionaryConfiguration.WordConfiguration;
import ai.labs.eddi.configs.workflows.model.ExtensionDescriptor.ConfigValue;
import ai.labs.eddi.configs.workflows.model.ExtensionDescriptor.FieldType;
import ai.labs.eddi.engine.lifecycle.exceptions.IllegalExtensionConfigurationException;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

@DisplayName("RegularDictionaryProvider")
class RegularDictionaryProviderTest {

    @Mock
    private IResourceClientLibrary resourceClientLibrary;

    @Mock
    private IExpressionProvider expressionProvider;

    private RegularDictionaryProvider provider;

    @BeforeEach
    void setUp() {
        openMocks(this);
        provider = new RegularDictionaryProvider(resourceClientLibrary, expressionProvider);
    }

    @Nested
    @DisplayName("getConfigs")
    class GetConfigs {

        @Test
        @DisplayName("should return map with URI config value")
        void shouldReturnUriConfig() {
            Map<String, ConfigValue> configs = provider.getConfigs();

            assertNotNull(configs);
            assertEquals(1, configs.size());
            assertTrue(configs.containsKey("uri"));
            ConfigValue cv = configs.get("uri");
            assertEquals("Resource URI", cv.getDisplayName());
            assertEquals(FieldType.URI, cv.getFieldType());
        }
    }

    @Nested
    @DisplayName("getId")
    class GetId {

        @Test
        @DisplayName("should return correct provider ID")
        void shouldReturnId() {
            assertEquals("ai.labs.parser.dictionaries.regular", provider.getId());
        }
    }

    @Nested
    @DisplayName("getDisplayName")
    class GetDisplayName {

        @Test
        @DisplayName("should return Regular Dictionary")
        void shouldReturnDisplayName() {
            assertEquals("Regular Dictionary", provider.getDisplayName());
        }
    }

    @Nested
    @DisplayName("provide")
    class Provide {

        @Test
        @DisplayName("should return dictionary when valid URI and config are given")
        void shouldReturnDictionary() throws Exception {
            String uriStr = "eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/aabbccdd11223344eeff5566?version=1";
            URI uri = URI.create(uriStr);

            DictionaryConfiguration dictConfig = new DictionaryConfiguration();

            WordConfiguration word = new WordConfiguration();
            word.setWord("hello");
            word.setExpressions("greeting(hello)");
            word.setFrequency(0);
            dictConfig.setWords(List.of(word));

            RegExConfiguration regEx = new RegExConfiguration();
            regEx.setRegEx("\\d+");
            regEx.setExpressions("number(*)");
            dictConfig.setRegExs(List.of(regEx));

            PhraseConfiguration phrase = new PhraseConfiguration();
            phrase.setPhrase("good morning");
            phrase.setExpressions("greeting(good_morning)");
            dictConfig.setPhrases(List.of(phrase));

            doReturn(dictConfig).when(resourceClientLibrary).getResource(eq(uri), eq(DictionaryConfiguration.class));

            Expressions parsedExpr = new Expressions(new Expression("greeting", new Expression("hello")));
            doReturn(parsedExpr).when(expressionProvider).parseExpressions(anyString());

            Map<String, Object> config = new HashMap<>();
            config.put("uri", uriStr);

            IDictionary result = provider.provide(config);

            assertNotNull(result);
            verify(resourceClientLibrary).getResource(eq(uri), eq(DictionaryConfiguration.class));
            verify(expressionProvider, times(3)).parseExpressions(anyString());
        }

        @Test
        @DisplayName("should create default expression when word expression is null")
        void shouldCreateDefaultExpressionWhenNull() throws Exception {
            String uriStr = "eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/aabbccdd11223344eeff5566?version=1";
            URI uri = URI.create(uriStr);

            DictionaryConfiguration dictConfig = new DictionaryConfiguration();

            WordConfiguration word = new WordConfiguration();
            word.setWord("hello");
            word.setExpressions(null); // null expression
            word.setFrequency(0);
            dictConfig.setWords(List.of(word));

            doReturn(dictConfig).when(resourceClientLibrary).getResource(eq(uri), eq(DictionaryConfiguration.class));

            Expression defaultExpr = new Expression("unused", new Expression("hello"));
            doReturn(defaultExpr).when(expressionProvider).createExpression("unused", "hello");

            Map<String, Object> config = new HashMap<>();
            config.put("uri", uriStr);

            IDictionary result = provider.provide(config);

            assertNotNull(result);
            verify(expressionProvider).createExpression("unused", "hello");
            verify(expressionProvider, never()).parseExpressions(anyString());
        }

        @Test
        @DisplayName("should throw when URI is null")
        void shouldThrowWhenUriIsNull() {
            Map<String, Object> config = new HashMap<>();
            config.put("uri", null);

            IllegalExtensionConfigurationException ex = assertThrows(
                    IllegalExtensionConfigurationException.class,
                    () -> provider.provide(config));

            assertTrue(ex.getMessage().contains("No resource URI has been defined"));
        }

        @Test
        @DisplayName("should throw when URI is empty string")
        void shouldThrowWhenUriIsEmpty() {
            Map<String, Object> config = new HashMap<>();
            config.put("uri", "");

            IllegalExtensionConfigurationException ex = assertThrows(
                    IllegalExtensionConfigurationException.class,
                    () -> provider.provide(config));

            assertTrue(ex.getMessage().contains("No resource URI has been defined"));
        }

        @Test
        @DisplayName("should throw when URI does not start with eddi")
        void shouldThrowWhenUriNotEddi() {
            Map<String, Object> config = new HashMap<>();
            config.put("uri", "http://example.com/resource");

            IllegalExtensionConfigurationException ex = assertThrows(
                    IllegalExtensionConfigurationException.class,
                    () -> provider.provide(config));

            assertTrue(ex.getMessage().contains("No resource URI has been defined"));
        }

        @Test
        @DisplayName("should throw when fetched configuration is null")
        void shouldThrowWhenConfigIsNull() throws Exception {
            String uriStr = "eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/aabbccdd11223344eeff5566?version=1";
            URI uri = URI.create(uriStr);

            doReturn(null).when(resourceClientLibrary).getResource(eq(uri), eq(DictionaryConfiguration.class));

            Map<String, Object> config = new HashMap<>();
            config.put("uri", uriStr);

            IllegalExtensionConfigurationException ex = assertThrows(
                    IllegalExtensionConfigurationException.class,
                    () -> provider.provide(config));

            assertTrue(ex.getMessage().contains("DictionaryConfiguration could not be loaded"));
        }

        @Test
        @DisplayName("should wrap ServiceException from resource library")
        void shouldWrapServiceException() throws Exception {
            String uriStr = "eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/aabbccdd11223344eeff5566?version=1";
            URI uri = URI.create(uriStr);

            doThrow(new ServiceException("connection failed"))
                    .when(resourceClientLibrary).getResource(eq(uri), eq(DictionaryConfiguration.class));

            Map<String, Object> config = new HashMap<>();
            config.put("uri", uriStr);

            IllegalExtensionConfigurationException ex = assertThrows(
                    IllegalExtensionConfigurationException.class,
                    () -> provider.provide(config));

            assertTrue(ex.getMessage().contains("Error while fetching DictionaryConfiguration"));
        }

        @Test
        @DisplayName("should skip word with null value without throwing")
        void shouldSkipNullWord() throws Exception {
            String uriStr = "eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/aabbccdd11223344eeff5566?version=1";
            URI uri = URI.create(uriStr);

            DictionaryConfiguration dictConfig = new DictionaryConfiguration();

            WordConfiguration nullWord = new WordConfiguration();
            nullWord.setWord(null);
            dictConfig.setWords(List.of(nullWord));

            doReturn(dictConfig).when(resourceClientLibrary).getResource(eq(uri), eq(DictionaryConfiguration.class));

            Map<String, Object> config = new HashMap<>();
            config.put("uri", uriStr);

            IDictionary result = provider.provide(config);

            assertNotNull(result);
            // no expression parsing should occur for null word
            verify(expressionProvider, never()).parseExpressions(anyString());
            verify(expressionProvider, never()).createExpression(anyString(), anyString());
        }
    }
}
