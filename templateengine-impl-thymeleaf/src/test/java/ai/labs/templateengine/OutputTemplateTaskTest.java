package ai.labs.templateengine;

import ai.labs.lifecycle.model.Context;
import ai.labs.memory.Data;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;
import ai.labs.memory.IDataFactory;
import ai.labs.output.model.QuickReply;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import org.junit.Before;
import org.junit.Test;

import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import static org.mockito.Mockito.*;

/**
 * @author ginccc
 */
public class OutputTemplateTaskTest {
    private IDataFactory dataFactory;
    private IConversationMemory conversationMemory;
    private IConversationMemory.IWritableConversationStep currentStep;
    private String templateString = "This is some output with context such as [[${context}]]";
    private OutputTemplateTask outputTemplateTask;
    private final String expectedOutputString = "This is some output with context such as someContextValue";
    private ITemplatingEngine templatingEngine;

    @Before
    public void setUp() throws Exception {
        templatingEngine = mock(ITemplatingEngine.class);
        dataFactory = mock(IDataFactory.class);
        conversationMemory = mock(IConversationMemory.class);
        currentStep = mock(IConversationMemory.IWritableConversationStep.class);
        when(conversationMemory.getCurrentStep()).then(invocation -> currentStep);
        outputTemplateTask = new OutputTemplateTask(templatingEngine, dataFactory);
    }

    @Test
    public void executeTaskWithContextString() throws Exception {
        //setup
        when(currentStep.getAllData(eq("context"))).then(invocation -> {
            LinkedList<IData<Context>> ret = new LinkedList<>();
            ret.add(new MockData<>("context:someContext",
                    new Context(Context.ContextType.string, "someContextValue")));
            return ret;
        });
        List<QuickReply> expectedPostQuickReplies = setupTask();

        //test
        outputTemplateTask.executeTask(conversationMemory);

        //assert
        verifyTask(expectedPostQuickReplies);
    }

    @Test
    public void executeTaskWithContextObject() throws Exception {
        //setup
        final TestContextObject testContextObject = new TestContextObject("someContext", "someContextValue");
        when(currentStep.getAllData(eq("context"))).then(invocation -> {
            LinkedList<IData<Context>> ret = new LinkedList<>();
            ret.add(new MockData<>("context:someContext",
                    new Context(Context.ContextType.object, testContextObject)));
            return ret;
        });
        List<QuickReply> expectedPostQuickReplies = setupTask();

        //test
        outputTemplateTask.executeTask(conversationMemory);

        //assert
        verifyTask(expectedPostQuickReplies);
    }

    private List<QuickReply> setupTask() {
        when(currentStep.getAllData(eq("output"))).then(invocation -> {
            LinkedList<IData<String>> ret = new LinkedList<>();
            ret.add(new MockData<>("output:text:someAction", templateString));
            return ret;
        });
        List<QuickReply> expectedPreQuickReplies = new LinkedList<>();
        expectedPreQuickReplies.add(new QuickReply(
                "Quick Reply Value [[${context}]]",
                "quickReply(expression)"));
        List<QuickReply> expectedPostQuickReplies = new LinkedList<>();
        expectedPostQuickReplies.add(new QuickReply(
                "Quick Reply Value someContextValue",
                "quickReply(expression)"));
        when(currentStep.getAllData(eq("quickReplies"))).then(invocation -> {
            LinkedList<IData<List<QuickReply>>> ret = new LinkedList<>();
            ret.add(new MockData<>("quickReply:someAction", expectedPreQuickReplies));
            return ret;
        });
        when(dataFactory.createData(eq("output:text:someAction:preTemplated"), eq(templateString)))
                .then(invocation -> new Data<>("output:text:someAction:preTemplated", templateString));

        when(dataFactory.createData(eq("output:text:someAction:postTemplated"), eq(expectedOutputString)))
                .then(invocation -> new Data<>("output:text:someAction:postTemplated", expectedOutputString));

        when(dataFactory.createData(eq("quickReply:someAction:preTemplated"), anyList()))
                .then(invocation -> new Data<>("quickReply:someAction:preTemplated", expectedPreQuickReplies));

        when(dataFactory.createData(eq("quickReply:someAction:postTemplated"), anyList()))
                .then(invocation -> new Data<>("quickReply:someAction:postTemplated", expectedPostQuickReplies));

        when(templatingEngine.processTemplate(eq(templateString), anyMap())).then(invocation ->
                expectedOutputString);
        when(templatingEngine.processTemplate(eq(expectedPreQuickReplies.get(0).getValue()), anyMap())).
                then(invocation -> expectedPostQuickReplies.get(0).getValue());
        return expectedPostQuickReplies;
    }

    private void verifyTask(List<QuickReply> expectedPostQuickReplies) {
        verify(currentStep).getAllData("output");
        verify(currentStep).getAllData("quickReplies");
        verify(currentStep).getAllData("context");
        verify(dataFactory).createData(eq("output:text:someAction:preTemplated"), eq(templateString));
        verify(dataFactory).createData(eq("output:text:someAction:postTemplated"), eq(expectedOutputString));
        verify(dataFactory).createData(eq("quickReply:someAction:preTemplated"), anyList());
        verify(dataFactory).createData(eq("quickReply:someAction:postTemplated"), eq(expectedPostQuickReplies));
        verify(currentStep, times(6)).storeData(any(IData.class));
    }


    @EqualsAndHashCode
    private static class MockData<T> implements IData<T> {
        private final String key;
        private T result;

        MockData(String key, T result) {
            this.key = key;
            this.result = result;
        }

        @Override
        public String getKey() {
            return key;
        }

        @Override
        public List<T> getPossibleResults() {
            return null;
        }

        @Override
        public T getResult() {
            return result;
        }

        @Override
        public Date getTimestamp() {
            return null;
        }

        @Override
        public boolean isPublic() {
            return false;
        }

        @Override
        public void setPossibleResults(List<T> possibleResults) {

        }

        @Override
        public void setResult(T result) {
            this.result = result;
        }

        @Override
        public void setPublic(boolean isPublic) {

        }
    }

    @Getter
    @Setter
    @AllArgsConstructor
    private static class TestContextObject {
        private String key;
        private String value;
    }
}

