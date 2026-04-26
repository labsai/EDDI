/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LifecycleUtilitiesTest {

    @Test
    void createComponentKey_concatenatesFields() {
        String key = LifecycleUtilities.createComponentKey("workflow-1", 3, 0);
        assertEquals("workflow-1:3:0", key);
    }

    @Test
    void createComponentKey_differentVersions_differentKeys() {
        String key1 = LifecycleUtilities.createComponentKey("wf", 1, 0);
        String key2 = LifecycleUtilities.createComponentKey("wf", 2, 0);
        assertNotEquals(key1, key2);
    }

    @Test
    void createComponentKey_differentSteps_differentKeys() {
        String key1 = LifecycleUtilities.createComponentKey("wf", 1, 0);
        String key2 = LifecycleUtilities.createComponentKey("wf", 1, 1);
        assertNotEquals(key1, key2);
    }

    @Test
    void createComponentKey_nullWorkflowId_handlesGracefully() {
        String key = LifecycleUtilities.createComponentKey(null, 1, 0);
        assertEquals("null:1:0", key);
    }
}
