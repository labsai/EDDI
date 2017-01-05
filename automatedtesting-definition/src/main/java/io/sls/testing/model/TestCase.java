package io.sls.testing.model;

import io.sls.memory.model.ConversationMemorySnapshot;
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
