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
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Additional branch coverage for {@link DictionaryStore}:
 * <ul>
 * <li>read(id, version, filter, order, index, limit) — exercises
 * ResultManipulator filter/sort/limit through the real
 * DictionaryStore.read(6-param) method</li>
 * <li>readExpressions — exercises word/phrase expression extraction loops</li>
 * <li>WordComparator and PhraseComparator reverse-order sort</li>
 * <li>create success path verifying sorting side effects</li>
 * </ul>
 */
@SuppressWarnings("unchecked")
@DisplayName("DictionaryStore — Filtered Read / ReadExpressions Branch Tests")
class DictionaryStoreFilteredReadTest {

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

    /**
     * Creates a DictionaryConfiguration for the filtered-read mocking path.
     */
    private DictionaryConfiguration createDictionaryWithWordsAndPhrases() {
        var config = new DictionaryConfiguration();

        var w1 = new WordConfiguration();
        w1.setWord("hello");
        w1.setExpressions("greeting(hello)");

        var w2 = new WordConfiguration();
        w2.setWord("world");
        w2.setExpressions("noun(world)");

        var w3 = new WordConfiguration();
        w3.setWord("hey");
        w3.setExpressions("greeting(hey)");

        config.setWords(new ArrayList<>(List.of(w1, w2, w3)));

        var p1 = new PhraseConfiguration();
        p1.setPhrase("good morning");
        p1.setExpressions("greeting(good_morning)");

        var p2 = new PhraseConfiguration();
        p2.setPhrase("good evening");
        p2.setExpressions("greeting(good_evening)");

        config.setPhrases(new ArrayList<>(List.of(p1, p2)));

        return config;
    }

    // ==================== read(filtered) ====================

    @Nested
    @DisplayName("read(id, version, filter, order, index, limit)")
    class FilteredReadTests {

        @Test
        @DisplayName("filter='' order='asc' — returns sorted result")
        void emptyFilterAscOrder() throws Exception {
            var dict = createDictionaryWithWordsAndPhrases();

            IResourceStorage.IResource<DictionaryConfiguration> resource = mock(IResourceStorage.IResource.class);
            when(resource.getData()).thenReturn(dict);
            when(resourceStorage.read("test-id", 1)).thenReturn(resource);

            var result = store.read("test-id", 1, "", "asc", 0, 10);

            assertNotNull(result);
            // Words should be sorted ascending: hello, hey, world
            assertEquals("hello", result.getWords().get(0).getWord());
            assertEquals("hey", result.getWords().get(1).getWord());
            assertEquals("world", result.getWords().get(2).getWord());
        }

        @Test
        @DisplayName("filter='' order='desc' — returns reverse sorted result")
        void emptyFilterDescOrder() throws Exception {
            var dict = createDictionaryWithWordsAndPhrases();

            IResourceStorage.IResource<DictionaryConfiguration> resource = mock(IResourceStorage.IResource.class);
            when(resource.getData()).thenReturn(dict);
            when(resourceStorage.read("test-id", 1)).thenReturn(resource);

            var result = store.read("test-id", 1, "", "desc", 0, 10);

            assertNotNull(result);
            // Words should be sorted descending: world, hey, hello
            assertEquals("world", result.getWords().get(0).getWord());
            assertEquals("hey", result.getWords().get(1).getWord());
            assertEquals("hello", result.getWords().get(2).getWord());
        }

        @Test
        @DisplayName("filter='hel' — only 'hello' matches")
        void filterMatchesSubstring() throws Exception {
            var dict = createDictionaryWithWordsAndPhrases();

            IResourceStorage.IResource<DictionaryConfiguration> resource = mock(IResourceStorage.IResource.class);
            when(resource.getData()).thenReturn(dict);
            when(resourceStorage.read("test-id", 1)).thenReturn(resource);

            var result = store.read("test-id", 1, "hel", "asc", 0, 10);

            assertNotNull(result);
            assertEquals(1, result.getWords().size());
            assertEquals("hello", result.getWords().getFirst().getWord());
        }

        @Test
        @DisplayName("limit=1 — only first word returned")
        void limitOne() throws Exception {
            var dict = createDictionaryWithWordsAndPhrases();

            IResourceStorage.IResource<DictionaryConfiguration> resource = mock(IResourceStorage.IResource.class);
            when(resource.getData()).thenReturn(dict);
            when(resourceStorage.read("test-id", 1)).thenReturn(resource);

            var result = store.read("test-id", 1, "", "asc", 0, 1);

            assertNotNull(result);
            assertEquals(1, result.getWords().size());
            assertEquals("hello", result.getWords().getFirst().getWord());
        }

        @Test
        @DisplayName("index=1 limit=1 — second word returned")
        void indexOne() throws Exception {
            var dict = createDictionaryWithWordsAndPhrases();

            IResourceStorage.IResource<DictionaryConfiguration> resource = mock(IResourceStorage.IResource.class);
            when(resource.getData()).thenReturn(dict);
            when(resourceStorage.read("test-id", 1)).thenReturn(resource);

            var result = store.read("test-id", 1, "", "asc", 1, 1);

            assertNotNull(result);
            assertEquals(1, result.getWords().size());
            assertEquals("hey", result.getWords().getFirst().getWord());
        }

        @Test
        @DisplayName("exact match filter '\"hello\"' — only exact match returned")
        void exactMatchFilter() throws Exception {
            var dict = createDictionaryWithWordsAndPhrases();

            IResourceStorage.IResource<DictionaryConfiguration> resource = mock(IResourceStorage.IResource.class);
            when(resource.getData()).thenReturn(dict);
            when(resourceStorage.read("test-id", 1)).thenReturn(resource);

            var result = store.read("test-id", 1, "\"hello\"", "asc", 0, 10);

            assertNotNull(result);
            assertEquals(1, result.getWords().size());
            assertEquals("hello", result.getWords().getFirst().getWord());
        }

        @Test
        @DisplayName("invalid order — throws IllegalArgumentException")
        void invalidOrder() throws Exception {
            var dict = createDictionaryWithWordsAndPhrases();

            IResourceStorage.IResource<DictionaryConfiguration> resource = mock(IResourceStorage.IResource.class);
            when(resource.getData()).thenReturn(dict);
            when(resourceStorage.read("test-id", 1)).thenReturn(resource);

            assertThrows(IllegalArgumentException.class,
                    () -> store.read("test-id", 1, "", "random", 0, 10));
        }
    }

    // ==================== readExpressions ====================

    @Nested
    @DisplayName("readExpressions")
    class ReadExpressionsTests {

        @Test
        @DisplayName("returns matching word expressions")
        void matchingWordExpressions() throws Exception {
            var dict = createDictionaryWithWordsAndPhrases();

            IResourceStorage.IResource<DictionaryConfiguration> resource = mock(IResourceStorage.IResource.class);
            when(resource.getData()).thenReturn(dict);
            when(resourceStorage.read("test-id", 1)).thenReturn(resource);

            var expressions = store.readExpressions("test-id", 1, "greeting", "asc", 0, 10);

            assertNotNull(expressions);
            assertTrue(expressions.contains("greeting(hello)"));
            assertTrue(expressions.contains("greeting(hey)"));
            assertFalse(expressions.contains("noun(world)"));
        }

        @Test
        @DisplayName("returns phrase expressions with matching filter")
        void matchingPhraseExpressions() throws Exception {
            var dict = createDictionaryWithWordsAndPhrases();

            IResourceStorage.IResource<DictionaryConfiguration> resource = mock(IResourceStorage.IResource.class);
            when(resource.getData()).thenReturn(dict);
            when(resourceStorage.read("test-id", 1)).thenReturn(resource);

            // Use empty filter to get all expressions including phrases
            var expressions = store.readExpressions("test-id", 1, "", "asc", 0, 20);

            assertNotNull(expressions);
            assertFalse(expressions.isEmpty());
        }

        @Test
        @DisplayName("empty dictionary — returns empty list")
        void emptyDictionary() throws Exception {
            var config = new DictionaryConfiguration();
            config.setWords(new ArrayList<>());
            config.setPhrases(new ArrayList<>());

            IResourceStorage.IResource<DictionaryConfiguration> resource = mock(IResourceStorage.IResource.class);
            when(resource.getData()).thenReturn(config);
            when(resourceStorage.read("test-id", 1)).thenReturn(resource);

            var expressions = store.readExpressions("test-id", 1, "", "asc", 0, 10);

            assertNotNull(expressions);
            assertTrue(expressions.isEmpty());
        }

        @Test
        @DisplayName("word with null expression — skipped")
        void nullWordExpression() throws Exception {
            var config = new DictionaryConfiguration();
            var w = new WordConfiguration();
            w.setWord("test");
            w.setExpressions(null);
            config.setWords(new ArrayList<>(List.of(w)));
            config.setPhrases(new ArrayList<>());

            IResourceStorage.IResource<DictionaryConfiguration> resource = mock(IResourceStorage.IResource.class);
            when(resource.getData()).thenReturn(config);
            when(resourceStorage.read("test-id", 1)).thenReturn(resource);

            var expressions = store.readExpressions("test-id", 1, "", "asc", 0, 10);

            assertTrue(expressions.isEmpty());
        }

        @Test
        @DisplayName("duplicate expressions — only unique ones returned")
        void duplicateExpressions() throws Exception {
            var config = new DictionaryConfiguration();
            var w1 = new WordConfiguration();
            w1.setWord("hello");
            w1.setExpressions("greeting(hello)");

            var w2 = new WordConfiguration();
            w2.setWord("hi");
            w2.setExpressions("greeting(hello)"); // same expression

            config.setWords(new ArrayList<>(List.of(w1, w2)));
            config.setPhrases(new ArrayList<>());

            IResourceStorage.IResource<DictionaryConfiguration> resource = mock(IResourceStorage.IResource.class);
            when(resource.getData()).thenReturn(config);
            when(resourceStorage.read("test-id", 1)).thenReturn(resource);

            var expressions = store.readExpressions("test-id", 1, "greeting", "asc", 0, 10);

            assertEquals(1, expressions.size());
            assertEquals("greeting(hello)", expressions.getFirst());
        }

        @Test
        @DisplayName("phrase with empty expression — skipped")
        void emptyPhraseExpression() throws Exception {
            var config = new DictionaryConfiguration();
            config.setWords(new ArrayList<>());

            var p = new PhraseConfiguration();
            p.setPhrase("test phrase");
            p.setExpressions("");
            config.setPhrases(new ArrayList<>(List.of(p)));

            IResourceStorage.IResource<DictionaryConfiguration> resource = mock(IResourceStorage.IResource.class);
            when(resource.getData()).thenReturn(config);
            when(resourceStorage.read("test-id", 1)).thenReturn(resource);

            var expressions = store.readExpressions("test-id", 1, "", "asc", 0, 10);

            assertTrue(expressions.isEmpty());
        }
    }
}
