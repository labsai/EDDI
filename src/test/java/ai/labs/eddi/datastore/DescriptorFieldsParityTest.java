/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore;

import ai.labs.eddi.datastore.mongo.MongoResourceStorage;
import ai.labs.eddi.datastore.mongo.MongoTestBase;
import ai.labs.eddi.datastore.postgres.PostgresResourceStorage;
import ai.labs.eddi.datastore.postgres.PostgresTestBase;
import ai.labs.eddi.datastore.serialization.DocumentBuilder;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.datastore.serialization.PersistenceMapperProducer;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor.TitleSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URI;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IResourceStorage#setFieldsIf}, the conditional single update that
 * writes conversation titles and pins, against a real MongoDB and a real
 * PostgreSQL: the guard must refuse a generated title over one the owner set,
 * every other field must survive, and the stored values must read back through
 * the descriptor model.
 */
@DisplayName("Descriptor title/pin fields — MongoDB and PostgreSQL alike")
class DescriptorFieldsParityTest extends MongoTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final JsonSerialization SERIALIZATION = new JsonSerialization(new PersistenceMapperProducer().persistenceMapper());
    private static final DocumentBuilder BUILDER = new DocumentBuilder(SERIALIZATION);

    static Stream<Arguments> storages() {
        Supplier<IResourceStorage<ConversationDescriptor>> mongo = () -> new MongoResourceStorage<>(getDatabase(),
                "fields" + SEQ.incrementAndGet(), BUILDER, ConversationDescriptor.class);
        Supplier<IResourceStorage<ConversationDescriptor>> postgres = () -> new PostgresResourceStorage<>(
                PostgresTestBase.createDataSourceInstance().get(), "fields" + SEQ.incrementAndGet(), SERIALIZATION, ConversationDescriptor.class);
        return Stream.of(Arguments.of("mongo", mongo), Arguments.of("postgres", postgres));
    }

    private static Map<String, Object> title(String title, TitleSource source) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(ConversationDescriptor.FIELD_TITLE, title);
        fields.put(ConversationDescriptor.FIELD_TITLE_SOURCE, source.name());
        return fields;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("storages")
    void generatedTitlesYieldToTheOwner(String backend, Supplier<IResourceStorage<ConversationDescriptor>> factory) throws Exception {
        var storage = factory.get();
        String id = store(storage);
        String guard = ConversationDescriptor.FIELD_TITLE_SOURCE;

        // AUTO onto nothing: written.
        assertTrue(storage.setFieldsIf(id, title("First message", TitleSource.AUTO), guard, List.of()), backend);
        // A second AUTO does not replace the first.
        assertFalse(storage.setFieldsIf(id, title("Other", TitleSource.AUTO), guard, List.of()), backend);
        // LLM replaces AUTO.
        assertTrue(storage.setFieldsIf(id, title("Model title", TitleSource.LLM), guard, List.of(TitleSource.AUTO.name())), backend);
        // The owner renames: unconditional, and pins.
        Map<String, Object> rename = title("Mine", TitleSource.USER);
        rename.put(ConversationDescriptor.FIELD_PINNED, true);
        assertTrue(storage.setFieldsIf(id, rename, null, List.of()), backend);
        // A late LLM answer is refused.
        assertFalse(storage.setFieldsIf(id, title("Late model title", TitleSource.LLM), guard, List.of(TitleSource.AUTO.name())), backend);

        ConversationDescriptor read = storage.read(id, storage.getCurrentVersion(id)).getData();
        assertEquals("Mine", read.getTitle(), backend);
        assertEquals(TitleSource.USER, read.getTitleSource(), backend);
        assertTrue(read.isPinned(), backend);
        assertEquals("alice", read.getUserId(), backend + ": other fields survive");
        assertEquals(new Date(1_000L), read.getLastModifiedOn(), backend);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("storages")
    void aDescriptorWithoutTheFieldsReadsUntitledAndUnpinned(String backend, Supplier<IResourceStorage<ConversationDescriptor>> factory)
            throws Exception {
        var storage = factory.get();
        String id = store(storage);

        ConversationDescriptor read = storage.read(id, storage.getCurrentVersion(id)).getData();
        assertNull(read.getTitle(), backend);
        assertNull(read.getTitleSource(), backend);
        assertFalse(read.isPinned(), backend);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("storages")
    void noLiveDescriptorMeansNoWrite(String backend, Supplier<IResourceStorage<ConversationDescriptor>> factory) throws Exception {
        var storage = factory.get();
        String id = store(storage);
        storage.remove(id);

        assertFalse(storage.setFieldsIf(id, Map.of(ConversationDescriptor.FIELD_PINNED, true), null, List.of()), backend);
        assertFalse(storage.setFieldsIf("xyz", Map.of(ConversationDescriptor.FIELD_PINNED, true), null, List.of()), backend);
        assertNull(storage.read(id, 1), backend + ": a write must not resurrect a deleted descriptor");
    }

    private static String store(IResourceStorage<ConversationDescriptor> storage) throws Exception {
        var descriptor = new ConversationDescriptor();
        descriptor.setUserId("alice");
        descriptor.setAgentResource(URI.create("eddi://ai.labs.agent/agentstore/agents/a?version=1"));
        descriptor.setLastModifiedOn(new Date(1_000L));
        var resource = storage.newResource(descriptor);
        storage.store(resource);
        return resource.getId();
    }
}
