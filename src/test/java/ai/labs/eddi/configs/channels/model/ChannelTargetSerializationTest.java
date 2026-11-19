/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The email-only target fields through JSON, the shape targets are stored in.
 */
class ChannelTargetSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("mentions, addresses and allowedSenders survive a round trip")
    void roundTrip() throws Exception {
        var target = new ChannelTarget();
        target.setName("finance");
        target.setTargetId("agent-1");
        target.setMentions(List.of("finance"));
        target.setAddresses(List.of("finance-agent@contoso.com"));
        target.setAllowedSenders(List.of("*@supplier.com"));

        ChannelTarget read = mapper.readValue(mapper.writeValueAsString(target), ChannelTarget.class);

        assertEquals(List.of("finance"), read.getMentions());
        assertEquals(List.of("finance-agent@contoso.com"), read.getAddresses());
        assertEquals(List.of("*@supplier.com"), read.getAllowedSenders());
    }

    @Test
    @DisplayName("a target without them writes no such keys, so other channel types' documents stay as they were")
    void absentWhenUnset() throws Exception {
        var target = new ChannelTarget();
        target.setName("support");
        target.setTargetId("agent-1");

        String json = mapper.writeValueAsString(target);

        assertFalse(json.contains("mentions") || json.contains("addresses") || json.contains("allowedSenders"), json);
        ChannelTarget read = mapper.readValue(json, ChannelTarget.class);
        assertNull(read.getMentions());
        assertNull(read.getAllowedSenders());
    }
}
