package ai.labs.behavior.impl.extensions;

import ai.labs.expressions.Expression;
import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.lifecycle.model.Context;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;
import ai.labs.serialization.IJsonSerialization;
import lombok.EqualsAndHashCode;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.*;

import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;

/**
 * @author ginccc
 */
public class ContextMatcherTest {
    private ContextMatcher contextMatcher;
    private IExpressionProvider expressionProvider;
    private IJsonSerialization jsonSerialization;
    private IConversationMemory conversationMemory;
    private IConversationMemory.IWritableConversationStep currentStep;

    @Before
    public void setUp() throws Exception {
        expressionProvider = mock(IExpressionProvider.class);
        jsonSerialization = mock(IJsonSerialization.class);
        contextMatcher = new ContextMatcher(expressionProvider, jsonSerialization);
        conversationMemory = mock(IConversationMemory.class);
        currentStep = mock(IConversationMemory.IWritableConversationStep.class);
        when(conversationMemory.getCurrentStep()).then(invocation -> currentStep);
    }

    @Test
    public void getValuesWithExpressions() throws Exception {
        //setup
        final HashMap<String, String> expected = setupValuesWithExpressions();

        //test
        Map<String, String> actual = contextMatcher.getValues();

        //assert
        Assert.assertEquals(expected, actual);
    }

    private HashMap<String, String> setupValuesWithExpressions() {
        final HashMap<String, String> expected = new HashMap<>();
        expected.put("contextKey", "someContextKey");
        expected.put("contextType", ContextMatcher.ContextType.expressions.toString());
        final String expressions = "expression(test),anotherExpression(test1)";
        expected.put("expressions", expressions);
        when(expressionProvider.parseExpressions(eq(expressions))).thenAnswer(invocation -> {
            List<Expression> ret = new LinkedList<>();
            ret.add(new Expression("expression", new Expression("test")));
            ret.add(new Expression("anotherExpression", new Expression("test1")));
            return ret;
        });
        contextMatcher.setValues(expected);
        return expected;
    }

    @Test
    public void getValuesWithObject() throws Exception {
        //setup
        final HashMap<String, String> expected = setupValuesWithObject(true);

        //test
        Map<String, String> actual = contextMatcher.getValues();

        //assert
        Assert.assertEquals(expected, actual);
    }

    private HashMap<String, String> setupValuesWithObject(boolean includeObjectValue) {
        final HashMap<String, String> expected = new HashMap<>();
        expected.put("contextKey", "someContextKey");
        expected.put("contextType", ContextMatcher.ContextType.object.toString());
        final String objectKeyPath = "$.userInfo.name.firstName";
        expected.put("objectKeyPath", objectKeyPath);
        if (includeObjectValue) {
            final String objectValue = "John";
            expected.put("objectValue", objectValue);
        }
        contextMatcher.setValues(expected);
        return expected;
    }

    @Test
    public void getValuesWithString() throws Exception {
        //setup
        final HashMap<String, String> expected = setupValuesWithString();

        //test
        Map<String, String> actual = contextMatcher.getValues();

        //assert
        Assert.assertEquals(expected, actual);
    }

    private HashMap<String, String> setupValuesWithString() {
        final HashMap<String, String> expected = new HashMap<>();
        expected.put("contextKey", "someContextKey");
        expected.put("contextType", ContextMatcher.ContextType.string.toString());
        expected.put("string", "someString");
        contextMatcher.setValues(expected);
        return expected;
    }

    @Test
    public void executeWithExpressionForSuccess() throws Exception {
        //setup
        final HashMap<String, String> values = setupValuesWithExpressions();
        when(currentStep.getAllData(eq("context"))).then(invocation -> {
            LinkedList<IData<Context>> ret = new LinkedList<>();
            ret.add(new MockData<>("context:someContextKey",
                    new Context(Context.ContextType.expressions, values.get("expressions"))));
            return ret;
        });

        //test
        IBehaviorExtension.ExecutionState actualExecutionState =
                contextMatcher.execute(conversationMemory, new LinkedList<>());

        //assert
        verify(currentStep).getAllData("context");
        verify(expressionProvider, times(2)).parseExpressions(values.get("expressions"));
        Assert.assertEquals(IBehaviorExtension.ExecutionState.SUCCESS, actualExecutionState);

    }

    @Test
    public void executeWithExpressionForFail() throws Exception {
        //setup
        final HashMap<String, String> values = setupValuesWithExpressions();
        final String otherExpressions = "someOtherExpressions(than_expected)";
        when(currentStep.getAllData(eq("context"))).then(invocation -> {
            LinkedList<IData<Context>> ret = new LinkedList<>();
            ret.add(new MockData<>("context:someContextKey",
                    new Context(Context.ContextType.expressions, otherExpressions)));
            return ret;
        });

        //test
        IBehaviorExtension.ExecutionState actualExecutionState =
                contextMatcher.execute(conversationMemory, new LinkedList<>());

        //assert
        verify(currentStep).getAllData("context");
        verify(expressionProvider).parseExpressions(values.get("expressions"));
        verify(expressionProvider).parseExpressions(otherExpressions);
        Assert.assertEquals(IBehaviorExtension.ExecutionState.FAIL, actualExecutionState);
    }

    @Test
    public void executeWithObjectKeyAndValueForSuccess() throws Exception {
        //setup
        setupValuesWithObject(true);
        final String contextJson = "{\"userInfo\":{\"name\":{\"firstName\":\"John\",\"lastName\":\"Silver\"}}}";
        final ContextMatcher.ObjectValue objectValue =
                new ContextMatcher.ObjectValue("someKeyPath", "someObjectValue");
        when(jsonSerialization.deserialize(eq(contextJson), eq(Object.class))).thenAnswer(invocation -> objectValue);
        when(jsonSerialization.serialize(eq(objectValue))).thenAnswer(invocation -> contextJson);
        when(currentStep.getAllData(eq("context"))).then(invocation -> {
            LinkedList<IData<Context>> ret = new LinkedList<>();
            ret.add(new MockData<>("context:someContextKey",
                    new Context(Context.ContextType.object,
                            jsonSerialization.deserialize(contextJson, Object.class))));
            return ret;
        });

        //test
        IBehaviorExtension.ExecutionState actualExecutionState =
                contextMatcher.execute(conversationMemory, new LinkedList<>());

        //assert
        verify(currentStep).getAllData("context");
        verify(jsonSerialization).serialize(objectValue);
        verify(jsonSerialization).deserialize(contextJson, Object.class);
        Assert.assertEquals(IBehaviorExtension.ExecutionState.SUCCESS, actualExecutionState);
    }

    @Test
    public void executeWithObjectKeyAndValueForFail() throws Exception {
        //setup
        setupValuesWithObject(true);
        final String contextJson = "{\"userInfo\":{\"name\":{\"firstName\":\"Albert\",\"lastName\":\"Silver\"}}}";
        final ContextMatcher.ObjectValue objectValue =
                new ContextMatcher.ObjectValue("someKeyPath", "someObjectValue");
        when(jsonSerialization.deserialize(eq(contextJson), eq(Object.class))).thenAnswer(invocation -> objectValue);
        when(jsonSerialization.serialize(eq(objectValue))).thenAnswer(invocation -> contextJson);
        when(currentStep.getAllData(eq("context"))).then(invocation -> {
            LinkedList<IData<Context>> ret = new LinkedList<>();
            ret.add(new MockData<>("context:someContextKey",
                    new Context(Context.ContextType.object,
                            jsonSerialization.deserialize(contextJson, Object.class))));
            return ret;
        });

        //test
        IBehaviorExtension.ExecutionState actualExecutionState =
                contextMatcher.execute(conversationMemory, new LinkedList<>());

        //assert
        verify(currentStep).getAllData("context");
        verify(jsonSerialization).serialize(objectValue);
        verify(jsonSerialization).deserialize(contextJson, Object.class);
        Assert.assertEquals(IBehaviorExtension.ExecutionState.FAIL, actualExecutionState);
    }

    @Test
    public void executeWithObjectKeyOnlyForSuccess() throws Exception {
        //setup
        setupValuesWithObject(false);
        final String contextJson = "{\"userInfo\":{\"name\":{\"firstName\":\"John\",\"lastName\":\"Silver\"}}}";
        final ContextMatcher.ObjectValue objectValue =
                new ContextMatcher.ObjectValue("someKeyPath", null);
        when(jsonSerialization.deserialize(eq(contextJson), eq(Object.class))).thenAnswer(invocation -> objectValue);
        when(jsonSerialization.serialize(eq(objectValue))).
                thenAnswer(invocation -> contextJson);
        when(currentStep.getAllData(eq("context"))).then(invocation -> {
            LinkedList<IData<Context>> ret = new LinkedList<>();
            ret.add(new MockData<>("context:someContextKey",
                    new Context(Context.ContextType.object,
                            jsonSerialization.deserialize(contextJson, Object.class))));
            return ret;
        });

        //test
        IBehaviorExtension.ExecutionState actualExecutionState =
                contextMatcher.execute(conversationMemory, new LinkedList<>());

        //assert
        verify(currentStep).getAllData("context");
        verify(jsonSerialization).serialize(objectValue);
        verify(jsonSerialization).deserialize(contextJson, Object.class);
        Assert.assertEquals(IBehaviorExtension.ExecutionState.SUCCESS, actualExecutionState);
    }

    @Test
    public void executeWithObjectKeyOnlyForFail() throws Exception {
        //setup
        setupValuesWithObject(false);
        final String contextJson = "{\"userInfo\":\"somethingElse\"}";
        final ContextMatcher.ObjectValue objectValue =
                new ContextMatcher.ObjectValue("someKeyPath", null);
        when(jsonSerialization.deserialize(eq(contextJson), eq(Object.class))).thenAnswer(invocation -> objectValue);
        when(jsonSerialization.serialize(eq(objectValue))).
                thenAnswer(invocation -> contextJson);
        when(currentStep.getAllData(eq("context"))).then(invocation -> {
            LinkedList<IData<Context>> ret = new LinkedList<>();
            ret.add(new MockData<>("context:someContextKey",
                    new Context(Context.ContextType.object,
                            jsonSerialization.deserialize(contextJson, Object.class))));
            return ret;
        });

        //test
        IBehaviorExtension.ExecutionState actualExecutionState =
                contextMatcher.execute(conversationMemory, new LinkedList<>());

        //assert
        verify(currentStep).getAllData("context");
        verify(jsonSerialization).serialize(objectValue);
        verify(jsonSerialization).deserialize(contextJson, Object.class);
        Assert.assertEquals(IBehaviorExtension.ExecutionState.FAIL, actualExecutionState);
    }

    @Test
    public void executeWithStringForSuccess() throws Exception {
        //setup
        setupValuesWithString();
        when(currentStep.getAllData(eq("context"))).then(invocation -> {
            LinkedList<IData<Context>> ret = new LinkedList<>();
            ret.add(new MockData<>("context:someContextKey",
                    new Context(Context.ContextType.string, "someString")));
            return ret;
        });

        //test
        IBehaviorExtension.ExecutionState actualExecutionState = contextMatcher.execute(conversationMemory, new LinkedList<>());

        //assert
        Assert.assertEquals(IBehaviorExtension.ExecutionState.SUCCESS, actualExecutionState);
    }

    @Test
    public void executeWithStringForFail() throws Exception {
        //setup
        setupValuesWithString();
        when(currentStep.getAllData(eq("context"))).then(invocation -> {
            LinkedList<IData<Context>> ret = new LinkedList<>();
            ret.add(new MockData<>("context:someContextKey",
                    new Context(Context.ContextType.string, "someStringOtherString")));
            return ret;
        });

        //test
        IBehaviorExtension.ExecutionState actualExecutionState = contextMatcher.execute(conversationMemory, new LinkedList<>());

        //assert
        Assert.assertEquals(IBehaviorExtension.ExecutionState.FAIL, actualExecutionState);
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
}
