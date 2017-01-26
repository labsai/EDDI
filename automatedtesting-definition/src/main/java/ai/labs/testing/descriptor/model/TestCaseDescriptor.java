package ai.labs.testing.descriptor.model;

import ai.labs.resources.rest.documentdescriptor.model.DocumentDescriptor;
import ai.labs.testing.model.TestCaseState;

/**
 * @author ginccc
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
