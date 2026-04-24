/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.triggermanagement.mongo;

import ai.labs.eddi.engine.triggermanagement.IAgentTriggerStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.ResourceAlreadyExistsException;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.triggermanagement.model.AgentTriggerConfiguration;
import ai.labs.eddi.utils.RuntimeUtilities;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * MongoDB implementation of {@link IAgentTriggerStore}. Annotated
 * {@code @DefaultBean} so PostgreSQL can override.
 *
 * @author ginccc
 */
@ApplicationScoped
@DefaultBean
public class AgentTriggerStore implements IAgentTriggerStore {
    private static final String COLLECTION_AGENT_TRIGGERS = "agenttriggers";
    private static final String INTENT_FIELD = "intent";
    private final MongoCollection<Document> collection;
    private final IDocumentBuilder documentBuilder;
    private final IJsonSerialization jsonSerialization;
    private final AgentTriggerResourceStore agentTriggerStore;

    @Inject
    public AgentTriggerStore(MongoDatabase database, IJsonSerialization jsonSerialization, IDocumentBuilder documentBuilder) {
        this.jsonSerialization = jsonSerialization;
        RuntimeUtilities.checkNotNull(database, "database");
        this.collection = database.getCollection(COLLECTION_AGENT_TRIGGERS);
        this.documentBuilder = documentBuilder;
        this.agentTriggerStore = new AgentTriggerResourceStore();
        collection.createIndex(Indexes.ascending(INTENT_FIELD), new IndexOptions().unique(true));
    }

    @Override
    public List<AgentTriggerConfiguration> readAllAgentTriggers() throws IResourceStore.ResourceStoreException {
        return agentTriggerStore.readAllAgentTriggers();
    }

    @Override
    public AgentTriggerConfiguration readAgentTrigger(String intent)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        RuntimeUtilities.checkNotNull(intent, INTENT_FIELD);

        return agentTriggerStore.readAgentTrigger(intent);
    }

    @Override
    public void updateAgentTrigger(String intent, AgentTriggerConfiguration agentTriggerConfiguration)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        RuntimeUtilities.checkNotNull(intent, INTENT_FIELD);
        RuntimeUtilities.checkNotNull(agentTriggerConfiguration, "AgentTriggerConfiguration");

        agentTriggerStore.updateAgentTrigger(intent, agentTriggerConfiguration);
    }

    @Override
    public void createAgentTrigger(AgentTriggerConfiguration agentTriggerConfiguration)
            throws ResourceAlreadyExistsException, IResourceStore.ResourceStoreException {
        RuntimeUtilities.checkNotNull(agentTriggerConfiguration, "AgentTriggerConfiguration");

        agentTriggerStore.createAgentTrigger(agentTriggerConfiguration);
    }

    @Override
    public void deleteAgentTrigger(String intent) {
        RuntimeUtilities.checkNotNull(intent, INTENT_FIELD);

        agentTriggerStore.deleteAgentTrigger(intent);
    }

    private class AgentTriggerResourceStore {
        AgentTriggerConfiguration readAgentTrigger(String intent)
                throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

            Document filter = new Document();
            filter.put(INTENT_FIELD, intent);

            try {
                Document document = collection.find(filter).first();
                if (document == null) {
                    String message = "AgentTriggerConfiguration with intent=%s does not exist";
                    message = String.format(message, intent);
                    throw new IResourceStore.ResourceNotFoundException(message);
                }
                return documentBuilder.build(document, AgentTriggerConfiguration.class);
            } catch (IOException e) {
                throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
            }
        }

        List<AgentTriggerConfiguration> readAllAgentTriggers() throws IResourceStore.ResourceStoreException {

            List<AgentTriggerConfiguration> agentTriggers = new ArrayList<>();
            try {
                for (var document : collection.find()) {
                    agentTriggers.add(documentBuilder.build(document, AgentTriggerConfiguration.class));
                }

                return agentTriggers;
            } catch (IOException e) {
                throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
            }
        }

        void updateAgentTrigger(String intent, AgentTriggerConfiguration agentTriggerConfiguration)
                throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

            Document document = createDocument(agentTriggerConfiguration);
            var result = collection.replaceOne(new Document(INTENT_FIELD, intent), document);
            if (result.getMatchedCount() == 0) {
                String message = "AgentTriggerConfiguration with intent=%s does not exist";
                message = String.format(message, intent);
                throw new IResourceStore.ResourceNotFoundException(message);
            }
        }

        void createAgentTrigger(AgentTriggerConfiguration agentTriggerConfiguration)
                throws IResourceStore.ResourceStoreException, ResourceAlreadyExistsException {

            Document existing = collection.find(new Document(INTENT_FIELD, agentTriggerConfiguration.getIntent())).first();
            if (existing != null) {
                String message = "AgentTriggerConfiguration with intent=%s already exists";
                message = String.format(message, agentTriggerConfiguration.getIntent());
                throw new ResourceAlreadyExistsException(message);
            }

            collection.insertOne(createDocument(agentTriggerConfiguration));
        }

        void deleteAgentTrigger(String intent) {
            collection.deleteOne(new Document(INTENT_FIELD, intent));
        }

        private Document createDocument(AgentTriggerConfiguration agentTriggerConfiguration) throws IResourceStore.ResourceStoreException {
            try {
                return jsonSerialization.deserialize(jsonSerialization.serialize(agentTriggerConfiguration), Document.class);
            } catch (IOException e) {
                throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
            }
        }
    }
}
