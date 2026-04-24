/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.engine.model.Context;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContextUtilitiesTest {

    @Test
    void storeContextLanguageInLongTermMemory_withLangContext_storesProperty() {
        var memory = new ConversationMemory("agent-1", 1, "user-1");
        var langCtx = new Context();
        langCtx.setType(Context.ContextType.string);
        langCtx.setValue("en");

        ContextUtilities.storeContextLanguageInLongTermMemory(Map.of("lang", langCtx), memory);

        Property prop = memory.getConversationProperties().get("lang");
        assertNotNull(prop);
        assertEquals("en", prop.getValueString());
        assertEquals(Property.Scope.longTerm, prop.getScope());
    }

    @Test
    void storeContextLanguageInLongTermMemory_nonLangKey_ignored() {
        var memory = new ConversationMemory("agent-1", 1, "user-1");
        var ctx = new Context();
        ctx.setType(Context.ContextType.string);
        ctx.setValue("some-value");

        ContextUtilities.storeContextLanguageInLongTermMemory(Map.of("other", ctx), memory);

        assertNull(memory.getConversationProperties().get("lang"));
    }

    @Test
    void storeContextLanguageInLongTermMemory_nonStringType_ignored() {
        var memory = new ConversationMemory("agent-1", 1, "user-1");
        var ctx = new Context();
        ctx.setType(Context.ContextType.object);
        ctx.setValue(Map.of("key", "val"));

        ContextUtilities.storeContextLanguageInLongTermMemory(Map.of("lang", ctx), memory);

        assertNull(memory.getConversationProperties().get("lang"));
    }

    @Test
    void storeContextLanguageInLongTermMemory_emptyValue_ignored() {
        var memory = new ConversationMemory("agent-1", 1, "user-1");
        var ctx = new Context();
        ctx.setType(Context.ContextType.string);
        ctx.setValue("");

        ContextUtilities.storeContextLanguageInLongTermMemory(Map.of("lang", ctx), memory);

        assertNull(memory.getConversationProperties().get("lang"));
    }

    @Test
    void retrieveContextLanguageFromLongTermMemory_exists_returnsLang() {
        var memory = new ConversationMemory("agent-1", 1, "user-1");
        memory.getConversationProperties().put("lang",
                new Property("lang", "de", Property.Scope.longTerm));

        String lang = ContextUtilities.retrieveContextLanguageFromLongTermMemory(
                memory.getConversationProperties());
        assertEquals("de", lang);
    }

    @Test
    void retrieveContextLanguageFromLongTermMemory_notSet_returnsNull() {
        var memory = new ConversationMemory("agent-1", 1, "user-1");
        String lang = ContextUtilities.retrieveContextLanguageFromLongTermMemory(
                memory.getConversationProperties());
        assertNull(lang);
    }
}
