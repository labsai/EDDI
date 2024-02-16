package ai.labs.eddi.testing.descriptor.model;

import ai.labs.eddi.configs.documentdescriptor.model.DocumentDescriptor;
import ai.labs.eddi.testing.model.TestCaseState;
import lombok.Getter;
import lombok.Setter;

/**
 * @author ginccc
 */

@Getter
@Setter
public class TestCaseDescriptor extends DocumentDescriptor {
    private TestCaseState testCaseState;
}
