package ai.labs.testing.descriptor.model;

import ai.labs.models.DocumentDescriptor;
import ai.labs.testing.model.TestCaseState;
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
