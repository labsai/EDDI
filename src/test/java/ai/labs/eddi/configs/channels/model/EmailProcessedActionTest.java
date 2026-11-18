/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels.model;

import ai.labs.eddi.configs.channels.model.EmailProcessedAction.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailProcessedActionTest {

    @Test
    @DisplayName("parses flag, move:<folder> and label:<name>, the kind without regard to case")
    void parse() {
        assertEquals(EmailProcessedAction.FLAG, EmailProcessedAction.parse(null));
        assertEquals(EmailProcessedAction.FLAG, EmailProcessedAction.parse("  "));
        assertEquals(EmailProcessedAction.FLAG, EmailProcessedAction.parse(" FLAG "));
        assertEquals(new EmailProcessedAction(Kind.MOVE, "EDDI/Processed"), EmailProcessedAction.parse("move:EDDI/Processed"));
        assertEquals(new EmailProcessedAction(Kind.MOVE, "Done"), EmailProcessedAction.parse("Move: Done "));
        assertEquals(new EmailProcessedAction(Kind.LABEL, "eddi-processed"), EmailProcessedAction.parse("label:eddi-processed"));
        assertEquals("move:EDDI/Processed", EmailProcessedAction.parse("move:EDDI/Processed").id());
        assertEquals("label:x", EmailProcessedAction.parse("LABEL:x").id());
        assertEquals("flag", EmailProcessedAction.FLAG.id());
    }

    @Test
    @DisplayName("anything else, and a prefix without a target, does not parse")
    void unparseable() {
        assertNull(EmailProcessedAction.parse("delete"));
        assertNull(EmailProcessedAction.parse("move:"));
        assertNull(EmailProcessedAction.parse("label:  "));
        assertNull(EmailProcessedAction.parse("seen"));
    }

    @Test
    @DisplayName("the settings carry the action; existing values read as before")
    void settings() {
        assertEquals(EmailProcessedAction.FLAG, EmailChannelSettings.from(EmailChannelSettingsTest.minimal()).processedAction());
        var moved = EmailChannelSettings.from(with("move:EDDI/Processed"));
        assertEquals(Kind.MOVE, moved.processedAction().kind());
        assertEquals("EDDI/Processed", moved.moveToFolder());
        assertTrue(moved.movesProcessed());
        var invalid = EmailChannelSettings.from(with("delete"));
        assertEquals(EmailProcessedAction.FLAG, invalid.processedAction(), "an invalid value reads as the default; validate refuses it");
        assertFalse(invalid.movesProcessed());
    }

    @Test
    @DisplayName("IMAP refuses label:<name> with a message naming what it can do; flag and move stay valid")
    void imapRefusesLabels() {
        assertEquals(List.of(), EmailChannelSettings.validate(with("flag")));
        assertEquals(List.of(), EmailChannelSettings.validate(with("move:EDDI/Processed")));
        List<String> errors = EmailChannelSettings.validate(with("label:eddi"));
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("needs a transport with labels"), errors.get(0));
        assertTrue(EmailChannelSettings.validate(with("label:")).stream().anyMatch(e -> e.contains("'label:<name>' needs a label name")));
        assertTrue(EmailChannelSettings.validate(with("delete")).stream().anyMatch(e -> e.contains("'flag', 'move:<folder>' or 'label:<name>'")));
    }

    private static Map<String, String> with(String processedAction) {
        Map<String, String> pc = EmailChannelSettingsTest.minimal();
        pc.put(EmailChannelSettings.KEY_PROCESSED_ACTION, processedAction);
        return pc;
    }
}
