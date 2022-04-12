package ai.labs.eddi.testing;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.testing.model.TestCase;
import ai.labs.eddi.testing.model.TestCaseState;

/**
 * @author ginccc
 */
public interface ITestCaseStore extends IResourceStore<TestCase> {
    String storeTestCase(String id, TestCase testCase) throws IResourceStore.ResourceStoreException;

    TestCase loadTestCase(String id) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    void deleteTestCase(String id) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    void setTestCaseState(String id, TestCaseState testCaseState);

    TestCaseState getTestCaseState(String id);
}
