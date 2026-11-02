/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("A2AToolProviderManager.distinctNamePart")
class A2ASkillNamePartTest {

    @Test
    @DisplayName("an id that fits is unchanged, and null stays null")
    void shortIdUnchanged() {
        assertEquals("translate", A2AToolProviderManager.distinctNamePart("translate"));
        assertNull(A2AToolProviderManager.distinctNamePart(null));
    }

    @Test
    @DisplayName("two long ids sharing their first characters stay distinct, and both fit the bound")
    void longIdsStayDistinct() {
        String prefix = "x".repeat(A2AToolProviderManager.MAX_NAME_PART_CHARS + 10);
        String first = A2AToolProviderManager.distinctNamePart(prefix + "-alpha");
        String second = A2AToolProviderManager.distinctNamePart(prefix + "-beta");

        assertNotEquals(first, second, "the old truncation made these the same tool name");
        assertTrue(first.length() <= A2AToolProviderManager.MAX_NAME_PART_CHARS, first);
        assertTrue(second.length() <= A2AToolProviderManager.MAX_NAME_PART_CHARS, second);
        // Deterministic: the same id always yields the same name.
        assertEquals(first, A2AToolProviderManager.distinctNamePart(prefix + "-alpha"));
    }
}
