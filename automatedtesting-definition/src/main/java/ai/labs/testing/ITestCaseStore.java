package ai.labs.testing;

import ai.labs.persistence.IResourceStore;
import ai.labs.testing.model.TestCase;
import ai.labs.testing.model.TestCaseState;

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
