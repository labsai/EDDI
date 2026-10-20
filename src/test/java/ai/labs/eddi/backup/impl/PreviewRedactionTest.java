/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.model.ImportPreview;
import ai.labs.eddi.backup.model.ImportPreview.DiffAction;
import ai.labs.eddi.backup.model.ImportPreview.ResourceDiff;
import ai.labs.eddi.secrets.sanitize.ConfigSecretRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The upgrade and sync previews carry the matcher's unredacted target content;
 * this is the copy a caller who is not an administrator gets.
 */
class PreviewRedactionTest {

    private static final String PLAINTEXT = "sk-fixturefixturefixturefixture";
    private static final String LLM_TARGET = "{\"tasks\":[{\"id\":\"main\",\"type\":\"openai\",\"parameters\":{\"apiKey\":\"" + PLAINTEXT
            + "\",\"modelName\":\"gpt-5\"},\"toolCacheScopes\":{\"token\":\"conversation\"}}]}";
    private static final String LLM_SOURCE = LLM_TARGET.replace(PLAINTEXT, "${vault:REDACTED}");
    private static final String HTTPCALLS_TARGET = "{\"httpCalls\":[{\"name\":\"lookup\",\"parameters\":{\"apiKey\":\"the caller's key for the lookup service\"},"
            + "\"request\":{\"headers\":{\"Authorization\":\"Bearer " + PLAINTEXT + "\"}}}]}";

    private static ImportPreview preview(ResourceDiff... diffs) {
        return new ImportPreview("src", "Source", "tgt", "Target", List.of(diffs));
    }

    private static ResourceDiff diff(String type, String source, String target) {
        return new ResourceDiff("s1", type, "name", DiffAction.UPDATE, "t1", 1, "structural", source, target, 0);
    }

    @Test
    @DisplayName("the target's literal becomes the marker; the source's reference placeholder stays")
    void credentialsAreRedacted() throws Exception {
        ImportPreview redacted = PreviewRedaction.forNonAdmin(preview(diff(AbstractBackupService.LLM_EXT, LLM_SOURCE, LLM_TARGET)));

        ResourceDiff llm = redacted.resources().get(0);
        assertEquals(LLM_SOURCE, llm.sourceContent(), "a reference placeholder is not a credential");
        assertFalse(llm.targetContent().contains(PLAINTEXT), llm.targetContent());
        var task = new ObjectMapper().readTree(llm.targetContent()).get("tasks").get(0);
        assertEquals(ConfigSecretRedactor.REDACTED, task.get("parameters").get("apiKey").asText());
        assertEquals("gpt-5", task.get("parameters").get("modelName").asText());
        assertEquals("conversation", task.get("toolCacheScopes").get("token").asText(), "the store's skipped paths apply here too");
    }

    @Test
    @DisplayName("an httpcall's tool-parameter descriptions are not configuration; a literal header is")
    void httpcallSkipsParameterDescriptions() throws Exception {
        ImportPreview redacted = PreviewRedaction.forNonAdmin(preview(diff(AbstractBackupService.HTTPCALLS_EXT, null, HTTPCALLS_TARGET)));

        var call = new ObjectMapper().readTree(redacted.resources().get(0).targetContent()).get("httpCalls").get(0);
        assertEquals("the caller's key for the lookup service", call.get("parameters").get("apiKey").asText());
        assertEquals(ConfigSecretRedactor.REDACTED, call.get("request").get("headers").get("Authorization").asText());
        assertNull(redacted.resources().get(0).sourceContent());
    }

    @Test
    @DisplayName("a preview without a credential is returned as it is")
    void cleanPreviewIsTheSameInstance() {
        ImportPreview clean = preview(diff(AbstractBackupService.LLM_EXT, LLM_SOURCE, LLM_SOURCE),
                diff(AbstractBackupService.BEHAVIOR_EXT, "{\"behaviorGroups\":[]}", null));

        assertSame(clean, PreviewRedaction.forNonAdmin(clean));
        assertNull(PreviewRedaction.forNonAdmin(null));
    }

    @Test
    @DisplayName("content that is not JSON is shape-scanned, never passed through")
    void nonJsonContentIsScanned() {
        ImportPreview redacted = PreviewRedaction.forNonAdmin(preview(diff(AbstractBackupService.PARSER_EXT, "not json, key " + PLAINTEXT, null)));

        assertFalse(redacted.resources().get(0).sourceContent().contains(PLAINTEXT), redacted.resources().get(0).sourceContent());
    }
}
