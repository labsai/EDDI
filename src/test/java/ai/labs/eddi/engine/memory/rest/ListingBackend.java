/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.DescriptorStore;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.mongo.MongoResourceStorageFactory;
import ai.labs.eddi.datastore.postgres.PostgresConversationMemoryStore;
import ai.labs.eddi.datastore.postgres.PostgresResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.DocumentBuilder;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.datastore.serialization.PersistenceMapperProducer;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.memory.ConversationDescriptorStore;
import ai.labs.eddi.engine.memory.ConversationMemoryStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import jakarta.enterprise.inject.Instance;
import org.bson.Document;
import org.bson.types.ObjectId;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A real conversation store pair - memory and descriptors - on MongoDB or
 * PostgreSQL, with bulk seeding, for the listing's two-backend tests and its
 * benchmark (#931).
 * <p>
 * Seeding writes one descriptor through the production store and clones its
 * stored form for the rest, so thousands of rows cost one bulk insert rather
 * than thousands of store round trips while keeping the exact stored shape.
 */
abstract class ListingBackend {

    static final String AGENT_ID = "6a1b2c3d4e5f6a7b8c9d0e1f";
    static final String OWNER = "alice";
    /**
     * Realistic epoch millis: PostgreSQL sorts the field as text, which only agrees
     * with numeric order at equal length.
     */
    static final long EPOCH = 1_700_000_000_000L;
    private static final String CONVERSATION_URI = "eddi://ai.labs.conversation/conversationstore/conversations/";

    // The production persistence mapper: dates as epoch millis, as in a deployment.
    static final JsonSerialization SERIALIZATION = new JsonSerialization(new PersistenceMapperProducer().persistenceMapper());
    static final DocumentBuilder BUILDER = new DocumentBuilder(SERIALIZATION);

    final IConversationMemoryStore memoryStore;
    final IConversationDescriptorStore descriptorStore;

    ListingBackend(IConversationMemoryStore memoryStore, IResourceStorageFactory storageFactory) {
        this.memoryStore = memoryStore;
        this.descriptorStore = new ConversationDescriptorStore(storageFactory, BUILDER);
    }

    ListingBackend(IConversationMemoryStore memoryStore, IConversationDescriptorStore descriptorStore) {
        this.memoryStore = memoryStore;
        this.descriptorStore = descriptorStore;
    }

    abstract String name();

    abstract String newId();

    /** Removes every conversation and descriptor. */
    abstract void clear() throws Exception;

    /**
     * Seeds {@code count} conversations. Row {@code i} is modified at {@code i}, so
     * a listing (newest first) returns them from the highest index down.
     * {@code orphan} rows get a descriptor and no conversation memory.
     *
     * @return the ids, by row index
     */
    abstract List<String> seed(int count, IntFunction<String> stateOf, IntPredicate orphan, int payloadBytes) throws Exception;

    /** Index keys and documents the server examined so far, where it tells. */
    long[] serverScanned() {
        return null;
    }

    /**
     * The listing over this backend, as a caller who sees all or only their own.
     */
    RestConversationStore listing(boolean seesAllConversations) {
        ConversationAccessGuard guard = mock(ConversationAccessGuard.class);
        when(guard.seesAllConversations()).thenReturn(seesAllConversations);
        lenient().when(guard.callerActor(any())).thenReturn(OWNER);
        lenient().when(guard.canAccessConversation(any())).thenAnswer(invocation -> OWNER.equals(invocation.getArgument(0)));
        @SuppressWarnings("unchecked")
        Instance<IAttachmentStore> attachments = mock(Instance.class);
        lenient().when(attachments.isResolvable()).thenReturn(false);
        return new RestConversationStore(mock(IDocumentDescriptorStore.class), descriptorStore, memoryStore, mock(IConversationService.class),
                mock(IUserMemoryStore.class), mock(IRuntime.class), guard, mock(ResourceAccessGuard.class), 30, 90, attachments);
    }

    ConversationDescriptor descriptor(String id, long modifiedOn, String owner) {
        var descriptor = new ConversationDescriptor();
        descriptor.setResource(URI.create(CONVERSATION_URI + id + "?version=0"));
        descriptor.setAgentResource(URI.create("eddi://ai.labs.agent/agentstore/agents/" + AGENT_ID + "?version=1"));
        descriptor.setUserId(owner);
        descriptor.setViewState(ConversationDescriptor.ViewState.UNSEEN);
        descriptor.setCreatedOn(new Date(modifiedOn));
        descriptor.setLastModifiedOn(new Date(modifiedOn));
        descriptor.setDeleted(false);
        return descriptor;
    }

    static String payload(int bytes) {
        return "x".repeat(Math.max(0, bytes));
    }

    // ---------------------------------------------------------------- MongoDB

    static final class Mongo extends ListingBackend {
        private final MongoDatabase database;

        Mongo(MongoDatabase database, MongoDatabase databaseWithCodecs) {
            super(new ConversationMemoryStore(databaseWithCodecs), new MongoResourceStorageFactory(database));
            this.database = database;
        }

        @Override
        String name() {
            return "mongo";
        }

        @Override
        String newId() {
            return new ObjectId().toHexString();
        }

        @Override
        void clear() {
            database.getCollection("conversationmemories").deleteMany(new Document());
            database.getCollection(DescriptorStore.COLLECTION_DESCRIPTORS).deleteMany(new Document());
        }

        @Override
        List<String> seed(int count, IntFunction<String> stateOf, IntPredicate orphan, int payloadBytes) throws Exception {
            List<String> ids = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                ids.add(newId());
            }
            descriptorStore.createDescriptor(ids.get(0), 0, descriptor(ids.get(0), 0, OWNER));
            var descriptors = database.getCollection(DescriptorStore.COLLECTION_DESCRIPTORS);
            Document template = descriptors.find(Filters.eq("_id", new ObjectId(ids.get(0)))).first();
            descriptors.deleteOne(Filters.eq("_id", new ObjectId(ids.get(0))));

            var memories = database.getCollection("conversationmemories");
            String body = payload(payloadBytes);
            List<Document> descriptorBatch = new ArrayList<>();
            List<Document> memoryBatch = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String id = ids.get(i);
                Document clone = new Document(template);
                clone.put("_id", new ObjectId(id));
                clone.put("resource", CONVERSATION_URI + id + "?version=0");
                clone.put("lastModifiedOn", EPOCH + i);
                descriptorBatch.add(clone);
                if (!orphan.test(i)) {
                    memoryBatch.add(new Document("_id", new ObjectId(id))
                            .append("agentId", AGENT_ID).append("agentVersion", 1)
                            .append("userId", OWNER).append("environment", "production")
                            .append("conversationState", stateOf.apply(i))
                            .append("conversationSteps", List.of(new Document("payload", body))));
                }
                if (descriptorBatch.size() == 2_000 || i == count - 1) {
                    descriptors.insertMany(descriptorBatch);
                    descriptorBatch.clear();
                    if (!memoryBatch.isEmpty()) {
                        memories.insertMany(memoryBatch);
                        memoryBatch.clear();
                    }
                }
            }
            return ids;
        }

        @Override
        long[] serverScanned() {
            Document status = database.runCommand(new Document("serverStatus", 1));
            Document executor = status.get("metrics", Document.class).get("queryExecutor", Document.class);
            return new long[]{((Number) executor.get("scanned")).longValue(), ((Number) executor.get("scannedObjects")).longValue()};
        }
    }

    // ------------------------------------------------------------- PostgreSQL

    static final class Postgres extends ListingBackend {
        private final DataSource dataSource;

        Postgres(Instance<DataSource> dataSourceInstance) {
            super(new PostgresConversationMemoryStore(dataSourceInstance, SERIALIZATION),
                    new PostgresResourceStorageFactory(dataSourceInstance, SERIALIZATION));
            this.dataSource = dataSourceInstance.get();
        }

        @Override
        String name() {
            return "postgres";
        }

        DataSource dataSource() {
            return dataSource;
        }

        @Override
        String newId() {
            return UUID.randomUUID().toString();
        }

        @Override
        void clear() throws Exception {
            // Creates the table if this is the first use.
            memoryStore.findConversationIdsByState(ConversationState.READY, 1);
            try (Connection conn = dataSource.getConnection(); Statement statement = conn.createStatement()) {
                statement.execute("DELETE FROM conversation_memories");
                statement.execute("DELETE FROM resources WHERE collection_name = '" + DescriptorStore.COLLECTION_DESCRIPTORS + "'");
                statement.execute("DELETE FROM resources_history WHERE collection_name = '" + DescriptorStore.COLLECTION_DESCRIPTORS + "'");
            }
        }

        @Override
        List<String> seed(int count, IntFunction<String> stateOf, IntPredicate orphan, int payloadBytes) throws Exception {
            List<String> ids = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                ids.add(newId());
            }
            descriptorStore.createDescriptor(ids.get(0), 0, descriptor(ids.get(0), 0, OWNER));
            String template;
            int version;
            try (Connection conn = dataSource.getConnection();
                    PreparedStatement read = conn.prepareStatement("SELECT data::text, version FROM resources WHERE id = ?::uuid");
                    PreparedStatement delete = conn.prepareStatement("DELETE FROM resources WHERE id = ?::uuid")) {
                read.setString(1, ids.get(0));
                try (var rs = read.executeQuery()) {
                    rs.next();
                    template = rs.getString(1);
                    version = rs.getInt(2);
                }
                delete.setString(1, ids.get(0));
                delete.executeUpdate();
            }
            String body = "{\"agentId\":\"" + AGENT_ID + "\",\"agentVersion\":1,\"userId\":\"" + OWNER
                    + "\",\"environment\":\"production\",\"conversationSteps\":[{\"payload\":\"" + payload(payloadBytes) + "\"}]}";
            try (Connection conn = dataSource.getConnection();
                    PreparedStatement descriptors = conn.prepareStatement("INSERT INTO resources (id, collection_name, version, data) "
                            + "VALUES (?::uuid, ?, ?, jsonb_set(jsonb_set(?::jsonb, '{resource}', to_jsonb(?::text)), "
                            + "'{lastModifiedOn}', to_jsonb(?::bigint)))");
                    PreparedStatement memories = conn.prepareStatement("INSERT INTO conversation_memories "
                            + "(id, AGENT_ID, AGENT_VERSION, conversation_state, data) VALUES (?::uuid, ?, 1, ?, ?::jsonb)")) {
                conn.setAutoCommit(false);
                for (int i = 0; i < count; i++) {
                    String id = ids.get(i);
                    descriptors.setString(1, id);
                    descriptors.setString(2, DescriptorStore.COLLECTION_DESCRIPTORS);
                    descriptors.setInt(3, version);
                    descriptors.setString(4, template);
                    descriptors.setString(5, CONVERSATION_URI + id + "?version=0");
                    descriptors.setLong(6, EPOCH + i);
                    descriptors.addBatch();
                    if (!orphan.test(i)) {
                        memories.setString(1, id);
                        memories.setString(2, AGENT_ID);
                        memories.setString(3, stateOf.apply(i));
                        memories.setString(4, body);
                        memories.addBatch();
                    }
                    if (i % 2_000 == 1_999 || i == count - 1) {
                        descriptors.executeBatch();
                        memories.executeBatch();
                    }
                }
                conn.commit();
                conn.setAutoCommit(true);
                try (Statement statement = conn.createStatement()) {
                    statement.execute("ANALYZE resources");
                    statement.execute("ANALYZE conversation_memories");
                }
            }
            return ids;
        }

        /**
         * One connection shared by every call, closed never: the test DataSource opens
         * a new connection per call, which would make every query look slow.
         */
        static Instance<DataSource> pooled(Instance<DataSource> source) {
            Connection connection;
            try {
                connection = source.get().getConnection();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            Connection shared = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("close")) {
                            return null;
                        }
                        try {
                            return method.invoke(connection, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            DataSource dataSource = new SingleConnectionDataSource(shared);
            @SuppressWarnings("unchecked")
            Instance<DataSource> instance = mock(Instance.class);
            lenient().when(instance.get()).thenReturn(dataSource);
            lenient().when(instance.isResolvable()).thenReturn(true);
            return instance;
        }
    }

    private record SingleConnectionDataSource(Connection connection) implements DataSource {
        @Override
        public Connection getConnection() {
            return connection;
        }

        @Override
        public Connection getConnection(String username, String password) {
            return connection;
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            return null;
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
