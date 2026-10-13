/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.output;

import ai.labs.eddi.configs.output.model.OutputConfiguration;
import ai.labs.eddi.configs.output.model.OutputConfigurationSet;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.output.impl.OutputGenerationTask;
import ai.labs.eddi.modules.output.model.QuickReply;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code configure()} receives the output set from the resource cache, shared
 * by every deployment that reads it. It used to sort that list in place.
 */
class OutputGenerationTaskSharedConfigTest {

    @Test
    @DisplayName("configure leaves the cached output set's order untouched and keeps isDefault")
    void configureDoesNotMutateTheCachedSet() throws Exception {
        var set = new OutputConfigurationSet();
        set.setOutputSet(new ArrayList<>(List.of(entry("zeta", false), entry("alpha", true))));
        var resources = mock(IResourceClientLibrary.class);
        when(resources.getResource(any(URI.class), eq(OutputConfigurationSet.class))).thenReturn(set);

        var task = new OutputGenerationTask(resources, mock(IDataFactory.class), new ObjectMapper());
        task.configure(Map.of("uri", "eddi://ai.labs.output/outputstore/outputsets/aabbccddeeff112233445566?version=1"), Map.of());

        assertEquals(List.of("zeta", "alpha"), set.getOutputSet().stream().map(OutputConfiguration::getAction).toList());
    }

    private static OutputConfiguration entry(String action, boolean isDefault) {
        var configuration = new OutputConfiguration();
        configuration.setAction(action);
        configuration.setTimesOccurred(0);
        configuration.setOutputs(List.of());
        configuration.setQuickReplies(List.of(new QuickReply(action, action, isDefault)));
        return configuration;
    }
}
