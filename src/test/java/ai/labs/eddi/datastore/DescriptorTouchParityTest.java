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
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.datastore.serialization.PersistenceMapperProducer;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DescriptorStore#touchLastModified}, the one-update stamp every
 * conversation turn now makes, against a real MongoDB and a real PostgreSQL.
 * <p>
 * {@code lastModifiedOn} drives conversation retention and the listings' sort,
 * so the stamped value must read back as the same {@link Date} a full write
 * would have stored, sort among descriptors written the ordinary way, and leave
 * every other field alone.
 */
@DisplayName("Descriptor lastModifiedOn touch — MongoDB and PostgreSQL alike")
class DescriptorTouchParityTest extends MongoTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();
    // The production persistence mapper: dates as epoch millis.
    private static final JsonSerialization SERIALIZATION = new JsonSerialization(new PersistenceMapperProducer().persistenceMapper());
    private static final DocumentBuilder BUILDER = new DocumentBuilder(SERIALIZATION);

    /**
     * A backend: its storage, and how it physically stored a descriptor's
     * lastModifiedOn.
     */
    record Backend(IResourceStorage<ConversationDescriptor> storage, Function<String, String> storedType) {
    }

    static Stream<Arguments> storages() {
        Supplier<Backend> mongo = () -> {
            String collection = "touch" + SEQ.incrementAndGet();
            return new Backend(new MongoResourceStorage<>(getDatabase(), collection, BUILDER, ConversationDescriptor.class),
                    id -> {
                        Object stored = getDatabase().getCollection(collection).find(new Document("_id", new ObjectId(id))).first()
                                .get(DescriptorStore.FIELD_LAST_MODIFIED);
                        return stored == null ? null : stored.getClass().getSimpleName();
                    });
        };
        Supplier<Backend> postgres = () -> {
            String collection = "touch" + SEQ.incrementAndGet();
            DataSource dataSource = PostgresTestBase.createDataSourceInstance().get();
            return new Backend(new PostgresResourceStorage<>(dataSource, collection, SERIALIZATION, ConversationDescriptor.class),
                    id -> postgresJsonType(dataSource, collection, id));
        };
        return Stream.of(Arguments.of("mongo", mongo, "Long"), Arguments.of("postgres", postgres, "number"));
    }

    private static String postgresJsonType(DataSource dataSource, String collection, String id) {
        String sql = "SELECT jsonb_typeof(data -> 'lastModifiedOn') FROM resources WHERE collection_name = ? AND id = ?::uuid";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, collection);
            ps.setString(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("storages")
    void touchStampsOnlyLastModifiedOn(String backend, Supplier<Backend> factory, String numberType) throws Exception {
        var backendUnderTest = factory.get();
        var storage = backendUnderTest.storage();
        var store = descriptorStoreOver(storage);
        String id = store(storage, "alice", 1_000L);
        int version = storage.getCurrentVersion(id);

        assertTrue(store.touchLastModified(id, 1_760_000_000_123L), backend);

        ConversationDescriptor read = store.readDescriptor(id, version);
        assertEquals(new Date(1_760_000_000_123L), read.getLastModifiedOn(), backend);
        assertEquals("alice", read.getUserId(), backend);
        assertEquals(URI.create("eddi://ai.labs.agent/agentstore/agents/a?version=1"), read.getAgentResource(), backend);
        assertEquals(version, storage.getCurrentVersion(id), backend + ": a touch is not a new version");
        // The type a full write stores (epoch millis as a number), or range queries and
        // sorts would treat touched and untouched descriptors differently.
        assertEquals(numberType, backendUnderTest.storedType().apply(id), backend);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("storages")
    void aTouchedDescriptorSortsAmongFullyWrittenOnes(String backend, Supplier<Backend> factory, String numberType) throws Exception {
        var backendUnderTest = factory.get();
        var storage = backendUnderTest.storage();
        var store = descriptorStoreOver(storage);
        String newest = store(storage, "bob", 5_000L);
        String touched = store(storage, "alice", 1_000L);
        String oldest = store(storage, "carol", 2_000L);

        // Into the middle, not the top: a value stored as the wrong JSON/BSON type
        // (a string) sorts above or below every number and would land at one end.
        store.touchLastModified(touched, 3_000L);

        var page = storage.findResources(new IResourceFilter.QueryFilters[0], DescriptorStore.FIELD_LAST_MODIFIED, 0, 10);
        assertEquals(List.of(newest, touched, oldest), page.stream().map(IResourceStore.IResourceId::getId).toList(), backend);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("storages")
    void noLiveDescriptorMeansNoWrite(String backend, Supplier<Backend> factory, String numberType) throws Exception {
        var backendUnderTest = factory.get();
        var storage = backendUnderTest.storage();
        var store = descriptorStoreOver(storage);
        String id = store(storage, "alice", 1_000L);
        storage.remove(id);

        assertFalse(store.touchLastModified(id, 9_000L), backend);
        assertFalse(store.touchLastModified("000000000000000000000000", 9_000L), backend);
        assertFalse(store.touchLastModified("00000000-0000-4000-8000-000000000000", 9_000L), backend);
        assertFalse(store.touchLastModified("xyz", 9_000L), backend);
        assertNull(storage.read(id, 1), backend + ": a touch must not resurrect a deleted descriptor");
    }

    private static DescriptorStore<ConversationDescriptor> descriptorStoreOver(IResourceStorage<ConversationDescriptor> storage) {
        IResourceStorageFactory factory = new IResourceStorageFactory() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> IResourceStorage<T> create(String collectionName, IDocumentBuilder documentBuilder,
                                                  Class<T> documentType, String... indexes) {
                return (IResourceStorage<T>) storage;
            }
        };
        return new DescriptorStore<>(factory, BUILDER, ConversationDescriptor.class);
    }

    private static String store(IResourceStorage<ConversationDescriptor> storage, String userId, long lastModified) throws Exception {
        var descriptor = new ConversationDescriptor();
        descriptor.setUserId(userId);
        descriptor.setAgentResource(URI.create("eddi://ai.labs.agent/agentstore/agents/a?version=1"));
        descriptor.setLastModifiedOn(new Date(lastModified));
        var resource = storage.newResource(descriptor);
        storage.store(resource);
        return resource.getId();
    }
}
