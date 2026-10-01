/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IResourceSource.ExtensionSourceData;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The dictionaries a parser document names are read from the archive like any
 * other extension, keyed under the document — the key the target side derives
 * the same way, which is what lets the two be matched.
 */
@DisplayName("ZipResourceSource — dictionaries a parser document names")
class ZipResourceSourceParserTest {

    private static final String WF_ID = "bbbb11112222333344445555";
    private static final String PARSER_ID = "cccc11112222333344445555";
    private static final String DICT_ID = "dddd11112222333344445555";
    private static final String DICT_URI = "eddi://ai.labs.dictionary/dictionarystore/dictionaries/" + DICT_ID
            + "?version=1";
    private static final String PARSER_KEY = "eddi://ai.labs.parser#0/config";

    private final ObjectMapper mapper = new ObjectMapper();
    private Path root;
    private IJsonSerialization jsonSerialization;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("eddi-zip-parser");
        jsonSerialization = mock(IJsonSerialization.class);
        when(jsonSerialization.deserialize(anyString(), eq(AgentConfiguration.class)))
                .thenAnswer(inv -> mapper.readValue((String) inv.getArgument(0), AgentConfiguration.class));
        when(jsonSerialization.deserialize(anyString(), eq(WorkflowConfiguration.class)))
                .thenAnswer(inv -> mapper.readValue((String) inv.getArgument(0), WorkflowConfiguration.class));
        when(jsonSerialization.deserialize(anyString()))
                .thenAnswer(inv -> mapper.readValue((String) inv.getArgument(0), Object.class));
    }

    @AfterEach
    void tearDown() throws IOException {
        // Closing the source deletes its directory; this is for a test that failed
        // first.
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    @Test
    @DisplayName("a dictionary only the parser document names is keyed under the document")
    void dictionaryIsKeyedUnderTheDocument() throws Exception {
        Map<String, ExtensionSourceData> extensions = extensionsOf(false);

        ExtensionSourceData dictionary = extensions.get(
                PARSER_KEY + WorkflowExtensions.DOCUMENT_MARKER + "/extensions/dictionaries/0/config");
        assertEquals(DICT_ID, dictionary == null ? null : dictionary.sourceId(), "got keys " + extensions.keySet());
        assertTrue(WorkflowExtensions.isInDocument(
                PARSER_KEY + WorkflowExtensions.DOCUMENT_MARKER + "/extensions/dictionaries/0/config"));
    }

    @Test
    @DisplayName("a dictionary the workflow names as well is read once, under the workflow's key")
    void sharedDictionaryIsReadOnce() throws Exception {
        Map<String, ExtensionSourceData> extensions = extensionsOf(true);

        assertEquals(1, extensions.values().stream().filter(e -> DICT_ID.equals(e.sourceId())).count(),
                "got keys " + extensions.keySet());
        assertTrue(extensions.containsKey("eddi://ai.labs.parser#0/extensions/dictionaries/0/config"));
    }

    private Map<String, ExtensionSourceData> extensionsOf(boolean stepNamesDictionaryToo) throws Exception {
        String dictionaryRef = "{\"type\":\"eddi://ai.labs.parser.dictionaries.regular\",\"config\":{\"uri\":\""
                + DICT_URI + "\"}}";
        Files.writeString(root.resolve("aaaa11112222333344445555.agent.json"),
                "{\"workflows\":[\"eddi://ai.labs.workflow/workflowstore/workflows/" + WF_ID + "?version=1\"]}");
        Path versionDir = Files.createDirectories(root.resolve("agent").resolve(WF_ID).resolve("1"));
        Files.writeString(versionDir.resolve(WF_ID + ".workflow.json"),
                "{\"workflowSteps\":[{\"type\":\"eddi://ai.labs.parser\",\"config\":{\"uri\":"
                        + "\"eddi://ai.labs.parser/parserstore/parsers/" + PARSER_ID + "?version=1\"}"
                        + (stepNamesDictionaryToo ? ",\"extensions\":{\"dictionaries\":[" + dictionaryRef + "]}" : "")
                        + "}]}");
        Files.writeString(versionDir.resolve(PARSER_ID + ".parser.json"),
                "{\"extensions\":{\"dictionaries\":[" + dictionaryRef + "]},\"config\":{}}");
        Files.writeString(versionDir.resolve(DICT_ID + ".regulardictionary.json"), "{\"words\":[]}");

        try (var source = new ZipResourceSource(root, jsonSerialization)) {
            return source.readWorkflows().getFirst().extensions();
        }
    }
}
