/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.output.mongo;

import ai.labs.eddi.configs.output.model.OutputConfiguration;
import ai.labs.eddi.configs.output.model.OutputConfigurationSet;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.DocumentBuilder;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("OutputStore — save-time validation of timesOccurred")
class OutputStoreSaveValidationTest {

    private IResourceStorage<OutputConfigurationSet> resourceStorage;
    private OutputStore outputStore;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        var storageFactory = mock(IResourceStorageFactory.class);
        resourceStorage = mock(IResourceStorage.class);
        when(storageFactory.create(eq("outputs"), any(), eq(OutputConfigurationSet.class), any(String[].class))).thenReturn(resourceStorage);
        ObjectMapper objectMapper = SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false);
        outputStore = new OutputStore(storageFactory, new DocumentBuilder(new JsonSerialization(objectMapper)));
    }

    private static OutputConfigurationSet setWith(int timesOccurred) {
        var set = new OutputConfigurationSet();
        set.setOutputSet(List.of(new OutputConfiguration("greet", timesOccurred, List.of(), List.of())));
        return set;
    }

    @Test
    @DisplayName("timesOccurred below -1 is refused on create and update, naming the entry")
    void refusedOnSave() throws Exception {
        var thrown = assertThrows(IllegalArgumentException.class, () -> outputStore.create(setWith(-2)));
        assertTrue(thrown.getMessage().contains("greet") && thrown.getMessage().contains("-2"), thrown.getMessage());
        assertThrows(IllegalArgumentException.class, () -> outputStore.update("id", 1, setWith(-7)));
        verify(resourceStorage, never()).newResource(any());
    }

    @Test
    @DisplayName("0 and -1 are accepted")
    void validValuesAccepted() throws Exception {
        outputStore.create(setWith(0));
        outputStore.create(setWith(-1));
        verify(resourceStorage, times(2)).newResource(any());
    }
}
