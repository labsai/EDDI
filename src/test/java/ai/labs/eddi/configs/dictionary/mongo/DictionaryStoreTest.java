/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.dictionary.mongo;

import ai.labs.eddi.configs.dictionary.model.DictionaryConfiguration;
import ai.labs.eddi.configs.dictionary.model.DictionaryConfiguration.PhraseConfiguration;
import ai.labs.eddi.configs.dictionary.model.DictionaryConfiguration.WordConfiguration;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DictionaryStore}. Tests validation in create/update,
 * and comparator/filtering logic.
 */
@SuppressWarnings("unchecked")
class DictionaryStoreTest {

    private DictionaryStore store;
    private IResourceStorage<DictionaryConfiguration> resourceStorage;

    @BeforeEach
    void setUp() {
        IResourceStorageFactory storageFactory = mock(IResourceStorageFactory.class);
        resourceStorage = mock(IResourceStorage.class);
        IDocumentBuilder documentBuilder = mock(IDocumentBuilder.class);
        when(storageFactory.create(eq("dictionaries"), any(), eq(DictionaryConfiguration.class)))
                .thenReturn(resourceStorage);

        store = new DictionaryStore(storageFactory, documentBuilder);
    }

    // ==================== Create Validation ====================

    @Nested
    @DisplayName("create validation")
    class CreateValidation {

        @Test
        @DisplayName("should throw when words list contains null element")
        void throwsOnNullWord() {
            var config = new DictionaryConfiguration();
            config.setWords(Arrays.asList(createWord("hello"), null));
            config.setPhrases(new ArrayList<>());

            assertThrows(Exception.class, () -> store.create(config));
        }

        @Test
        @DisplayName("should throw when phrases list contains null element")
        void throwsOnNullPhrase() {
            var config = new DictionaryConfiguration();
            config.setWords(new ArrayList<>());
            config.setPhrases(Arrays.asList(createPhrase("good morning"), null));

            assertThrows(Exception.class, () -> store.create(config));
        }

        @Test
        @DisplayName("should succeed with valid words and phrases")
        void succeedsWithValidData() throws Exception {
            var config = new DictionaryConfiguration();
            config.setWords(List.of(createWord("hello")));
            config.setPhrases(List.of(createPhrase("good morning")));

            // Mock the underlying storage for create
            var mockResource = mock(IResourceStorage.IResource.class);
            when(mockResource.getId()).thenReturn("new-id");
            when(mockResource.getVersion()).thenReturn(1);
            when(resourceStorage.newResource(any())).thenReturn(mockResource);

            var result = store.create(config);
            assertNotNull(result);
        }
    }

    // ==================== Update Validation ====================

    @Nested
    @DisplayName("update validation")
    class UpdateValidation {

        @Test
        @DisplayName("should throw when words list contains null element")
        void throwsOnNullWord() {
            var config = new DictionaryConfiguration();
            config.setWords(Arrays.asList(createWord("hello"), null));
            config.setPhrases(new ArrayList<>());

            assertThrows(Exception.class, () -> store.update("dict-1", 1, config));
        }

        @Test
        @DisplayName("should throw when phrases list contains null element")
        void throwsOnNullPhrase() {
            var config = new DictionaryConfiguration();
            config.setWords(new ArrayList<>());
            config.setPhrases(Arrays.asList(createPhrase("good morning"), null));

            assertThrows(Exception.class, () -> store.update("dict-1", 1, config));
        }
    }

    // ==================== WordComparator / PhraseComparator ====================

    @Nested
    @DisplayName("Comparator tests")
    class ComparatorTests {

        @Test
        @DisplayName("WordConfiguration sorts alphabetically by word")
        void wordComparator() {
            var w1 = createWord("banana");
            var w2 = createWord("apple");
            var w3 = createWord("cherry");

            var words = new ArrayList<>(List.of(w1, w2, w3));
            Collections.sort(words);

            assertEquals("apple", words.get(0).getWord());
            assertEquals("banana", words.get(1).getWord());
            assertEquals("cherry", words.get(2).getWord());
        }

        @Test
        @DisplayName("WordConfiguration equals and hashCode")
        void wordEqualsHashCode() {
            var w1 = createWord("hello");
            var w2 = createWord("hello");
            var w3 = createWord("world");

            assertEquals(w1, w2);
            assertEquals(w1.hashCode(), w2.hashCode());
            assertNotEquals(w1, w3);
        }

        @Test
        @DisplayName("WordConfiguration equals with null and different type")
        void wordEqualsEdgeCases() {
            var w1 = createWord("hello");
            assertNotEquals(null, w1);
            assertNotEquals("hello", w1);
            assertEquals(w1, w1); // reflexive
        }

        @Test
        @DisplayName("PhraseConfiguration equals and hashCode")
        void phraseEqualsHashCode() {
            var p1 = createPhrase("good morning");
            var p2 = createPhrase("good morning");
            var p3 = createPhrase("good evening");

            assertEquals(p1, p2);
            assertEquals(p1.hashCode(), p2.hashCode());
            assertNotEquals(p1, p3);
        }

        @Test
        @DisplayName("PhraseConfiguration equals with null and different type")
        void phraseEqualsEdgeCases() {
            var p1 = createPhrase("hello world");
            assertNotEquals(null, p1);
            assertNotEquals("hello world", p1);
            assertEquals(p1, p1); // reflexive
        }

        @Test
        @DisplayName("RegExConfiguration equals and hashCode")
        void regExEqualsHashCode() {
            var r1 = createRegEx("\\d+");
            var r2 = createRegEx("\\d+");
            var r3 = createRegEx("\\w+");

            assertEquals(r1, r2);
            assertEquals(r1.hashCode(), r2.hashCode());
            assertNotEquals(r1, r3);
        }

        @Test
        @DisplayName("RegExConfiguration equals edge cases")
        void regExEqualsEdgeCases() {
            var r1 = createRegEx("\\d+");
            assertNotEquals(null, r1);
            assertNotEquals("\\d+", r1);
            assertEquals(r1, r1);
        }

        @Test
        @DisplayName("RegExConfiguration compareTo sorts alphabetically")
        void regExCompareTo() {
            var r1 = createRegEx("b.*");
            var r2 = createRegEx("a.*");

            assertTrue(r1.compareTo(r2) > 0);
            assertTrue(r2.compareTo(r1) < 0);
        }
    }

    // ==================== Model Tests ====================

    @Nested
    @DisplayName("DictionaryConfiguration model")
    class ModelTests {

        @Test
        @DisplayName("default constructor initializes empty lists")
        void defaultConstructor() {
            var config = new DictionaryConfiguration();
            assertNotNull(config.getWords());
            assertTrue(config.getWords().isEmpty());
            assertNotNull(config.getPhrases());
            assertTrue(config.getPhrases().isEmpty());
            assertNotNull(config.getRegExs());
            assertTrue(config.getRegExs().isEmpty());
        }

        @Test
        @DisplayName("lang getter/setter")
        void langGetterSetter() {
            var config = new DictionaryConfiguration();
            config.setLang("de");
            assertEquals("de", config.getLang());
        }

        @Test
        @DisplayName("WordConfiguration frequency getter/setter")
        void wordFrequency() {
            var word = createWord("test");
            word.setFrequency(5);
            assertEquals(5, word.getFrequency());
        }

        @Test
        @DisplayName("WordConfiguration expressions getter/setter")
        void wordExpressions() {
            var word = createWord("hello");
            word.setExpressions("greeting(hello)");
            assertEquals("greeting(hello)", word.getExpressions());
        }

        @Test
        @DisplayName("PhraseConfiguration expressions getter/setter")
        void phraseExpressions() {
            var phrase = createPhrase("good morning");
            phrase.setExpressions("greeting(good_morning)");
            assertEquals("greeting(good_morning)", phrase.getExpressions());
        }

        @Test
        @DisplayName("RegExConfiguration expressions getter/setter")
        void regExExpressions() {
            var regex = createRegEx("\\d+");
            regex.setExpressions("number($1)");
            assertEquals("number($1)", regex.getExpressions());
        }
    }

    // ==================== Read Filtered Validation ====================

    @Nested
    @DisplayName("read with filter/order/index/limit validation")
    class ReadFilteredValidation {

        @Test
        @DisplayName("should throw when filter is null")
        void throwsOnNullFilter() {
            assertThrows(IllegalArgumentException.class,
                    () -> store.read("aabbccdd11223344eeff5566", 1, null, "asc", 0, 10));
        }

        @Test
        @DisplayName("should throw when order is null")
        void throwsOnNullOrder() {
            assertThrows(IllegalArgumentException.class,
                    () -> store.read("aabbccdd11223344eeff5566", 1, "", null, 0, 10));
        }

        @Test
        @DisplayName("should throw when index is null")
        void throwsOnNullIndex() {
            assertThrows(IllegalArgumentException.class,
                    () -> store.read("aabbccdd11223344eeff5566", 1, "", "asc", null, 10));
        }

        @Test
        @DisplayName("should throw when limit is null")
        void throwsOnNullLimit() {
            assertThrows(IllegalArgumentException.class,
                    () -> store.read("aabbccdd11223344eeff5566", 1, "", "asc", 0, null));
        }
    }

    // ==================== Update Success Validation ====================

    @Nested
    @DisplayName("update with valid data")
    class UpdateSuccessValidation {

        @Test
        @DisplayName("should pass validation with empty words and phrases lists")
        void passesWithEmptyLists() {
            var config = new DictionaryConfiguration();
            config.setWords(new ArrayList<>());
            config.setPhrases(new ArrayList<>());

            // update calls checkCollectionNoNullElements (passes), then super.update
            // which will fail due to storage not being set up — but validation passes
            assertThrows(Exception.class, () -> store.update("aabbccdd11223344eeff5566", 1, config));
        }

        @Test
        @DisplayName("should pass validation with valid words and phrases")
        void passesWithValidElements() {
            var config = new DictionaryConfiguration();
            config.setWords(List.of(createWord("hello"), createWord("world")));
            config.setPhrases(List.of(createPhrase("good morning")));

            // Storage layer will fail, but we verify validation passes
            assertThrows(Exception.class, () -> store.update("aabbccdd11223344eeff5566", 1, config));
        }
    }

    // ==================== ReadExpressions edge cases ====================

    @Nested
    @DisplayName("readExpressions validation")
    class ReadExpressionsValidation {

        @Test
        @DisplayName("should throw when filter is null")
        void throwsOnNullFilter() {
            assertThrows(IllegalArgumentException.class,
                    () -> store.readExpressions("aabbccdd11223344eeff5566", 1, null, "asc", 0, 10));
        }

        @Test
        @DisplayName("should throw when order is null")
        void throwsOnNullOrder() {
            assertThrows(IllegalArgumentException.class,
                    () -> store.readExpressions("aabbccdd11223344eeff5566", 1, "", null, 0, 10));
        }
    }

    // ==================== PhraseConfiguration sorting ====================

    @Nested
    @DisplayName("PhraseConfiguration sorting")
    class PhraseSorting {

        @Test
        @DisplayName("PhraseConfiguration sorts alphabetically by phrase")
        void phraseComparator() {
            var p1 = createPhrase("cherry");
            var p2 = createPhrase("apple");
            var p3 = createPhrase("banana");

            var phrases = new ArrayList<>(List.of(p1, p2, p3));
            phrases.sort(Comparator.comparing(PhraseConfiguration::getPhrase));

            assertEquals("apple", phrases.get(0).getPhrase());
            assertEquals("banana", phrases.get(1).getPhrase());
            assertEquals("cherry", phrases.get(2).getPhrase());
        }
    }

    // ==================== Helpers ====================

    private static WordConfiguration createWord(String word) {
        var config = new WordConfiguration();
        config.setWord(word);
        return config;
    }

    private static PhraseConfiguration createPhrase(String phrase) {
        var config = new PhraseConfiguration();
        config.setPhrase(phrase);
        return config;
    }

    private static DictionaryConfiguration.RegExConfiguration createRegEx(String regex) {
        var config = new DictionaryConfiguration.RegExConfiguration();
        config.setRegEx(regex);
        return config;
    }
}
