/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.configs.migration.IMigrationLogStore;
import ai.labs.eddi.configs.migration.V6RenameMigration;
import ai.labs.eddi.configs.migration.model.MigrationLog;
import ai.labs.eddi.engine.model.AgentDeployment;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.triggermanagement.mongo.AgentTriggerStore;
import ai.labs.eddi.engine.triggermanagement.mongo.UserConversationStore;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rename migration against EDDI 5 triggers and user-conversation mappings,
 * read back through the 6.x stores.
 *
 * <p>
 * EDDI 5 kept managed-conversation triggers in {@code bottriggers}, with
 * {@code botDeployments[].botId}, and each user's conversation per intent in
 * {@code userconversations} with {@code botId}. Neither was migrated: after the
 * upgrade no intent had a trigger, and an existing user's intent resolved to no
 * agent. Ids and values are synthetic.
 * </p>
 */
@DisplayName("V6RenameMigration — managed-conversation triggers and user mappings")
class V6RenameMigrationTriggersTest extends MongoTestBase {

    private static final String AGENT_A = "0000000000000000000000f1";
    private static final String AGENT_B = "0000000000000000000000f2";
    private static final String CONVERSATION = "0000000000000000000000f3";

    private IMigrationLogStore migrationLog;
    private AgentTriggerStore triggers;
    private UserConversationStore userConversations;

    @BeforeEach
    void clean() {
        dropCollections("bottriggers", "agenttriggers", "userconversations");
        migrationLog = mock(IMigrationLogStore.class);
        when(migrationLog.readMigrationLog(any())).thenReturn(null);
    }

    private static Document v5Deployment(String environment, String botId) {
        return new Document("environment", environment).append("botId", botId).append("initialContext", new Document());
    }

    private static void insertV5Trigger(String intent, Document... deployments) {
        getDatabase().getCollection("bottriggers")
                .insertOne(new Document("_id", new ObjectId()).append("intent", intent).append("botDeployments", List.of(deployments)));
    }

    private static void insertV5UserConversation(String intent, String userId) {
        getDatabase().getCollection("userconversations").insertOne(new Document("_id", new ObjectId()).append("userId", userId)
                .append("botId", AGENT_A).append("conversationId", CONVERSATION).append("environment", "unrestricted").append("intent", intent));
    }

    /**
     * Startup order: the stores are built (and create their collections and
     * indexes) before the migration runs — which is why the rename has to cope with
     * an empty {@code agenttriggers} already being there.
     */
    private void boot() {
        triggers = new AgentTriggerStore(getDatabase(), jsonSerialization, documentBuilder);
        userConversations = new UserConversationStore(getDatabase(), jsonSerialization, documentBuilder);
        new V6RenameMigration(getDatabase(), migrationLog, true).runIfNeeded();
    }

    @Test
    @DisplayName("a v5 trigger is found by its intent, with its agent and a v6 environment")
    void triggerIsFoundByIntent() throws Exception {
        insertV5Trigger("synthetic-intent", v5Deployment("unrestricted", AGENT_A));

        boot();

        verify(migrationLog).createMigrationLog(argThat(log -> "v6-rename-migration-complete".equals(log.getName())));
        var trigger = triggers.readAgentTrigger("synthetic-intent");
        assertEquals(1, trigger.getAgentDeployments().size());
        AgentDeployment deployment = trigger.getAgentDeployments().getFirst();
        assertEquals(AGENT_A, deployment.getAgentId());
        assertEquals(Deployment.Environment.production, deployment.getEnvironment());
        Document stored = getDatabase().getCollection("agenttriggers").find().first();
        assertFalse(stored.containsKey("botDeployments"));
        assertEquals("production", stored.getList("agentDeployments", Document.class).getFirst().getString("environment"));
        assertFalse(stored.getList("agentDeployments", Document.class).getFirst().containsKey("botId"));
        assertEquals(0, getDatabase().getCollection("bottriggers").countDocuments());
    }

    @Test
    @DisplayName("an existing user's intent resolves to their old conversation and agent")
    void userConversationResolves() throws Exception {
        insertV5UserConversation("synthetic-intent", "synthetic-user");

        boot();

        var mapping = userConversations.readUserConversation("synthetic-intent", "synthetic-user");
        assertNotNull(mapping);
        assertEquals(AGENT_A, mapping.getAgentId());
        assertEquals(CONVERSATION, mapping.getConversationId());
        assertEquals(Deployment.Environment.production, mapping.getEnvironment());
        Document stored = getDatabase().getCollection("userconversations").find().first();
        assertFalse(stored.containsKey("botId"));
        assertEquals("production", stored.getString("environment"));
    }

    @Test
    @DisplayName("an agent listed for both v5 environments is kept once; a different agent is kept too")
    void collidingEnvironmentsAreDeduplicated() throws Exception {
        insertV5Trigger("synthetic-intent", v5Deployment("unrestricted", AGENT_A), v5Deployment("restricted", AGENT_A),
                v5Deployment("restricted", AGENT_B));

        boot();

        List<String> agents = new ArrayList<>();
        for (var deployment : triggers.readAgentTrigger("synthetic-intent").getAgentDeployments()) {
            agents.add(deployment.getAgentId());
            assertEquals(Deployment.Environment.production, deployment.getEnvironment());
        }
        assertEquals(List.of(AGENT_A, AGENT_B), agents);
    }

    @Test
    @DisplayName("a user mapping holding both botId and a different agentId is left as it is")
    void ambiguousUserConversationIsLeftAlone() {
        MongoCollection<Document> collection = getDatabase().getCollection("userconversations");
        Document ambiguous = new Document("_id", new ObjectId()).append("userId", "synthetic-user").append("botId", AGENT_A)
                .append("agentId", AGENT_B).append("conversationId", CONVERSATION).append("environment", "unrestricted")
                .append("intent", "synthetic-intent");
        collection.insertOne(ambiguous);

        boot();

        assertEquals(ambiguous, collection.find().first());
        verify(migrationLog).createMigrationLog(argThat(log -> "v6-rename-migration-complete".equals(log.getName())));
    }

    @Test
    @DisplayName("running the migration twice changes nothing the second time")
    void idempotent() {
        insertV5Trigger("synthetic-intent", v5Deployment("unrestricted", AGENT_A));
        insertV5UserConversation("synthetic-intent", "synthetic-user");
        boot();
        List<Document> triggersAfterFirst = getDatabase().getCollection("agenttriggers").find().into(new ArrayList<>());
        List<Document> mappingsAfterFirst = getDatabase().getCollection("userconversations").find().into(new ArrayList<>());

        new V6RenameMigration(getDatabase(), migrationLog, true).runIfNeeded();

        assertEquals(triggersAfterFirst, getDatabase().getCollection("agenttriggers").find().into(new ArrayList<>()));
        assertEquals(mappingsAfterFirst, getDatabase().getCollection("userconversations").find().into(new ArrayList<>()));
        assertTrue(triggersAfterFirst.getFirst().containsKey("agentDeployments"));
    }

    /**
     * Before 6.5 the rename migration did not touch triggers. A database it already
     * migrated has them under {@code bottriggers}, where the 6.x store never looks.
     */
    @Test
    @DisplayName("on a database an earlier 6.x migrated, the triggers left under bottriggers are migrated")
    void triggersCaughtUpAfterAnEarlierMigration() throws Exception {
        insertV5Trigger("synthetic-intent", v5Deployment("unrestricted", AGENT_A));
        insertV5UserConversation("synthetic-intent", "synthetic-user");
        when(migrationLog.readMigrationLog(any())).thenReturn(new MigrationLog("v6-rename-migration-complete"));

        boot();

        assertEquals(AGENT_A, triggers.readAgentTrigger("synthetic-intent").getAgentDeployments().getFirst().getAgentId());
        assertEquals(0, getDatabase().getCollection("bottriggers").countDocuments());
        assertFalse(getDatabase().getCollection("userconversations").find().first().containsKey("botId"));
        verify(migrationLog, never()).createMigrationLog(any());
    }

    @Test
    @DisplayName("v5 environments are matched ignoring case, as the per-document pass did")
    void environmentCaseIsIgnored() {
        getDatabase().getCollection("userconversations").insertOne(new Document("_id", new ObjectId()).append("userId", "synthetic-user")
                .append("agentId", AGENT_A).append("conversationId", CONVERSATION).append("environment", "Unrestricted")
                .append("intent", "synthetic-intent"));

        boot();

        assertEquals("production", getDatabase().getCollection("userconversations").find().first().getString("environment"));
    }

    @Test
    @DisplayName("a trigger entry without an environment does not gain an explicit null one")
    void absentEnvironmentStaysAbsent() {
        insertV5Trigger("synthetic-intent", new Document("botId", AGENT_A));

        boot();

        Document entry = getDatabase().getCollection("agenttriggers").find().first().getList("agentDeployments", Document.class).getFirst();
        assertFalse(entry.containsKey("environment"));
        assertEquals(AGENT_A, entry.getString("agentId"));
    }

    @Test
    @DisplayName("a v5 mapping the migration has not reached still resolves to its agent")
    void unmigratedMappingLoadsThroughTheAlias() throws Exception {
        insertV5UserConversation("synthetic-intent", "synthetic-user");
        userConversations = new UserConversationStore(getDatabase(), jsonSerialization, documentBuilder);

        var mapping = userConversations.readUserConversation("synthetic-intent", "synthetic-user");

        assertEquals(AGENT_A, mapping.getAgentId());
        assertEquals(Deployment.Environment.production, mapping.getEnvironment());
    }
}
