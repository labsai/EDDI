package ai.labs.testing;

import ai.labs.persistence.IResourceStore;
import ai.labs.serialization.IJsonSerialization;
import ai.labs.testing.model.TestCase;
import ai.labs.testing.model.TestCaseState;
import com.mongodb.BasicDBObject;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.types.ObjectId;

import javax.inject.Inject;
import java.io.IOException;
import java.util.Date;

/**
 * @author ginccc
 */
@Slf4j
public class TestCaseStore implements ITestCaseStore, IResourceStore<TestCase> {
    private static final String TESTCASE_COLLECTION = "testcases";
    private static final String TESTCASE_STATE_FIELD = "testCaseState";
    private final MongoCollection<Document> testcaseCollection;
    private IJsonSerialization jsonSerialization;

    @Inject
    public TestCaseStore(MongoDatabase database, IJsonSerialization jsonSerialization) {
        testcaseCollection = database.getCollection(TESTCASE_COLLECTION);
        this.jsonSerialization = jsonSerialization;
    }

    @Override
    public String storeTestCase(String id, TestCase testCase) throws IResourceStore.ResourceStoreException {
        try {
            testCase.setLastRun(new Date(System.currentTimeMillis()));

            String json = jsonSerialization.serialize(testCase);
            Document document = Document.parse(json);

            if (id != null) {
                document.put("_id", new ObjectId(id));
            }

            testcaseCollection.insertOne(document);

            return document.get("_id").toString();
        } catch (IOException e) {
            log.debug(e.getLocalizedMessage(), e);
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public TestCase loadTestCase(String id) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        Document document = testcaseCollection.find(new Document("_id", new ObjectId(id))).first();

        try {
            if (document == null) {
                String message = "Could not find TestCase (id=%s)";
                message = String.format(message, id);
                throw new IResourceStore.ResourceNotFoundException(message);
            }

            document.remove("_id");

            return jsonSerialization.deserialize(document.toString(), TestCase.class);
        } catch (IOException e) {
            log.debug(e.getLocalizedMessage(), e);
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }
    }

    public void setTestCaseState(String id, TestCaseState testCaseState) {
        BasicDBObject updateTestCaseField = new BasicDBObject("$set", new BasicDBObject(TESTCASE_STATE_FIELD, testCaseState.name()));
        testcaseCollection.updateOne(new BasicDBObject("_id", new ObjectId(id)), updateTestCaseField);
    }

    public void deleteTestCase(String id) {
        testcaseCollection.deleteOne(new BasicDBObject("_id", new ObjectId(id)));
    }

    public TestCaseState getTestCaseState(String id) {
        Document conversationMemoryDocument = testcaseCollection.find(new Document("_id", new ObjectId(id))).first();
        if (conversationMemoryDocument != null) {
            return TestCaseState.valueOf(conversationMemoryDocument.get(TESTCASE_STATE_FIELD).toString());
        }

        return null;
    }

    @Override
    public TestCase readIncludingDeleted(String id, Integer version) throws ResourceNotFoundException, ResourceStoreException {
        return loadTestCase(id);
    }

    @Override
    public IResourceId create(TestCase content) throws ResourceStoreException {
        final String id = storeTestCase(null, content);

        return new IResourceId() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Integer getVersion() {
                return 0;
            }
        };
    }

    @Override
    public TestCase read(String id, Integer version) throws ResourceNotFoundException, ResourceStoreException {
        return loadTestCase(id);
    }

    @Override
    public Integer update(String id, Integer version, TestCase testCase) throws ResourceStoreException {
        storeTestCase(id, testCase);
        return 0;
    }

    @Override
    public void delete(String id, Integer version) {
        //todo implement
    }

    @Override
    public void deleteAllPermanently(String id) {
        //todo implement
    }

    @Override
    public IResourceId getCurrentResourceId(final String id) {
        return new IResourceId() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Integer getVersion() {
                return 0;
            }
        };
    }
}
