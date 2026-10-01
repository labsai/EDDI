/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api;

import ai.labs.eddi.configs.variables.IGlobalVariableStore;
import ai.labs.eddi.configs.variables.model.GlobalVariable;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static ai.labs.eddi.engine.api.OperatorRevisionCheck.Outcome;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OperatorRevisionCheckTest {

    private IGlobalVariableStore store;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        store = mock(IGlobalVariableStore.class);
    }

    /**
     * A check whose "classpath" holds the given revision file content, or nothing.
     */
    private OperatorRevisionCheck checkShipping(String revisionFile) {
        return new OperatorRevisionCheck(store, objectMapper) {
            @Override
            InputStream openRevisionResource() {
                return revisionFile == null ? null : new ByteArrayInputStream(revisionFile.getBytes(StandardCharsets.UTF_8));
            }
        };
    }

    private void storeOperator(String json) {
        when(store.get(GlobalVariable.DEFAULT_TENANT, OperatorRevisionCheck.OPERATOR_VARIABLE))
                .thenReturn(new GlobalVariable(GlobalVariable.DEFAULT_TENANT, OperatorRevisionCheck.OPERATOR_VARIABLE, json, null, false));
    }

    @Test
    void reportsOutdatedForAConfigWrittenBeforeRevisionsExisted() {
        // The operator this check exists to find: no provisionedRevision at all.
        storeOperator("{\"enabled\":true,\"agentId\":\"6a1b2c3d4e5f60718293a4b5\",\"version\":3}");
        assertEquals(Outcome.OUTDATED, checkShipping("{\"revision\":1,\"fingerprint\":\"x\"}").check());
    }

    @Test
    void reportsOutdatedForAnOlderRevision() {
        storeOperator("{\"enabled\":true,\"agentId\":\"6a1b2c3d4e5f60718293a4b5\",\"provisionedRevision\":1}");
        assertEquals(Outcome.OUTDATED, checkShipping("{\"revision\":2}").check());
    }

    @Test
    void reportsCurrentForTheSameOrANewerRevision() {
        storeOperator("{\"enabled\":true,\"agentId\":\"6a1b2c3d4e5f60718293a4b5\",\"provisionedRevision\":2}");
        assertEquals(Outcome.CURRENT, checkShipping("{\"revision\":2}").check());
        // A rollback to an older jar must not nag about a NEWER operator.
        assertEquals(Outcome.CURRENT, checkShipping("{\"revision\":1}").check());
    }

    @Test
    void ignoresAPausedOperator() {
        // Turning it back on redeploys the old agent, but activating it again builds
        // the current one — and a paused operator answers nobody meanwhile.
        storeOperator("{\"enabled\":false,\"agentId\":\"6a1b2c3d4e5f60718293a4b5\",\"provisionedRevision\":0}");
        assertEquals(Outcome.NOT_ACTIVE, checkShipping("{\"revision\":2}").check());
    }

    @Test
    void ignoresAnEnabledConfigWithoutAnAgent() {
        storeOperator("{\"enabled\":true,\"agentId\":null}");
        assertEquals(Outcome.NOT_ACTIVE, checkShipping("{\"revision\":2}").check());
    }

    @Test
    void notConfiguredWhenTheVariableIsMissingOrUnreadable() {
        assertEquals(Outcome.NOT_CONFIGURED, checkShipping("{\"revision\":2}").check());
        storeOperator("not json");
        assertEquals(Outcome.NOT_CONFIGURED, checkShipping("{\"revision\":2}").check());
        storeOperator("[1,2]");
        assertEquals(Outcome.NOT_CONFIGURED, checkShipping("{\"revision\":2}").check());
    }

    @Test
    void skipsWhenTheBuildShipsNoRevisionFile() {
        storeOperator("{\"enabled\":true,\"agentId\":\"6a1b2c3d4e5f60718293a4b5\"}");
        assertEquals(Outcome.NO_MANIFEST, checkShipping(null).check());
        assertEquals(Outcome.NO_MANIFEST, checkShipping("{\"fingerprint\":\"x\"}").check());
    }

    @Test
    void theRealBuildPutsTheManagersRevisionFileOnTheClasspath() throws Exception {
        // Guards the pom.xml resource entry: without it this check silently never
        // runs (NO_MANIFEST) on any real deployment.
        var check = new OperatorRevisionCheck(store, objectMapper);
        try (InputStream in = check.openRevisionResource()) {
            assertNotNull(in, OperatorRevisionCheck.REVISION_RESOURCE + " is missing from the classpath — see pom.xml <resources>");
        }
        assertTrue(check.shippedRevision().isPresent());
        assertTrue(check.shippedRevision().getAsInt() >= 1);
    }
}
