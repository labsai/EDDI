/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.mongo.MongoResourceStorage;
import ai.labs.eddi.datastore.mongo.MongoTestBase;
import ai.labs.eddi.datastore.postgres.PostgresResourceStorage;
import ai.labs.eddi.datastore.postgres.PostgresTestBase;
import ai.labs.eddi.datastore.serialization.DocumentBuilder;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.datastore.serialization.PersistenceMapperProducer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A target's {@code consultable} (email v2 plan §10.1) through the resource
 * storage the channel store uses, against a real MongoDB and a real PostgreSQL:
 * the list the admin saved is the list the inbound processor reads.
 */
@DisplayName("Channel target consultable — MongoDB and PostgreSQL alike")
class ChannelTargetConsultableParityTest extends MongoTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final JsonSerialization SERIALIZATION = new JsonSerialization(new PersistenceMapperProducer().persistenceMapper());
    private static final DocumentBuilder BUILDER = new DocumentBuilder(SERIALIZATION);

    static Stream<Arguments> storages() {
        Supplier<IResourceStorage<ChannelIntegrationConfiguration>> mongo = () -> new MongoResourceStorage<>(getDatabase(),
                "channels" + SEQ.incrementAndGet(), BUILDER, ChannelIntegrationConfiguration.class);
        Supplier<IResourceStorage<ChannelIntegrationConfiguration>> postgres = () -> new PostgresResourceStorage<>(
                PostgresTestBase.createDataSourceInstance().get(), "channels" + SEQ.incrementAndGet(), SERIALIZATION,
                ChannelIntegrationConfiguration.class);
        return Stream.of(Arguments.of("mongo", mongo), Arguments.of("postgres", postgres));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("storages")
    void consultableSurvivesTheStore(String backend, Supplier<IResourceStorage<ChannelIntegrationConfiguration>> factory) throws Exception {
        var storage = factory.get();
        var finance = target("finance", "0f0f0f0f0f0f0f0f0f0f0f0f");
        finance.setConsultable(List.of("legal", "tax"));
        var legal = target("legal", "0e0e0e0e0e0e0e0e0e0e0e0e");

        var resource = storage.newResource(integration(finance, legal));
        storage.store(resource);
        ChannelIntegrationConfiguration read = storage.read(resource.getId(), storage.getCurrentVersion(resource.getId())).getData();

        assertEquals(List.of("legal", "tax"), read.getTargets().get(0).getConsultable(), backend);
        assertNull(read.getTargets().get(1).getConsultable(), backend + ": an unset list stays absent");
    }

    private static ChannelTarget target(String name, String agentId) {
        var target = new ChannelTarget();
        target.setName(name);
        target.setType(ChannelTarget.TargetType.AGENT);
        target.setTargetId(agentId);
        return target;
    }

    private static ChannelIntegrationConfiguration integration(ChannelTarget... targets) {
        var config = new ChannelIntegrationConfiguration();
        config.setName("Invoices");
        config.setChannelType("email");
        config.setPlatformConfig(new HashMap<>(Map.of("address", "invoices@contoso.com")));
        config.setTargets(List.of(targets));
        config.setDefaultTargetName(targets[0].getName());
        return config;
    }
}
