package io.sls.testing.model;

import io.sls.memory.model.ConversationMemorySnapshot;

import java.util.Date;

/**
 * User: jarisch
 * Date: 22.11.12
 * Time: 14:30
 */
public class TestCase {
    private String botId;
    private Integer botVersion;
    private TestCaseState testCaseState;
    private Date lastRun;
    private ConversationMemorySnapshot expected;
    private ConversationMemorySnapshot actual;

    public String getBotId() {
        return botId;
    }

    public void setBotId(String botId) {
        this.botId = botId;
    }

    public Integer getBotVersion() {
        return botVersion;
    }

    public void setBotVersion(Integer botVersion) {
        this.botVersion = botVersion;
    }

    public TestCaseState getTestCaseState() {
        return testCaseState;
    }

    public void setTestCaseState(TestCaseState testCaseState) {
        this.testCaseState = testCaseState;
    }

    public Date getLastRun() {
        return lastRun;
    }

    public void setLastRun(Date lastRun) {
        this.lastRun = lastRun;
    }

    public ConversationMemorySnapshot getExpected() {
        return expected;
    }

    public void setExpected(ConversationMemorySnapshot expected) {
        this.expected = expected;
    }

    public ConversationMemorySnapshot getActual() {
        return actual;
    }

    public void setActual(ConversationMemorySnapshot actual) {
        this.actual = actual;
    }
}
