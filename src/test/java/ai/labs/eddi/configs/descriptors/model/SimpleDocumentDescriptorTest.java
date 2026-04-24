/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.descriptors.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SimpleDocumentDescriptorTest {

    @Test
    void defaultConstructor() {
        var desc = new SimpleDocumentDescriptor();
        assertNull(desc.getName());
        assertNull(desc.getDescription());
    }

    @Test
    void parameterizedConstructor() {
        var desc = new SimpleDocumentDescriptor("Agent 1", "A test agent");
        assertEquals("Agent 1", desc.getName());
        assertEquals("A test agent", desc.getDescription());
    }

    @Test
    void setters() {
        var desc = new SimpleDocumentDescriptor();
        desc.setName("Updated Name");
        desc.setDescription("Updated Description");
        assertEquals("Updated Name", desc.getName());
        assertEquals("Updated Description", desc.getDescription());
    }
}
