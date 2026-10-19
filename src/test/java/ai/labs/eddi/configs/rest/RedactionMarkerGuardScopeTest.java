/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rest;

import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.secrets.sanitize.ConfigSecretRedactor;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The import and upgrade paths create every resource type through one method,
 * so their marker check is scoped to the five document types whose REST reads
 * produce the marker; elsewhere the same text is content.
 */
class RedactionMarkerGuardScopeTest {

    private static LlmConfiguration llmWith(String apiKey) {
        var task = new LlmConfiguration.Task();
        task.setType("openai");
        task.setParameters(new LinkedHashMap<>(Map.of("apiKey", apiKey, "modelName", "gpt-5")));
        return new LlmConfiguration(List.of(task));
    }

    @Test
    @DisplayName("a configuration of a redacted store type that carries the marker is refused")
    void redactedTypeIsRefused() {
        assertTrue(RedactionMarkerGuard.isRedactedDocumentType(llmWith("sk-fixturefixturefixturefixture")));
        assertThrows(BadRequestException.class, () -> RedactionMarkerGuard.requireNoneIfRedactedType(llmWith(ConfigSecretRedactor.REDACTED)));
        assertDoesNotThrow(() -> RedactionMarkerGuard.requireNoneIfRedactedType(llmWith("${vault:openai}")));
    }

    @Test
    @DisplayName("the same text in any other document type is content, not a marker")
    void otherTypesAreNotChecked() {
        Map<String, Object> prompt = Map.of("systemMessage", "Editors see " + ConfigSecretRedactor.IN_STRING_REDACTED + " in place of a key.");

        assertFalse(RedactionMarkerGuard.isRedactedDocumentType(prompt));
        assertDoesNotThrow(() -> RedactionMarkerGuard.requireNoneIfRedactedType(prompt));
        assertThrows(BadRequestException.class, () -> RedactionMarkerGuard.requireNone(prompt),
                "the unconditional check the stores use still sees the marker");
    }
}
