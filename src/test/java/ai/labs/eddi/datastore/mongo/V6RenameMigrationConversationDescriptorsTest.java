/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.migration.IMigrationLogStore;
import ai.labs.eddi.configs.migration.V6RenameMigration;
import ai.labs.eddi.configs.migration.model.MigrationLog;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.memory.ConversationDescriptorStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.rest.RestConversationStore;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import com.mongodb.client.MongoCollection;
import jakarta.enterprise.inject.Instance;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rename migration against EDDI 5 conversation descriptors, read back
 * through the 6.x store and listed through the real per-agent filter.
 *
 * <p>
 * EDDI 5 stored a conversation's agent as {@code botResource} and
 * {@code botName}. The migration rewrote the URI inside {@code botResource} but
 * not the field's name, and the 6.x descriptor reads {@code agentResource}, so
 * {@code GET /conversationstore/conversations?agentId=…} listed none of the
 * conversations EDDI 5 created. Ids and values are synthetic.
 * </p>
 */
@DisplayName("V6RenameMigration — conversation descriptors")
class V6RenameMigrationConversationDescriptorsTest extends MongoTestBase {

    private static final String AGENT = "0000000000000000000000d1";
    private static final String OTHER_AGENT = "0000000000000000000000d2";
    private static final String CONVERSATION_A = "0000000000000000000000c1";
    private static final String CONVERSATION_B = "0000000000000000000000c2";
    private static final String CONVERSATION_C = "0000000000000000000000c3";

    private static final String V5_AGENT_URI = "eddi://ai.labs.bot/botstore/bots/" + AGENT + "?version=2";
    private static final String V6_AGENT_URI = "eddi://ai.labs.agent/agentstore/agents/" + AGENT + "?version=2";
    private static final String AGENT_NAME = "Synthetic agent";

    private static final String RENAME_COMPLETE = "v6-rename-migration-complete";
    private static final String DESCRIPTORS_COMPLETE = "v6-rename-descriptor-fields-complete";

    private IMigrationLogStore migrationLog;
    private ConversationDescriptorStore descriptorStore;
    private final Map<String, String> snapshotAgents = new HashMap<>();

    @BeforeEach
    void clean() {
        dropCollections("descriptors", "descriptors.history", "conversationmemories");
        migrationLog = mock(IMigrationLogStore.class);
        when(migrationLog.readMigrationLog(any())).thenReturn(null);
        snapshotAgents.clear();
        // The store creates the collection and its indexes, as it does at startup
        // before the migration runs.
        descriptorStore = new ConversationDescriptorStore(new MongoResourceStorageFactory(getDatabase()), documentBuilder);
    }

    private static MongoCollection<Document> descriptors() {
        return getDatabase().getCollection("descriptors");
    }

    /** A conversation descriptor as EDDI 5 wrote it. */
    private static Document v5Descriptor(String conversationId, String botResource) {
        Document descriptor = new Document("_id", new ObjectId(conversationId)).append("_version", 1)
                .append("resource", "eddi://ai.labs.conversation/conversationstore/conversations/" + conversationId + "?version=1")
                .append("createdOn", new Date()).append("lastModifiedOn", new Date()).append("deleted", false)
                .append("userId", "synthetic-user").append("viewState", "SEEN").append("conversationStepSize", 3)
                .append("environment", "unrestricted").append("conversationState", "READY");
        if (botResource != null) {
            descriptor.append("botResource", botResource).append("botName", AGENT_NAME);
        }
        return descriptor;
    }

    private void migrate() {
        new V6RenameMigration(getDatabase(), migrationLog, true).runIfNeeded();
    }

    /** The rename migration an earlier 6.x recorded as complete. */
    private void migratedByAnEarlier6x() {
        when(migrationLog.readMigrationLog(RENAME_COMPLETE)).thenReturn(new MigrationLog(RENAME_COMPLETE));
    }

    /**
     * The real listing, over the real descriptor store. The conversations it loads
     * name the agent given in {@link #snapshotAgents}, or none: most tests name
     * none, so that the listing is decided by the descriptor alone — the snapshot
     * fallback has a test of its own.
     */
    @SuppressWarnings("unchecked")
    private List<ConversationDescriptor> list(String agentId, Integer agentVersion) throws Exception {
        IConversationMemoryStore conversations = mock(IConversationMemoryStore.class);
        when(conversations.loadListingSummaries(any())).thenCallRealMethod();
        when(conversations.loadConversationMemorySnapshot(anyString())).thenAnswer(invocation -> {
            String conversationId = invocation.getArgument(0);
            var snapshot = new ConversationMemorySnapshot();
            snapshot.setUserId("synthetic-user");
            snapshot.setEnvironment(Deployment.Environment.production);
            snapshot.setConversationState(ConversationState.READY);
            String snapshotAgent = snapshotAgents.get(conversationId);
            if (snapshotAgent != null) {
                snapshot.setAgentId(snapshotAgent);
                snapshot.setAgentVersion(2);
            }
            return snapshot;
        });
        IDocumentDescriptorStore documentDescriptors = mock(IDocumentDescriptorStore.class);
        var agentDescriptor = new DocumentDescriptor();
        agentDescriptor.setName(AGENT_NAME);
        when(documentDescriptors.readDescriptor(any(), any())).thenReturn(agentDescriptor);
        ConversationAccessGuard accessGuard = mock(ConversationAccessGuard.class);
        when(accessGuard.seesAllConversations()).thenReturn(true);
        Instance<IAttachmentStore> attachments = mock(Instance.class);

        var rest = new RestConversationStore(documentDescriptors, descriptorStore, conversations, mock(IConversationService.class),
                mock(IUserMemoryStore.class), mock(IRuntime.class), accessGuard, mock(ResourceAccessGuard.class), 30, 90, attachments);
        return rest.readConversationDescriptors(0, 20, null, null, agentId, agentVersion, null, null);
    }

    private static List<String> conversationIds(List<ConversationDescriptor> listed) {
        List<String> ids = new ArrayList<>();
        for (var descriptor : listed) {
            String resource = descriptor.getResource().toString();
            ids.add(resource.substring(resource.lastIndexOf('/') + 1, resource.indexOf('?')));
        }
        return ids;
    }

    private static void assertV6Shaped(Document stored, String agentResource) {
        assertEquals(agentResource, stored.getString("agentResource"));
        assertEquals(AGENT_NAME, stored.getString("agentName"));
        assertFalse(stored.containsKey("botResource"), "botResource is renamed, not copied");
        assertFalse(stored.containsKey("botName"), "botName is renamed, not copied");
        assertEquals(1, stored.getInteger("_version"), "the descriptor's version is part of the conversation's URI");
    }

    @Test
    @DisplayName("first boot: a v5 descriptor, current and history, gets the v6 field names and is listed by its agent")
    void firstBootRenamesAndLists() throws Exception {
        descriptors().insertOne(v5Descriptor(CONVERSATION_A, V5_AGENT_URI));
        Document history = v5Descriptor(CONVERSATION_B, V5_AGENT_URI);
        history.put("_id", new Document("_id", new ObjectId(CONVERSATION_B)).append("_version", 1));
        getDatabase().getCollection("descriptors.history").insertOne(history);

        migrate();

        assertV6Shaped(descriptors().find().first(), V6_AGENT_URI);
        assertV6Shaped(getDatabase().getCollection("descriptors.history").find().first(), V6_AGENT_URI);
        verify(migrationLog).createMigrationLog(argThat(log -> DESCRIPTORS_COMPLETE.equals(log.getName())));
        verify(migrationLog).createMigrationLog(argThat(log -> RENAME_COMPLETE.equals(log.getName())));

        assertEquals(List.of(CONVERSATION_A), conversationIds(list(AGENT, null)));
        assertEquals(List.of(CONVERSATION_A), conversationIds(list(AGENT, 2)));
        assertEquals(List.of(), conversationIds(list(AGENT, 3)));
        assertEquals(List.of(), conversationIds(list(OTHER_AGENT, null)));
    }

    /**
     * A database 6.4 migrated: the URIs are v6 already, the field names still v5,
     * and the rename migration is recorded as complete, so only the catch-up can
     * reach them.
     */
    @Test
    @DisplayName("on a database an earlier 6.x migrated, the catch-up renames the fields and the agent lists them")
    void catchUpAfterAnEarlierMigration() throws Exception {
        migratedByAnEarlier6x();
        descriptors().insertOne(v5Descriptor(CONVERSATION_A, V6_AGENT_URI));
        descriptors().insertOne(v5Descriptor(CONVERSATION_B, V6_AGENT_URI));
        Document agentDescriptor = new Document("_id", new ObjectId()).append("_version", 1)
                .append("resource", V6_AGENT_URI).append("name", AGENT_NAME).append("deleted", false);
        descriptors().insertOne(agentDescriptor);

        migrate();

        assertV6Shaped(descriptors().find(new Document("_id", new ObjectId(CONVERSATION_A))).first(), V6_AGENT_URI);
        assertV6Shaped(descriptors().find(new Document("_id", new ObjectId(CONVERSATION_B))).first(), V6_AGENT_URI);
        assertEquals(agentDescriptor, descriptors().find(new Document("_id", agentDescriptor.get("_id"))).first(),
                "a config descriptor holds no v5 field and is not touched");
        verify(migrationLog).createMigrationLog(argThat(log -> DESCRIPTORS_COMPLETE.equals(log.getName())));
        verify(migrationLog, never()).createMigrationLog(argThat(log -> RENAME_COMPLETE.equals(log.getName())));

        assertEquals(List.of(CONVERSATION_A, CONVERSATION_B), conversationIds(list(AGENT, 2)).stream().sorted().toList());
    }

    @Test
    @DisplayName("once the catch-up is recorded it does not run again")
    void catchUpRunsOnce() {
        when(migrationLog.readMigrationLog(any())).thenAnswer(invocation -> new MigrationLog(invocation.getArgument(0)));
        Document v5 = v5Descriptor(CONVERSATION_A, V6_AGENT_URI);
        descriptors().insertOne(v5);

        migrate();

        assertEquals(v5, descriptors().find().first());
        verify(migrationLog, never()).createMigrationLog(any());
    }

    @Test
    @DisplayName("running it twice changes nothing the second time, on either path")
    void idempotent() {
        descriptors().insertOne(v5Descriptor(CONVERSATION_A, V5_AGENT_URI));
        migrate();
        List<Document> afterFirstBoot = descriptors().find().into(new ArrayList<>());

        migrate();
        migratedByAnEarlier6x();
        migrate();

        assertEquals(afterFirstBoot, descriptors().find().into(new ArrayList<>()));
    }

    @Test
    @DisplayName("a descriptor holding both a v5 and a v6 name is left as it is, and does not fail the migration")
    void ambiguousIsLeftAlone() {
        migratedByAnEarlier6x();
        Document ambiguous = v5Descriptor(CONVERSATION_A, V6_AGENT_URI).append("agentResource",
                "eddi://ai.labs.agent/agentstore/agents/" + OTHER_AGENT + "?version=1");
        descriptors().insertOne(ambiguous);

        migrate();

        assertEquals(ambiguous, descriptors().find().first());
        verify(migrationLog).createMigrationLog(argThat(log -> DESCRIPTORS_COMPLETE.equals(log.getName())));
    }

    /**
     * An earlier 6.x read a v5 descriptor without {@code botResource} and wrote it
     * back whole — on a turn, or when the idle sweep ended the conversation — so
     * the descriptor names no agent under either name. The conversation still does.
     */
    @Test
    @DisplayName("a descriptor an earlier 6.x stripped of its agent gets it back from its conversation")
    void strippedDescriptorIsBackfilled() throws Exception {
        migratedByAnEarlier6x();
        descriptors().insertOne(v5Descriptor(CONVERSATION_A, null));
        descriptors().insertOne(v5Descriptor(CONVERSATION_B, null));
        getDatabase().getCollection("conversationmemories").insertOne(
                new Document("_id", new ObjectId(CONVERSATION_A)).append("agentId", AGENT).append("agentVersion", 2));

        migrate();

        assertEquals(V6_AGENT_URI, descriptors().find(new Document("_id", new ObjectId(CONVERSATION_A))).first().getString("agentResource"));
        assertFalse(descriptors().find(new Document("_id", new ObjectId(CONVERSATION_B))).first().containsKey("agentResource"),
                "a descriptor whose conversation is gone has nothing to get back");
        verify(migrationLog).createMigrationLog(argThat(log -> DESCRIPTORS_COMPLETE.equals(log.getName())));
        assertEquals(List.of(CONVERSATION_A), conversationIds(list(AGENT, 2)));
    }

    @Test
    @DisplayName("a v5 descriptor the migration has not reached reads with its agent, and is written back with v6 names")
    void unmigratedDescriptorReadsThroughTheV5Names() throws Exception {
        descriptors().insertOne(v5Descriptor(CONVERSATION_A, V6_AGENT_URI));

        ConversationDescriptor read = descriptorStore.readDescriptor(CONVERSATION_A, 1);
        assertEquals(URI.create(V6_AGENT_URI), read.getAgentResource());
        assertEquals(AGENT_NAME, read.getAgentName());
        assertEquals(List.of(CONVERSATION_A), conversationIds(list(AGENT, 2)));

        descriptorStore.setDescriptor(CONVERSATION_A, 1, read);
        assertV6Shaped(descriptors().find().first(), V6_AGENT_URI);
    }

    @Test
    @DisplayName("when a document holds both names, the v6 one is read, whichever comes first")
    void v6NameWinsInEitherOrder() throws Exception {
        String v5First = "{\"botResource\":\"eddi://ai.labs.agent/agentstore/agents/" + OTHER_AGENT + "?version=1\",\"botName\":\"v5\","
                + "\"agentResource\":\"" + V6_AGENT_URI + "\",\"agentName\":\"v6\"}";
        String v6First = "{\"agentResource\":\"" + V6_AGENT_URI + "\",\"agentName\":\"v6\","
                + "\"botResource\":\"eddi://ai.labs.agent/agentstore/agents/" + OTHER_AGENT + "?version=1\",\"botName\":\"v5\"}";
        for (String json : List.of(v5First, v6First)) {
            ConversationDescriptor read = objectMapper.readValue(json, ConversationDescriptor.class);
            assertEquals(URI.create(V6_AGENT_URI), read.getAgentResource(), json);
            assertEquals("v6", read.getAgentName(), json);
            String written = objectMapper.writeValueAsString(read);
            assertFalse(written.contains("botResource") || written.contains("botName"), written);
        }
    }

    /**
     * The read-time counterpart of the backfill: a descriptor that names no agent
     * is listed by the agent its conversation names — which covers a descriptor
     * stripped after the catch-up ran, by a replica still on an earlier 6.x.
     */
    @Test
    @DisplayName("a descriptor naming no agent is listed by the agent its conversation names")
    void listingFallsBackToTheConversation() throws Exception {
        descriptors().insertOne(v5Descriptor(CONVERSATION_A, null));
        descriptors().insertOne(v5Descriptor(CONVERSATION_C, null));
        snapshotAgents.put(CONVERSATION_A, AGENT);
        snapshotAgents.put(CONVERSATION_C, OTHER_AGENT);

        assertEquals(List.of(CONVERSATION_A), conversationIds(list(AGENT, 2)));
        assertEquals(List.of(), conversationIds(list(AGENT, 3)));
        assertTrue(descriptors().find(new Document("_id", new ObjectId(CONVERSATION_A))).first().get("agentResource") == null,
                "a listing does not write");
    }
}
