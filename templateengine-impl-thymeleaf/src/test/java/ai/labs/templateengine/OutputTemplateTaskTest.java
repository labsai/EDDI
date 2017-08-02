package ai.labs.templateengine;

import ai.labs.lifecycle.model.Context;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;
import ai.labs.memory.IDataFactory;
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

    @Before
    public void setUp() throws Exception {
        final ITemplatingEngine templatingEngine = mock(ITemplatingEngine.class);
        dataFactory = mock(IDataFactory.class);
        conversationMemory = mock(IConversationMemory.class);
        currentStep = mock(IConversationMemory.IWritableConversationStep.class);
        when(conversationMemory.getCurrentStep()).then(invocation -> currentStep);
        when(currentStep.getAllData("output")).then(invocation -> {
            LinkedList<IData<String>> ret = new LinkedList<>();
            ret.add(new MockData<>("output:someOutput", templateString));
            return ret;
        });

        outputTemplateTask = new OutputTemplateTask(templatingEngine, dataFactory);
        when(templatingEngine.processTemplate(eq(templateString), anyMap())).then(invocation ->
                expectedOutputString);
    }

    @Test
    public void executeTaskWithContextString() throws Exception {
        //setup
        final IData<String> expectedDataObject = new MockData<>("output:templated:someOutput", expectedOutputString);
        when(currentStep.getAllData(eq("context"))).then(invocation -> {
            LinkedList<IData<Context>> ret = new LinkedList<>();
            ret.add(new MockData<>("context:someContext",
                    new Context(Context.ContextType.string, "someContextValue")));
            return ret;
        });
        when(dataFactory.createData(eq("output:templated:someOutput"), eq(expectedOutputString)))
                .then(invocation -> expectedDataObject);

        //test
        outputTemplateTask.executeTask(conversationMemory);

        //assert
        verify(currentStep).getAllData("output");
        verify(currentStep).getAllData("context");
        verify(currentStep).storeData(expectedDataObject);
    }

    @Test
    public void executeTaskWithContextObject() throws Exception {
        //setup
        final TestContextObject testContextObject = new TestContextObject("someContext", "someContextValue");
        final IData<Object> expectedDataObject = new MockData<>("output:templated:someOutput", testContextObject);
        when(currentStep.getAllData(eq("context"))).then(invocation -> {
            LinkedList<IData<Context>> ret = new LinkedList<>();
            ret.add(new MockData<>("context:someContext",
                    new Context(Context.ContextType.object, testContextObject)));
            return ret;
        });
        when(dataFactory.createData(eq("output:templated:someOutput"), eq(expectedOutputString)))
                .then(invocation -> expectedDataObject);

        //test
        outputTemplateTask.executeTask(conversationMemory);

        //assert
        verify(currentStep).getAllData("output");
        verify(currentStep).getAllData("context");
        verify(currentStep).storeData(expectedDataObject);
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

