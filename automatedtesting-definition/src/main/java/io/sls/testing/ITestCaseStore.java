package io.sls.testing;

import io.sls.persistence.IResourceStore;
import io.sls.testing.model.TestCase;
import io.sls.testing.model.TestCaseState;

/**
 * User: jarisch
 * Date: 22.11.12
 * Time: 15:49
 */
public interface ITestCaseStore extends IResourceStore<TestCase> {
    String storeTestCase(String id, TestCase testCase) throws IResourceStore.ResourceStoreException;

    TestCase loadTestCase(String id) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    void deleteTestCase(String id) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    void setTestCaseState(String id, TestCaseState testCaseState);

    TestCaseState getTestCaseState(String id);
}
