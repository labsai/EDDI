/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.properties;

import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import ai.labs.eddi.engine.TestMemoryFactory;
import ai.labs.eddi.engine.TestMemoryFactory.MemoryContext;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.nlp.expressions.ExpressionFactory;
import ai.labs.eddi.modules.nlp.expressions.utilities.ExpressionProvider;
import ai.labs.eddi.modules.properties.impl.PropertySetter;
import ai.labs.eddi.modules.properties.impl.PropertySetterTask;
import ai.labs.eddi.modules.properties.impl.SecretPropertyVault;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * The exploit: a chat client sends a {@code properties*} context entry of type
 * {@code expressions}, hoping to overwrite what the agent computed — and adds
 * {@code "serverGenerated": true} to pass for the engine. The flag cannot come
 * off the wire, so the entry is ignored unless the agent opted in.
 */
@DisplayName("client-supplied properties context — the real request shape")
class ClientSuppliedPropertiesContextTest {

    private static final String REQUEST = "{\"type\":\"expressions\",\"value\":\"property(role(admin))\",\"serverGenerated\":true}";

    private PropertySetterTask task;

    @BeforeEach
    void setUp() {
        task = new PropertySetterTask(new ExpressionProvider(new ExpressionFactory()), mock(IMemoryItemConverter.class),
                mock(ITemplatingEngine.class), mock(IDataFactory.class), mock(IResourceClientLibrary.class), new ObjectMapper(),
                mock(SecretPropertyVault.class));
    }

    private Context deserialize() throws Exception {
        // the application's object mapper configuration, as the REST layer reads a
        // request
        ObjectMapper mapper = SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false);
        return mapper.readValue(REQUEST, Context.class);
    }

    private MemoryContext turnWith(Context context) {
        MemoryContext ctx = TestMemoryFactory.create();
        doReturn(List.of(new Data<>("context:properties", context))).when(ctx.currentStep()).getAllData("context");
        return ctx;
    }

    @Test
    @DisplayName("serverGenerated in the body does not set the flag, and the property is not set")
    void flagCannotComeFromTheWire() throws Exception {
        Context context = deserialize();
        assertFalse(context.isServerGenerated());
        MemoryContext ctx = turnWith(context);

        task.execute(ctx.memory(), new PropertySetter(new LinkedList<>()));

        assertNull(ctx.conversationProperties().get("role"));
    }

    @Test
    @DisplayName("an agent that opted in with acceptClientProperties does apply it")
    void optInApplies() throws Exception {
        MemoryContext ctx = turnWith(deserialize());

        task.execute(ctx.memory(), new PropertySetter(new LinkedList<>(), true));

        assertEquals("admin", ctx.conversationProperties().get("role").getValueString());
    }

    @Test
    @DisplayName("an entry the engine itself wrote is applied")
    void serverGeneratedApplies() throws Exception {
        Context context = deserialize();
        context.setServerGenerated(true);
        MemoryContext ctx = turnWith(context);

        task.execute(ctx.memory(), new PropertySetter(new LinkedList<>()));

        assertEquals("admin", ctx.conversationProperties().get("role").getValueString());
    }
}
