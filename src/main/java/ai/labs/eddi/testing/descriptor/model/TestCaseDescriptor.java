package ai.labs.eddi.testing.descriptor.model;

import ai.labs.eddi.configs.documentdescriptor.model.DocumentDescriptor;
import ai.labs.eddi.testing.model.TestCaseState;

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
