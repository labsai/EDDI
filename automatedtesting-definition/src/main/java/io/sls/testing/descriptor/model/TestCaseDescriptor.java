package io.sls.testing.descriptor.model;

import io.sls.resources.rest.documentdescriptor.model.DocumentDescriptor;
import io.sls.testing.model.TestCaseState;

/**
 * User: jarisch
 * Date: 22.11.12
 * Time: 14:37
 */
public class TestCaseDescriptor extends DocumentDescriptor {
    private TestCaseState testCaseState;

    public TestCaseState getTestCaseState() {
        return testCaseState;
    }

    public void setTestCaseState(TestCaseState testCaseState) {
        this.testCaseState = testCaseState;
    }
}
