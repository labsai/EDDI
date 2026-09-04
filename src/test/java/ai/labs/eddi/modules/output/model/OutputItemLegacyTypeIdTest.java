/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.output.model;

import ai.labs.eddi.configs.migration.LegacyDocumentMigrations;
import ai.labs.eddi.configs.output.model.OutputConfigurationSet;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import ai.labs.eddi.modules.output.model.types.AgentFaceOutputItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A v5 output set that uses the avatar item must still load.
 * <p>
 * The polymorphic type id was renamed {@code botFace} → {@code agentFace} with
 * no alias and no {@code defaultImpl}. Unlike an unknown <em>property</em>, an
 * unknown <em>type id</em> is fatal no matter what
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} says: the read throws
 * {@code InvalidTypeIdException}, which becomes a
 * {@code ResourceStoreException} and then a
 * {@code WorkflowConfigurationException}, so one legacy output item takes down
 * the whole agent deployment — not just that one reply.
 */
@DisplayName("OutputItem — retired v5 type id")
class OutputItemLegacyTypeIdTest {

    private static final String V5_OUTPUT_SET = """
            {
              "outputSet": [ {
                "action": "greet",
                "timesOccurred": 0,
                "outputs": [ {
                  "valueAlternatives": [
                    { "type": "botFace", "uri": "https://example.invalid/face.png", "alt": "avatar", "delay": 0 }
                  ]
                } ],
                "quickReplies": []
              } ]
            }
            """;

    /** Exactly how stored documents are read: the shared, lenient recipe. */
    private static ObjectMapper productionMapper() {
        return SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false);
    }

    private static AgentFaceOutputItem firstItemOf(OutputConfigurationSet set) {
        var item = set.getOutputSet().getFirst().getOutputs().getFirst().getValueAlternatives().getFirst();
        return assertInstanceOf(AgentFaceOutputItem.class, item, "the retired id must resolve to the renamed class");
    }

    @Test
    @DisplayName("a 'botFace' item loads as the renamed class")
    void legacyTypeIdIsAccepted() throws IOException {
        var set = productionMapper().readValue(V5_OUTPUT_SET, OutputConfigurationSet.class);

        var avatar = firstItemOf(set);
        assertEquals("https://example.invalid/face.png", avatar.getUri());
        assertEquals("avatar", avatar.getAlt());
    }

    @Test
    @DisplayName("the retired id is canonicalized, not carried back into storage")
    void legacyTypeIdIsCanonicalized() throws IOException {
        var mapper = productionMapper();
        var set = mapper.readValue(V5_OUTPUT_SET, OutputConfigurationSet.class);

        assertEquals(AgentFaceOutputItem.TYPE_ID, firstItemOf(set).getType(),
                "reading a v5 document must not leave the retired discriminator on the item");

        String reSerialized = mapper.writeValueAsString(set);
        assertFalse(reSerialized.contains(AgentFaceOutputItem.LEGACY_TYPE_ID),
                "@JsonTypeInfo(EXISTING_PROPERTY) writes whatever the property holds — the retired id must not "
                        + "round-trip back out to storage or to clients");
    }

    @Test
    @DisplayName("a freshly built avatar item still writes the canonical id")
    void newItemsWriteTheCanonicalId() throws IOException {
        // The retired id is registered on the SAME class as the canonical one, so a
        // resolver that picked the wrong name for the class -> id direction would put
        // "botFace" on every avatar item EDDI writes from now on. Nothing about
        // reading a v5 document is involved here: this is the write path on its own.
        String json = productionMapper().writeValueAsString(new AgentFaceOutputItem("https://example.invalid/f.png", "avatar", 0));

        assertTrue(json.contains("\"type\":\"" + AgentFaceOutputItem.TYPE_ID + "\""), "expected the canonical id, got: " + json);
        assertFalse(json.contains(AgentFaceOutputItem.LEGACY_TYPE_ID));
    }

    @Test
    @DisplayName("templating carries the canonical id, not the legacy one")
    void templatingKeepsTheCanonicalId() throws IOException {
        var set = productionMapper().readValue(V5_OUTPUT_SET, OutputConfigurationSet.class);

        var templated = firstItemOf(set).applyTemplating(s -> s);

        assertEquals(AgentFaceOutputItem.TYPE_ID, templated.getType());
    }

    @Test
    @DisplayName("the document migration normalizes the stored id once")
    @SuppressWarnings("unchecked")
    void migrationRewritesTheStoredId() {
        var document = new Document("outputSet", new ArrayList<>(List.of(new Document("action", "greet").append("outputs",
                new ArrayList<>(List.of(new Document("valueAlternatives", new ArrayList<Object>(
                        List.of(new Document("type", AgentFaceOutputItem.LEGACY_TYPE_ID).append("uri", "u").append("alt", "a"))))))))));

        Document migrated = LegacyDocumentMigrations.output().migrate(document);

        assertNotNull(migrated, "a document carrying the retired id must be reported as changed, so it is rewritten");
        var outputs = (List<Map<String, Object>>) ((List<Map<String, Object>>) migrated.get("outputSet")).getFirst().get("outputs");
        var alternatives = (List<Map<String, Object>>) outputs.getFirst().get("valueAlternatives");
        assertEquals(AgentFaceOutputItem.TYPE_ID, alternatives.getFirst().get("type"));
    }
}
