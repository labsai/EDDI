/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.modules.output.model.QuickReply;
import ai.labs.eddi.modules.output.model.types.ImageOutputItem;
import ai.labs.eddi.modules.output.model.types.InputFieldOutputItem;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import static ai.labs.eddi.modules.templating.ITemplatingEngine.TemplateMode.HTML;
import static ai.labs.eddi.modules.templating.ITemplatingEngine.TemplateMode.TEXT;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * @author ginccc
 */
public class OutputTemplateTaskTest {
    private static final String KEY_QUICK_REPLY_SOME_ACTION = "quickReplies:someAction";
    private static final String KEY_QUICK_REPLY_SOME_ACTION_PRE_TEMPLATED = "quickReplies:someAction:preTemplated";
    private static final String KEY_QUICK_REPLY_SOME_ACTION_POST_TEMPLATED = "quickReplies:someAction:postTemplated";
    private static final String KEY_OUTPUT_TEXT_SOME_ACTION_PRE_TEMPLATED = "output:text:someAction:preTemplated";
    private static final String KEY_OUTPUT_TEXT_SOME_ACTION_POST_TEMPLATED = "output:text:someAction:postTemplated";
    private static IDataFactory dataFactory;
    private static IConversationMemory conversationMemory;
    private static IConversationMemory.IWritableConversationStep currentStep;
    private static final String templateString = "This is some output with context such as {context}";
    private static OutputTemplateTask outputTemplateTask;
    private static final String expectedOutputString = "This is some output with context such as someContextValue";
    private static ITemplatingEngine templatingEngine;

    @BeforeEach
    public void setUp() {
        templatingEngine = mock(ITemplatingEngine.class);
        dataFactory = mock(IDataFactory.class);
        conversationMemory = mock(IConversationMemory.class);
        currentStep = mock(IConversationMemory.IWritableConversationStep.class);
        when(conversationMemory.getCurrentStep()).then(invocation -> currentStep);
        IMemoryItemConverter memoryTemplateConverter = mock(IMemoryItemConverter.class);
        when(memoryTemplateConverter.convert(any(IConversationMemory.class))).then(invocation -> new HashMap<>());
        ObjectMapper objectMapper = new ObjectMapper();
        outputTemplateTask = new OutputTemplateTask(templatingEngine, memoryTemplateConverter, dataFactory, objectMapper);
    }

    @Test
    public void executeTaskWithContextString() throws Exception {
        // setup
        when(currentStep.getAllData(eq("context"))).then(invocation -> {
            LinkedList<IData<Context>> ret = new LinkedList<>();
            ret.add(new MockData<>("context:someContext", new Context(Context.ContextType.string, "someContextValue")));
            return ret;
        });
        List<QuickReply> expectedPostQuickReplies = setupTask();

        // test
        outputTemplateTask.execute(conversationMemory, null);

        // assert
        verifyTask(expectedPostQuickReplies);
    }

    @Test
    public void executeTaskWithContextObject() throws Exception {
        // setup
        final TestContextObject testContextObject = new TestContextObject("someContext", "someContextValue");
        when(currentStep.getAllData(eq("context"))).then(invocation -> {
            LinkedList<IData<Context>> ret = new LinkedList<>();
            ret.add(new MockData<>("context:someContext", new Context(Context.ContextType.object, testContextObject)));
            return ret;
        });
        List<QuickReply> expectedPostQuickReplies = setupTask();

        // test
        outputTemplateTask.execute(conversationMemory, null);

        // assert
        verifyTask(expectedPostQuickReplies);
    }

    private List<QuickReply> setupTask() throws ITemplatingEngine.TemplateEngineException {
        when(currentStep.getAllData(eq("output"))).then(invocation -> {
            LinkedList<IData<TextOutputItem>> ret = new LinkedList<>();
            ret.add(new MockData<>("output:text:someAction", new TextOutputItem(templateString)));
            return ret;
        });
        List<QuickReply> expectedPreQuickReplies = new LinkedList<>();
        expectedPreQuickReplies.add(new QuickReply("Quick Reply Value {context}", "quickReply(expression)", false));

        List<QuickReply> expectedPostQuickReplies = new LinkedList<>();
        expectedPostQuickReplies.add(new QuickReply("Quick Reply Value someContextValue", "quickReply(expression)", false));

        when(currentStep.getAllData(eq("quickReplies"))).then(invocation -> {
            LinkedList<IData<List<QuickReply>>> ret = new LinkedList<>();
            ret.add(new MockData<>(KEY_QUICK_REPLY_SOME_ACTION, expectedPreQuickReplies));
            ret.add(new MockData<>(KEY_QUICK_REPLY_SOME_ACTION, expectedPostQuickReplies));
            return ret;
        });
        when(dataFactory.createData(eq(KEY_OUTPUT_TEXT_SOME_ACTION_PRE_TEMPLATED), eq(new TextOutputItem(templateString))))
                .then(invocation -> new Data<>(KEY_OUTPUT_TEXT_SOME_ACTION_PRE_TEMPLATED, new TextOutputItem(templateString)));

        when(dataFactory.createData(eq(KEY_OUTPUT_TEXT_SOME_ACTION_POST_TEMPLATED), eq(new TextOutputItem(expectedOutputString))))
                .then(invocation -> new Data<>(KEY_OUTPUT_TEXT_SOME_ACTION_POST_TEMPLATED, new TextOutputItem(expectedOutputString)));

        when(dataFactory.createData(eq(KEY_QUICK_REPLY_SOME_ACTION_PRE_TEMPLATED), anyList()))
                .then(invocation -> new Data<>(KEY_QUICK_REPLY_SOME_ACTION_PRE_TEMPLATED, expectedPreQuickReplies));

        when(dataFactory.createData(eq(KEY_QUICK_REPLY_SOME_ACTION_POST_TEMPLATED), anyList()))
                .then(invocation -> new Data<>(KEY_QUICK_REPLY_SOME_ACTION_POST_TEMPLATED, expectedPostQuickReplies));

        when(templatingEngine.processTemplate(eq(templateString), anyMap(), eq(TEXT))).then(invocation -> expectedOutputString);

        var expectedPreQuickReply = expectedPreQuickReplies.getFirst();
        var expectedPostQuickReply = expectedPostQuickReplies.getFirst();

        String expectedPreQuickReplyValue = expectedPreQuickReply.getValue();
        String expectedPostQuickReplyValue = expectedPostQuickReply.getValue();
        String expectedPostQuickReplyExpressions = expectedPostQuickReply.getExpressions();

        when(templatingEngine.processTemplate(eq(expectedPreQuickReplyValue), anyMap(), eq(TEXT))).then(invocation -> expectedPostQuickReplyValue);
        when(templatingEngine.processTemplate(eq(expectedPreQuickReply.getExpressions()), anyMap(), eq(TEXT)))
                .then(invocation -> expectedPostQuickReplyExpressions);

        when(templatingEngine.processTemplate(eq(expectedPostQuickReplyValue), anyMap(), eq(TEXT))).then(invocation -> expectedPostQuickReplyValue);
        when(templatingEngine.processTemplate(eq(expectedPostQuickReplyExpressions), anyMap(), eq(TEXT)))
                .then(invocation -> expectedPostQuickReplyExpressions);

        return expectedPostQuickReplies;
    }

    private void verifyTask(List<QuickReply> expectedPostQuickReplies) {
        verify(currentStep).getAllData("output");
        verify(currentStep).getAllData("quickReplies");
        verify(dataFactory).createData(eq(KEY_OUTPUT_TEXT_SOME_ACTION_PRE_TEMPLATED), eq(new TextOutputItem(templateString)));
        verify(dataFactory).createData(eq(KEY_OUTPUT_TEXT_SOME_ACTION_POST_TEMPLATED), eq(new TextOutputItem(expectedOutputString)));
        verify(dataFactory, times(2)).createData(eq(KEY_QUICK_REPLY_SOME_ACTION_PRE_TEMPLATED), any());
        verify(dataFactory, times(2)).createData(eq(KEY_QUICK_REPLY_SOME_ACTION_POST_TEMPLATED), eq(expectedPostQuickReplies));
        verify(currentStep, times(9)).storeData(any(IData.class));
    }

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
        public String getOriginWorkflowId() {
            return null;
        }

        @Override
        public void setOriginWorkflowId(String workflowId) {

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

        @Override
        public boolean isCommitted() {
            return true;
        }

        @Override
        public void setCommitted(boolean committed) {

        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            MockData<?> that = (MockData<?>) o;
            return java.util.Objects.equals(key, that.key) && java.util.Objects.equals(result, that.result);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(key, result);
        }
    }

    private record TestContextObject(String key, String value) {
    }

    // ==================== Additional coverage tests ====================

    @Test
    public void getId_returnsCorrectId() {
        assertEquals("ai.labs.templating", outputTemplateTask.getId().name());
    }

    @Test
    public void getType_returnsOutput() {
        assertEquals("output", outputTemplateTask.getType());
    }

    @Test
    public void getExtensionDescriptor_returnsDescriptor() {
        var descriptor = outputTemplateTask.getExtensionDescriptor();
        assertNotNull(descriptor);
        assertEquals("Templating", descriptor.getDisplayName());
    }

    @Test
    public void executeTask_emptyOutputAndQuickReplies() throws Exception {
        when(currentStep.getAllData(eq("output"))).thenReturn(new LinkedList<>());
        when(currentStep.getAllData(eq("quickReplies"))).thenReturn(new LinkedList<>());
        when(currentStep.getAllData(eq("context"))).thenReturn(null);

        outputTemplateTask.execute(conversationMemory, null);

        // Should not fail, no data to store
        verify(currentStep, never()).storeData(any());
    }

    @Test
    public void executeTask_withImageOutputItem() throws Exception {
        when(currentStep.getAllData(eq("context"))).thenReturn(null);

        var imageOutput = new ImageOutputItem("https://example.com/{context}.png", "alt text {context}");
        when(currentStep.getAllData(eq("output"))).then(invocation -> {
            LinkedList<IData<ImageOutputItem>> ret = new LinkedList<>();
            ret.add(new MockData<>("output:image:someAction", imageOutput));
            return ret;
        });
        when(currentStep.getAllData(eq("quickReplies"))).thenReturn(new LinkedList<>());

        when(templatingEngine.processTemplate(eq("https://example.com/{context}.png"), anyMap(), any()))
                .thenReturn("https://example.com/resolved.png");
        when(templatingEngine.processTemplate(eq("alt text {context}"), anyMap(), any()))
                .thenReturn("alt text resolved");

        when(dataFactory.createData(anyString(), any())).thenAnswer(i -> new Data<>(i.getArgument(0), i.getArgument(1)));

        outputTemplateTask.execute(conversationMemory, null);

        verify(templatingEngine).processTemplate(eq("https://example.com/{context}.png"), anyMap(), any());
        verify(templatingEngine).processTemplate(eq("alt text {context}"), anyMap(), any());
    }

    @Test
    public void executeTask_withMapOutput() throws Exception {
        when(currentStep.getAllData(eq("context"))).thenReturn(null);

        var mapOutput = new java.util.LinkedHashMap<String, Object>();
        mapOutput.put("key1", "value with {template}");
        mapOutput.put("key2", 42); // non-string value - should not be templated

        when(currentStep.getAllData(eq("output"))).then(invocation -> {
            LinkedList<IData<Object>> ret = new LinkedList<>();
            ret.add(new MockData<>("output:text:mapAction", mapOutput));
            return ret;
        });
        when(currentStep.getAllData(eq("quickReplies"))).thenReturn(new LinkedList<>());

        when(templatingEngine.processTemplate(eq("value with {template}"), anyMap(), any()))
                .thenReturn("value with resolved");
        when(dataFactory.createData(anyString(), any())).thenAnswer(i -> new Data<>(i.getArgument(0), i.getArgument(1)));

        outputTemplateTask.execute(conversationMemory, null);

        verify(templatingEngine).processTemplate(eq("value with {template}"), anyMap(), any());
    }

    /**
     * E7 — a failed template must never ship raw template syntax to the end user.
     * The failure behaviour is identical in every profile: log + empty string.
     */
    @Test
    public void executeTask_templateEngineThrows_substitutesEmptyStringInsteadOfRawTemplate() throws Exception {
        when(currentStep.getAllData(eq("context"))).thenReturn(null);

        var textOutput = new TextOutputItem("This will {fail}");
        var outputData = new MockData<Object>("output:text:failAction", textOutput);
        when(currentStep.getAllData(eq("output"))).then(invocation -> {
            LinkedList<IData<Object>> ret = new LinkedList<>();
            ret.add(outputData);
            return ret;
        });
        when(currentStep.getAllData(eq("quickReplies"))).thenReturn(new LinkedList<>());

        when(templatingEngine.processTemplate(eq("This will {fail}"), anyMap(), any()))
                .thenThrow(new ITemplatingEngine.TemplateEngineException("Template error", new RuntimeException("cause")));
        when(dataFactory.createData(anyString(), any())).thenAnswer(i -> new Data<>(i.getArgument(0), i.getArgument(1)));

        outputTemplateTask.execute(conversationMemory, null);

        var captor = ArgumentCaptor.forClass(Object.class);
        verify(dataFactory).createData(eq("output:text:failAction:postTemplated"), captor.capture());
        var postTemplated = (TextOutputItem) captor.getValue();
        assertEquals("", postTemplated.getText());
        assertEquals(postTemplated, outputData.getResult());
        // the untemplated original stays available as preTemplated
        assertEquals("This will {fail}", textOutput.getText());
    }

    /**
     * E7 — the quick reply path used to return null into the list on a template
     * failure. Both paths now behave identically.
     */
    @Test
    public void executeTask_quickReplyTemplateThrows_doesNotProduceNullEntries() throws Exception {
        when(currentStep.getAllData(eq("context"))).thenReturn(null);
        when(currentStep.getAllData(eq("output"))).thenReturn(new LinkedList<>());

        List<QuickReply> quickReplies = new LinkedList<>();
        quickReplies.add(new QuickReply("{fail}", "quickReply(expression)", false));
        when(currentStep.getAllData(eq("quickReplies"))).then(invocation -> {
            LinkedList<IData<List<QuickReply>>> ret = new LinkedList<>();
            ret.add(new MockData<>(KEY_QUICK_REPLY_SOME_ACTION, quickReplies));
            return ret;
        });

        when(templatingEngine.processTemplate(eq("{fail}"), anyMap(), eq(TEXT)))
                .thenThrow(new ITemplatingEngine.TemplateEngineException("Template error", new RuntimeException("cause")));
        when(templatingEngine.processTemplate(eq("quickReply(expression)"), anyMap(), eq(TEXT))).thenReturn("quickReply(expression)");
        when(dataFactory.createData(anyString(), any())).thenAnswer(i -> new Data<>(i.getArgument(0), i.getArgument(1)));

        outputTemplateTask.execute(conversationMemory, null);

        var captor = ArgumentCaptor.forClass(Object.class);
        verify(dataFactory).createData(eq(KEY_QUICK_REPLY_SOME_ACTION_POST_TEMPLATED), captor.capture());
        @SuppressWarnings("unchecked")
        var postTemplated = (List<QuickReply>) captor.getValue();
        assertEquals(1, postTemplated.size());
        assertFalse(postTemplated.contains(null));
        assertEquals("", postTemplated.getFirst().getValue());
    }

    /**
     * E9 — "output:html:..." also starts with "output", so the generic prefix used
     * to win and HTML mode was never selected.
     */
    @Test
    public void executeTask_htmlOutputKeySelectsHtmlMode() throws Exception {
        when(currentStep.getAllData(eq("context"))).thenReturn(null);

        var textOutput = new TextOutputItem("<b>{value}</b>");
        when(currentStep.getAllData(eq("output"))).then(invocation -> {
            LinkedList<IData<Object>> ret = new LinkedList<>();
            ret.add(new MockData<>("output:html:someAction", textOutput));
            return ret;
        });
        when(currentStep.getAllData(eq("quickReplies"))).thenReturn(new LinkedList<>());

        when(templatingEngine.processTemplate(eq("<b>{value}</b>"), anyMap(), eq(HTML))).thenReturn("<b>&lt;script&gt;</b>");
        when(dataFactory.createData(anyString(), any())).thenAnswer(i -> new Data<>(i.getArgument(0), i.getArgument(1)));

        outputTemplateTask.execute(conversationMemory, null);

        verify(templatingEngine).processTemplate(eq("<b>{value}</b>"), anyMap(), eq(HTML));
        verify(templatingEngine, never()).processTemplate(anyString(), anyMap(), eq(TEXT));
    }

    /**
     * E13 — inputField (and the four other previously unhandled output types) are
     * templated now that OutputItem forces every subtype to declare its templatable
     * fields.
     */
    @Test
    public void executeTask_inputFieldOutputIsTemplated() throws Exception {
        when(currentStep.getAllData(eq("context"))).thenReturn(null);

        var inputField = new InputFieldOutputItem();
        inputField.setSubType("password");
        inputField.setPlaceholder("Paste {properties.keyName} here");
        inputField.setLabel("{properties.keyName}");
        when(currentStep.getAllData(eq("output"))).then(invocation -> {
            LinkedList<IData<Object>> ret = new LinkedList<>();
            ret.add(new MockData<>("output:inputField:someAction", inputField));
            return ret;
        });
        when(currentStep.getAllData(eq("quickReplies"))).thenReturn(new LinkedList<>());

        when(templatingEngine.processTemplate(eq("Paste {properties.keyName} here"), anyMap(), eq(TEXT))).thenReturn("Paste API Key here");
        when(templatingEngine.processTemplate(eq("{properties.keyName}"), anyMap(), eq(TEXT))).thenReturn("API Key");
        when(dataFactory.createData(anyString(), any())).thenAnswer(i -> new Data<>(i.getArgument(0), i.getArgument(1)));

        outputTemplateTask.execute(conversationMemory, null);

        var captor = ArgumentCaptor.forClass(Object.class);
        verify(dataFactory).createData(eq("output:inputField:someAction:postTemplated"), captor.capture());
        var postTemplated = (InputFieldOutputItem) captor.getValue();
        assertEquals("Paste API Key here", postTemplated.getPlaceholder());
        assertEquals("API Key", postTemplated.getLabel());
        assertEquals("inputField", postTemplated.getType());
        assertEquals("password", postTemplated.getSubType());
        // the original is left untouched for the preTemplated data entry
        assertEquals("Paste {properties.keyName} here", inputField.getPlaceholder());
    }
}
