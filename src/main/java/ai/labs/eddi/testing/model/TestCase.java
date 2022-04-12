package ai.labs.eddi.testing.model;

import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * @author ginccc
 */

@Getter
@Setter
public class TestCase {
    private String botId;
    private Integer botVersion;
    private TestCaseState testCaseState;
    private Date lastRun;
    private ConversationMemorySnapshot expected;
    private ConversationMemorySnapshot actual;
}
