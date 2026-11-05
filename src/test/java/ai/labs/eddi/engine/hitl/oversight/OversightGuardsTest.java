/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight;

import ai.labs.eddi.engine.hitl.tools.SelfUngatingGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The self-ungating guard's oversight rules: what an agent's own approved tool
 * call may never do to the oversight of agents.
 */
@DisplayName("oversight guards")
class OversightGuardsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TARGET = "0123456789abcdef01234567";
    private static final String STORED = "{\"toolApprovals\":{\"requireApproval\":[\"http.post:*\"]},"
            + "\"oversight\":{\"sampledAudit\":{\"rules\":[{\"match\":\"http.post:/crm/notes\",\"rate\":0.1}]}}}";

    private static SelfUngatingGuard.Protection protection() {
        SelfUngatingGuard.StoredGateLookup lookup = agentId -> MAPPER.readTree(STORED);
        return new SelfUngatingGuard.Protection("ffffffffffffffffffffffff", Set.of(), false, lookup);
    }

    private static String agentBody(String hitlConfig) {
        return "{\"workflows\":[],\"hitlConfig\":" + hitlConfig + "}";
    }

    @Nested
    @DisplayName("self-ungating guard")
    class SelfUngating {

        @ParameterizedTest
        @CsvSource({"PATCH,/hitl/samples/s1", "POST,/hitl/autonomy/a1/apply?category=x", "POST,/hitl/analytics/rebuild",
                "POST,/hitl/autonomy/a1/lift"})
        @DisplayName("an approved call writing to the oversight API is refused for every agent")
        void oversightApiRefused(String method, String path) {
            assertEquals(OversightWriteGuard.OVERSIGHT_API_REASON,
                    SelfUngatingGuard.refusal(method, "http://localhost:7070" + path, "{}", protection()));
        }

        @Test
        @DisplayName("reading the oversight API is not refused")
        void readsAllowed() {
            assertNull(SelfUngatingGuard.refusal("GET", "http://localhost:7070/hitl/samples?agentId=x", null, protection()));
        }

        @Test
        @DisplayName("a full agent document that changes hitlConfig.oversight is refused")
        void oversightChangeRefused() throws Exception {
            JsonNode changed = MAPPER.readTree(STORED);
            ((ObjectNode) changed.path("oversight").path("sampledAudit").path("rules").get(0)).put("rate",
                    0.0);

            assertEquals(OversightWriteGuard.OVERSIGHT_CONFIG_REASON, SelfUngatingGuard.refusal("PUT",
                    "http://localhost:7070/agentstore/agents/" + TARGET + "?version=1", agentBody(changed.toString()), protection()));
        }

        @Test
        @DisplayName("removing hitlConfig.oversight is refused; a faithful copy is not")
        void removalRefusedCopyAllowed() {
            String uri = "http://localhost:7070/agentstore/agents/" + TARGET + "?version=1";
            assertEquals(OversightWriteGuard.OVERSIGHT_CONFIG_REASON,
                    SelfUngatingGuard.refusal("PUT", uri, agentBody("{\"toolApprovals\":{\"requireApproval\":[\"http.post:*\"]}}"), protection()));
            assertNull(SelfUngatingGuard.refusal("PUT", uri, agentBody(STORED), protection()));
        }
    }
}
