package io.sls.testing;

import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.util.JSON;
import io.sls.persistence.IResourceStore;
import io.sls.serialization.JSONSerialization;
import io.sls.testing.model.TestCase;
import io.sls.testing.model.TestCaseState;
import org.bson.types.ObjectId;
import org.codehaus.jackson.type.TypeReference;

import javax.inject.Inject;
import java.io.IOException;
import java.util.Date;

/**
 * Copyright by Spoken Language System. All rights reserved.
 * User: jarisch
 * Date: 22.11.12
 * Time: 15:57
 */
public class TestCaseStore implements ITestCaseStore, IResourceStore<TestCase> {
    private static final String TESTCASE_COLLECTION = "testcases";
    public static final String TESTCASE_STATE_FIELD = "testCaseState";
    private final DBCollection testcaseCollection;

    @Inject
    public TestCaseStore(DB database) {
        testcaseCollection = database.getCollection(TESTCASE_COLLECTION);
    }

    @Override
    public String storeTestCase(String id, TestCase testCase) throws IResourceStore.ResourceStoreException {
        try {
            testCase.setLastRun(new Date(System.currentTimeMillis()));

            String json = JSONSerialization.serialize(testCase);
            DBObject document = (DBObject) JSON.parse(json);

            if (id != null) {
                document.put("_id", new ObjectId(id));
            }

            testcaseCollection.save(document);

            return document.get("_id").toString();
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public TestCase loadTestCase(String id) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        DBObject document = testcaseCollection.findOne(new BasicDBObject("_id", new ObjectId(id)));

        try {
            if (document == null) {
                String message = "Could not find TestCase (id=%s)";
                message = String.format(message, id);
                throw new IResourceStore.ResourceNotFoundException(message);
            }

            document.removeField("_id");

            TestCase testCase = JSONSerialization.deserialize(document.toString(), new TypeReference<TestCase>() {
            });

            return testCase;
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }
    }

    public void setTestCaseState(String conversationId, TestCaseState testCaseState) {
        BasicDBObject updateTestCaseField = new BasicDBObject("$set", new BasicDBObject(TESTCASE_STATE_FIELD, testCaseState.name()));
        testcaseCollection.update(new BasicDBObject("_id", new ObjectId(conversationId)), updateTestCaseField);
    }

    public void deleteTestCase(String conversationId) throws ResourceStoreException, ResourceNotFoundException {
        testcaseCollection.remove(new BasicDBObject("_id", new ObjectId(conversationId)));
    }

    public TestCaseState getTestCaseState(String conversationId) {
        DBObject conversationMemoryDocument = testcaseCollection.findOne(new BasicDBObject("_id", new ObjectId(conversationId)));
        if (conversationMemoryDocument != null) {
            return TestCaseState.valueOf(conversationMemoryDocument.get(TESTCASE_STATE_FIELD).toString());
        }

        return null;
    }

    @Override
    public IResourceId create(TestCase content) throws ResourceStoreException {
        final String conversationId = storeTestCase(null, content);

        return new IResourceId() {
            @Override
            public String getId() {
                return conversationId;
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
    public Integer update(String id, Integer version, TestCase testCase) throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {
        storeTestCase(id, testCase);
        return 0;
    }

    @Override
    public void delete(String id, Integer version) throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {
        //todo implement
    }

    @Override
    public void deleteAllPermanently(String id) {
        //todo implement
    }

    @Override
    public IResourceId getCurrentResourceId(final String id) throws ResourceNotFoundException {
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
