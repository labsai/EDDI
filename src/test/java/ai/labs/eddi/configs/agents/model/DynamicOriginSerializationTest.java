/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents.model;

import ai.labs.eddi.configs.agents.model.AgentConfiguration.DynamicOrigin;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.datastore.serialization.PersistenceMapperProducer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A sub-agent's {@code dynamicOrigin.retained} flag is the authority both
 * cleanup paths read before they delete it. {@code isRetained()} names the same
 * bean property and is {@code @JsonIgnore}d; without an explicit include on the
 * stored accessors that ignore dropped the property from every stored and
 * exported agent, so a retained agent read back as legacy (absent) and could be
 * deleted at the end of its conversation.
 */
@DisplayName("DynamicOrigin.retained — JSON round trip")
class DynamicOriginSerializationTest {

    static Stream<Arguments> flags() {
        return Stream.of(Arguments.of(Boolean.TRUE), Arguments.of(Boolean.FALSE), Arguments.of((Boolean) null));
    }

    private static AgentConfiguration agent(Boolean retained) {
        var origin = new DynamicOrigin("parent", "0123456789abcdef01234567", null, "user-1");
        origin.setRetained(retained);
        var agent = new AgentConfiguration();
        agent.setDynamicOrigin(origin);
        return agent;
    }

    @ParameterizedTest(name = "retained={0}")
    @MethodSource("flags")
    @DisplayName("a plain mapper (ZIP, sync) keeps the flag, tri-state")
    void plainMapper(Boolean retained) throws Exception {
        var mapper = new ObjectMapper();

        var read = mapper.readValue(mapper.writeValueAsString(agent(retained)), AgentConfiguration.class);

        assertNotNull(read.getDynamicOrigin());
        assertEquals(retained, read.getDynamicOrigin().getRetained());
    }

    @ParameterizedTest(name = "retained={0}")
    @MethodSource("flags")
    @DisplayName("the persistence mapper (the store) keeps the flag, tri-state")
    void persistenceMapper(Boolean retained) throws Exception {
        var serialization = new JsonSerialization(new PersistenceMapperProducer().persistenceMapper());

        var read = serialization.deserialize(serialization.serialize(agent(retained)), AgentConfiguration.class);

        assertNotNull(read.getDynamicOrigin());
        assertEquals(retained, read.getDynamicOrigin().getRetained());
    }
}
