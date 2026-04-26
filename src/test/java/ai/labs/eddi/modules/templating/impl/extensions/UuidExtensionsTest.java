/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating.impl.extensions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for UUID extension methods in {@link EddiTemplateExtensions}. Replaces
 * the old UUIDWrapperTest.
 */
class UuidExtensionsTest {

    @Test
    void extractId_withMongoObjectId() {
        assertEquals("6740832a2b0f614abcaee7ab",
                EddiTemplateExtensions.extractId("http://localhost:7070/rulestore/rulesets/6740832a2b0f614abcaee7ab?version=1"));
    }

    @Test
    void extractId_withPostgresUUID() {
        assertEquals("f3be2bcd-aff3-41f0-9a1a-cf4eb513dd81",
                EddiTemplateExtensions.extractId("http://localhost:7070/rulestore/rulesets/f3be2bcd-aff3-41f0-9a1a-cf4eb513dd81?version=1"));
    }

    @Test
    void extractId_agentLocation() {
        assertEquals("6740832b2b0f614abcaee7ca",
                EddiTemplateExtensions.extractId("http://localhost:7070/agentstore/agents/6740832b2b0f614abcaee7ca?version=1"));
    }

    @Test
    void extractId_packageLocation() {
        assertEquals("c1d2e3f4-a5b6-47c8-9d0e-f1a2b3c4d5e6",
                EddiTemplateExtensions.extractId("http://localhost:7070/workflowstore/workflows/c1d2e3f4-a5b6-47c8-9d0e-f1a2b3c4d5e6?version=2"));
    }

    @Test
    void extractId_withoutVersion() {
        assertEquals("abc123", EddiTemplateExtensions.extractId("http://localhost:7070/store/resources/abc123"));
    }

    @Test
    void extractId_nullInput() {
        assertEquals("", EddiTemplateExtensions.extractId(null));
    }

    @Test
    void extractId_emptyInput() {
        assertEquals("", EddiTemplateExtensions.extractId(""));
    }

    @Test
    void extractVersion_withMongoObjectId() {
        assertEquals("1", EddiTemplateExtensions.extractVersion("http://localhost:7070/rulestore/rulesets/6740832a2b0f614abcaee7ab?version=1"));
    }

    @Test
    void extractVersion_withPostgresUUID() {
        assertEquals("3",
                EddiTemplateExtensions.extractVersion("http://localhost:7070/rulestore/rulesets/f3be2bcd-aff3-41f0-9a1a-cf4eb513dd81?version=3"));
    }

    @Test
    void extractVersion_noVersion() {
        assertEquals("", EddiTemplateExtensions.extractVersion("http://localhost:7070/store/resources/abc123"));
    }

    @Test
    void extractVersion_nullInput() {
        assertEquals("", EddiTemplateExtensions.extractVersion(null));
    }

    @Test
    void generateUUID_returnsValidUUID() {
        String uuid = EddiTemplateExtensions.generateUUID();
        assertEquals(36, uuid.length());
        assertEquals(4, uuid.chars().filter(c -> c == '-').count());
    }
}
