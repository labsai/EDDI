/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rules.impl;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * A behavior step without a usable {@code config.uri} fails its deployment with
 * a {@link WorkflowConfigurationException} naming the step — it used to be a
 * {@code NullPointerException} (missing) or an {@code IllegalArgumentException}
 * (malformed) from deep inside {@code configure()}.
 */
class RulesEvaluationTaskConfigureUriTest {

    private final RulesEvaluationTask task = new RulesEvaluationTask(mock(IResourceClientLibrary.class), mock(IJsonSerialization.class),
            mock(IRuleDeserialization.class), mock(IExpressionProvider.class));

    @Test
    @DisplayName("missing uri")
    void missingUri() {
        var e = assertThrows(WorkflowConfigurationException.class, () -> task.configure(new HashMap<>(), Map.of()));
        assertTrue(e.getMessage().contains(RulesEvaluationTask.ID), e.getMessage());
    }

    @Test
    @DisplayName("malformed uri")
    void malformedUri() {
        assertThrows(WorkflowConfigurationException.class,
                () -> task.configure(new HashMap<>(Map.of("uri", "eddi://ai.labs.rules/rulestore/rulesets/a b?version=1")), Map.of()));
    }
}
