package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.LogEntry;
import ai.labs.eddi.utils.RuntimeUtilities;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import io.quarkus.arc.DefaultBean;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * MongoDB implementation of {@link IDatabaseLogs}. Annotated
 * {@code @DefaultBean} so PostgreSQL can override.
 *
 * @author ginccc
 */
@ApplicationScoped
@DefaultBean
@IfBuildProfile("!postgres")
public class DatabaseLogs implements IDatabaseLogs {
    private static final String COLLECTION_NAME = "logs";
    private static final String KEY_AGENT_ID = "agentId";
    private static final String KEY_AGENT_VERSION = "agentVersion";
    private static final String KEY_ENVIRONMENT = "environment";
    private static final String KEY_CONVERSATION_ID = "conversationId";
    private static final String KEY_USER_ID = "userId";
    private static final String KEY_INSTANCE_ID = "instanceId";
    private static final String KEY_MESSAGE = "message";
    private static final String KEY_LEVEL = "level";
    private static final String KEY_LOGGER = "loggerName";
    public static final String TIMESTAMP = "timestamp";
    private final MongoCollection<Document> logsCollection;

    private static final Logger log = Logger.getLogger(DatabaseLogs.class);

    @Inject
    public DatabaseLogs(MongoDatabase database) {
        RuntimeUtilities.checkNotNull(database, "database");
        logsCollection = database.getCollection(COLLECTION_NAME);
    }

    @Override
    public List<LogEntry> getLogs(Environment environment, String agentId, Integer agentVersion, String conversationId, String userId,
            String instanceId, Integer skip, Integer limit) {
        return getLogs(createFilter(environment, agentId, agentVersion, conversationId, userId, instanceId), skip, limit);
    }

    @Override
    public void addLogsBatch(List<LogEntry> entries) {
        if (entries == null || entries.isEmpty())
            return;

        List<Document> documents = new ArrayList<>(entries.size());
        for (LogEntry entry : entries) {
            Document doc = new Document();
            doc.put(KEY_MESSAGE, entry.message());
            doc.put(KEY_LEVEL, entry.level());
            doc.put(KEY_LOGGER, entry.loggerName());
            doc.put(TIMESTAMP, new Date(entry.timestamp()));
            doc.put(KEY_ENVIRONMENT, entry.environment());
            doc.put(KEY_AGENT_ID, entry.agentId());
            doc.put(KEY_AGENT_VERSION, entry.agentVersion());
            if (entry.conversationId() != null) {
                doc.put(KEY_CONVERSATION_ID, entry.conversationId());
            }
            if (entry.userId() != null) {
                doc.put(KEY_USER_ID, entry.userId());
            }
            if (entry.instanceId() != null) {
                doc.put(KEY_INSTANCE_ID, entry.instanceId());
            }
            documents.add(doc);
        }

        try {
            logsCollection.insertMany(documents);
        } catch (Exception e) {
            log.errorv("Failed to batch-insert {0} log entries: {1}", entries.size(), e.getMessage());
        }
    }

    private Document createFilter(Environment environment, String agentId, Integer agentVersion, String conversationId, String userId,
            String instanceId) {
        Document filter = new Document();
        if (environment != null) {
            filter.put(KEY_ENVIRONMENT, environment.toString());
        }
        if (agentId != null) {
            filter.put(KEY_AGENT_ID, agentId);
        }
        if (agentVersion != null) {
            filter.put(KEY_AGENT_VERSION, agentVersion);
        }
        if (conversationId != null) {
            filter.put(KEY_CONVERSATION_ID, conversationId);
        }
        if (userId != null) {
            filter.put(KEY_USER_ID, userId);
        }
        if (instanceId != null) {
            filter.put(KEY_INSTANCE_ID, instanceId);
        }

        return filter;
    }

    private List<LogEntry> getLogs(Document filter, Integer skip, Integer limit) {
        List<LogEntry> ret = new ArrayList<>();
        var iterable = logsCollection.find(filter).sort(new Document(TIMESTAMP, -1));
        if (limit > 0) {
            iterable.limit(limit);
        }
        if (skip > 0) {
            iterable.skip(skip);
        }

        for (Document doc : iterable) {
            Date ts = doc.getDate(TIMESTAMP);
            ret.add(new LogEntry(ts != null ? ts.getTime() : 0L, doc.getString(KEY_LEVEL), doc.getString(KEY_LOGGER), doc.getString(KEY_MESSAGE),
                    doc.getString(KEY_ENVIRONMENT), doc.getString(KEY_AGENT_ID), doc.getInteger(KEY_AGENT_VERSION),
                    doc.getString(KEY_CONVERSATION_ID), doc.getString(KEY_USER_ID), doc.getString(KEY_INSTANCE_ID)));
        }

        return ret;
    }
}
