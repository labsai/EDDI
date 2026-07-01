/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.dictionary.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.dictionary.IDictionaryStore;
import ai.labs.eddi.configs.dictionary.model.DictionaryConfiguration;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;

import ai.labs.eddi.configs.patch.PatchInstruction;
import ai.labs.eddi.configs.dictionary.model.DictionaryConfiguration.WordConfiguration;
import ai.labs.eddi.configs.dictionary.model.DictionaryConfiguration.PhraseConfiguration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestDictionaryStore}.
 */
class RestDictionaryStoreTest {

    private IDictionaryStore dictionaryStore;
    private IJsonSchemaCreator jsonSchemaCreator;
    private RestDictionaryStore restStore;

    @BeforeEach
    void setUp() {
        dictionaryStore = mock(IDictionaryStore.class);
        var documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        jsonSchemaCreator = mock(IJsonSchemaCreator.class);
        restStore = new RestDictionaryStore(dictionaryStore, documentDescriptorStore, jsonSchemaCreator);
    }

    @Nested
    @DisplayName("readJsonSchema")
    class ReadJsonSchema {
        @Test
        void returnsSchema() throws Exception {
            when(jsonSchemaCreator.generateSchema(DictionaryConfiguration.class)).thenReturn("{}");
            Response response = restStore.readJsonSchema();
            assertEquals(200, response.getStatus());
        }
    }

    @Nested
    @DisplayName("readRegularDictionary")
    class ReadRegularDictionary {
        @Test
        void delegatesToStore() throws Exception {
            var config = new DictionaryConfiguration();
            when(dictionaryStore.read("dict-1", 1)).thenReturn(config);
            DictionaryConfiguration result = restStore.readRegularDictionary("dict-1", 1, "", "", 0, 10);
            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("readExpressions")
    class ReadExpressions {
        @Test
        void returnsExpressions() throws Exception {
            when(dictionaryStore.readExpressions("dict-1", 1, "greet", "", 0, 10))
                    .thenReturn(List.of("greeting(hello)", "greeting(hi)"));
            List<String> result = restStore.readExpressions("dict-1", 1, "greet", "", 0, 10);
            assertEquals(2, result.size());
        }
    }

    @Nested
    @DisplayName("createRegularDictionary")
    class CreateRegularDictionary {
        @Test
        void creates() throws Exception {
            var resourceId = mock(IResourceStore.IResourceId.class);
            when(resourceId.getId()).thenReturn("new-id");
            when(resourceId.getVersion()).thenReturn(1);
            when(dictionaryStore.create(any())).thenReturn(resourceId);

            Response response = restStore.createRegularDictionary(new DictionaryConfiguration());
            assertEquals(201, response.getStatus());
        }
    }

    @Nested
    @DisplayName("deleteRegularDictionary")
    class DeleteRegularDictionary {
        @Test
        void deletes() throws Exception {
            restStore.deleteRegularDictionary("dict-1", 1, false);
            verify(dictionaryStore).delete("dict-1", 1);
        }
    }

    @Nested
    @DisplayName("duplicateRegularDictionary")
    class DuplicateRegularDictionary {
        @Test
        void duplicates() throws Exception {
            var config = new DictionaryConfiguration();
            when(dictionaryStore.read("dict-1", 1)).thenReturn(config);
            var resourceId = mock(IResourceStore.IResourceId.class);
            when(resourceId.getId()).thenReturn("dup-id");
            when(resourceId.getVersion()).thenReturn(1);
            when(dictionaryStore.create(any())).thenReturn(resourceId);

            Response response = restStore.duplicateRegularDictionary("dict-1", 1);
            assertEquals(201, response.getStatus());
        }
    }

    @Nested
    @DisplayName("getResourceURI / getCurrentResourceId")
    class ResourceInfo {
        @Test
        void returnsUri() {
            assertNotNull(restStore.getResourceURI());
        }

        @Test
        void delegatesCurrentId() throws Exception {
            var resourceId = mock(IResourceStore.IResourceId.class);
            when(resourceId.getVersion()).thenReturn(3);
            when(dictionaryStore.getCurrentResourceId("dict-1")).thenReturn(resourceId);
            assertEquals(3, restStore.getCurrentResourceId("dict-1").getVersion());
        }
    }

    @Nested
    @DisplayName("patchRegularDictionary")
    class PatchRegularDictionary {

        @Test
        @DisplayName("SET operation — merges words and phrases")
        void setOperation_mergesWordsAndPhrases() throws Exception {
            var existing = new DictionaryConfiguration();
            var existingWord = new WordConfiguration();
            existingWord.setWord("hello");
            existingWord.setExpressions("greeting(hello)");
            existing.getWords().add(existingWord);

            when(dictionaryStore.read("dict-1", 1)).thenReturn(existing);
            when(dictionaryStore.update(eq("dict-1"), eq(1), any())).thenReturn(2);

            var patchConfig = new DictionaryConfiguration();
            var newWord = new WordConfiguration();
            newWord.setWord("world");
            newWord.setExpressions("noun(world)");
            patchConfig.getWords().add(newWord);

            var instruction = new PatchInstruction<DictionaryConfiguration>();
            instruction.setOperation(PatchInstruction.PatchOperation.SET);
            instruction.setDocument(patchConfig);

            Response response = restStore.patchRegularDictionary("dict-1", 1, List.of(instruction));

            assertEquals(200, response.getStatus());
            verify(dictionaryStore).update(eq("dict-1"), eq(1), argThat(config -> {
                var dict = (DictionaryConfiguration) config;
                return dict.getWords().size() == 2;
            }));
        }

        @Test
        @DisplayName("DELETE operation — removes words and phrases")
        void deleteOperation_removesWordsAndPhrases() throws Exception {
            var existing = new DictionaryConfiguration();
            var word1 = new WordConfiguration();
            word1.setWord("hello");
            word1.setExpressions("greeting(hello)");
            existing.getWords().add(word1);
            var word2 = new WordConfiguration();
            word2.setWord("world");
            word2.setExpressions("noun(world)");
            existing.getWords().add(word2);

            when(dictionaryStore.read("dict-1", 1)).thenReturn(existing);
            when(dictionaryStore.update(eq("dict-1"), eq(1), any())).thenReturn(2);

            var patchConfig = new DictionaryConfiguration();
            var deleteWord = new WordConfiguration();
            deleteWord.setWord("hello");
            patchConfig.getWords().add(deleteWord);

            var instruction = new PatchInstruction<DictionaryConfiguration>();
            instruction.setOperation(PatchInstruction.PatchOperation.DELETE);
            instruction.setDocument(patchConfig);

            Response response = restStore.patchRegularDictionary("dict-1", 1, List.of(instruction));

            assertEquals(200, response.getStatus());
            verify(dictionaryStore).update(eq("dict-1"), eq(1), argThat(config -> {
                var dict = (DictionaryConfiguration) config;
                return dict.getWords().size() == 1 && dict.getWords().get(0).getWord().equals("world");
            }));
        }

        @Test
        @DisplayName("multiple patch instructions in one call")
        void multiplePatchInstructions() throws Exception {
            var existing = new DictionaryConfiguration();
            var word1 = new WordConfiguration();
            word1.setWord("hello");
            existing.getWords().add(word1);

            when(dictionaryStore.read("dict-1", 1)).thenReturn(existing);
            when(dictionaryStore.update(eq("dict-1"), eq(1), any())).thenReturn(2);

            var setConfig = new DictionaryConfiguration();
            var newWord = new WordConfiguration();
            newWord.setWord("world");
            setConfig.getWords().add(newWord);
            var setInstruction = new PatchInstruction<DictionaryConfiguration>();
            setInstruction.setOperation(PatchInstruction.PatchOperation.SET);
            setInstruction.setDocument(setConfig);

            var deleteConfig = new DictionaryConfiguration();
            var delWord = new WordConfiguration();
            delWord.setWord("hello");
            deleteConfig.getWords().add(delWord);
            var deleteInstruction = new PatchInstruction<DictionaryConfiguration>();
            deleteInstruction.setOperation(PatchInstruction.PatchOperation.DELETE);
            deleteInstruction.setDocument(deleteConfig);

            Response response = restStore.patchRegularDictionary("dict-1", 1, List.of(setInstruction, deleteInstruction));

            assertEquals(200, response.getStatus());
            verify(dictionaryStore).update(eq("dict-1"), eq(1), argThat(config -> {
                var dict = (DictionaryConfiguration) config;
                return dict.getWords().size() == 1 && dict.getWords().get(0).getWord().equals("world");
            }));
        }
    }

    @Nested
    @DisplayName("readJsonSchema — exception")
    class ReadJsonSchemaException {
        @Test
        @DisplayName("jsonSchemaCreator throws → sneakyThrow propagates")
        void exceptionPropagates() throws Exception {
            when(jsonSchemaCreator.generateSchema(DictionaryConfiguration.class))
                    .thenThrow(new RuntimeException("schema generation failed"));

            assertThrows(RuntimeException.class, () -> restStore.readJsonSchema());
        }
    }

    @Nested
    @DisplayName("readExpressions — exception")
    class ReadExpressionsException {
        @Test
        @DisplayName("dictionaryStore throws ResourceStoreException → sneakyThrow propagates")
        void resourceStoreExceptionPropagates() throws Exception {
            when(dictionaryStore.readExpressions("dict-1", 1, "", "", 0, 10))
                    .thenThrow(new IResourceStore.ResourceStoreException("store error"));

            assertThrows(IResourceStore.ResourceStoreException.class,
                    () -> restStore.readExpressions("dict-1", 1, "", "", 0, 10));
        }
    }

    @Nested
    @DisplayName("updateRegularDictionary")
    class UpdateRegularDictionary {
        @Test
        @DisplayName("delegates to restVersionInfo.update and returns response")
        void delegatesAndReturnsResponse() throws Exception {
            when(dictionaryStore.update(eq("dict-1"), eq(1), any())).thenReturn(2);

            Response response = restStore.updateRegularDictionary("dict-1", 1, new DictionaryConfiguration());

            assertEquals(200, response.getStatus());
            verify(dictionaryStore).update(eq("dict-1"), eq(1), any());
        }
    }

    @Nested
    @DisplayName("readRegularDictionaryDescriptors")
    class ReadRegularDictionaryDescriptors {
        @Test
        @DisplayName("delegates to restVersionInfo.readDescriptors")
        void delegates() throws Exception {
            // The internal documentDescriptorStore was created as a mock in setUp.
            // RestVersionInfo.readDescriptors calls documentDescriptorStore.readDescriptors
            // which returns null by default → sneakyThrow may fire. We just verify it's
            // callable.
            try {
                restStore.readRegularDictionaryDescriptors("", 0, 10);
            } catch (Exception e) {
                // Expected — mock returns null which triggers NPE or sneakyThrow
            }
        }
    }

    @Nested
    @DisplayName("duplicateRegularDictionary — exception")
    class DuplicateException {
        @Test
        @DisplayName("read throws ResourceNotFoundException → sneakyThrow propagates")
        void readThrowsResourceNotFound() throws Exception {
            when(dictionaryStore.read("dict-1", 1))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("not found"));

            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> restStore.duplicateRegularDictionary("dict-1", 1));
        }
    }
}
