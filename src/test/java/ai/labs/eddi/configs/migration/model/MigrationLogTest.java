/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.migration.model;

import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class MigrationLogTest {

    @Test
    void constructor_withName_setsDefaults() {
        var log = new MigrationLog("v6-qute-migration");

        assertEquals("v6-qute-migration", log.getName());
        assertTrue(log.isFinished());
        assertNotNull(log.getTimestamp());
    }

    @Test
    void defaultConstructor() {
        var log = new MigrationLog();
        assertNull(log.getName());
        assertFalse(log.isFinished());
        assertNull(log.getTimestamp());
    }

    @Test
    void settersAndGetters() {
        var log = new MigrationLog();
        var now = new Date();

        log.setName("test-migration");
        log.setFinished(true);
        log.setTimestamp(now);

        assertEquals("test-migration", log.getName());
        assertTrue(log.isFinished());
        assertEquals(now, log.getTimestamp());
    }

    @Test
    void setFinished_canToggle() {
        var log = new MigrationLog("test");
        assertTrue(log.isFinished());
        log.setFinished(false);
        assertFalse(log.isFinished());
    }
}
