package ai.labs.property.impl;

import ai.labs.expressions.Expression;
import ai.labs.expressions.Expressions;
import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.expressions.value.Value;
import ai.labs.lifecycle.LifecycleException;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;
import ai.labs.memory.IDataFactory;
import ai.labs.memory.IMemoryItemConverter;
import ai.labs.memory.model.ConversationProperties;
import ai.labs.memory.model.Data;
import ai.labs.models.Context;
import ai.labs.models.Property;
import ai.labs.property.IPropertySetter;
import ai.labs.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.templateengine.ITemplatingEngine;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatchers;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import static ai.labs.models.Property.Scope.conversation;
import static org.mockito.AdditionalMatchers.not;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author ginccc
 */
public class PropertySetterTaskTest {
    private static final String KEY_EXPRESSIONS_PARSED = "expressions:parsed";
    private static final String KEY_ACTIONS = "actions";
    private static final String KEY_CONTEXT = "context";
    private static final String KEY_INPUT_INITIAL = "input:initial";
    private PropertySetterTask propertySetterTask;
    private IConversationMemory conversationMemory;
    private IConversationMemory.IWritableConversationStep currentStep;
    private IConversationMemory.IConversationStep previousStep;
    private IPropertySetter propertySetter;
    private IDataFactory dataFactory;
    private Expressions expressions;

    @Before
    public void setUp() {
        propertySetter = mock(IPropertySetter.class);
        dataFactory = mock(IDataFactory.class);
        conversationMemory = mock(IConversationMemory.class);
        currentStep = mock(IConversationMemory.IWritableConversationStep.class);
        IConversationMemory.IConversationStepStack previousConversationSteps = mock(IConversationMemory.IConversationStepStack.class);
        previousStep = mock(IConversationMemory.IConversationStep.class);
        when(previousConversationSteps.get(eq(0))).thenAnswer(invocation -> previousStep);
        when(conversationMemory.getCurrentStep()).thenAnswer(invocation -> currentStep);
        when(conversationMemory.getPreviousSteps()).thenAnswer(invocation -> previousConversationSteps);
        IExpressionProvider expressionProvider = mock(IExpressionProvider.class);
        String exp = "property(someMeaning(someValue))";
        when(expressionProvider.parseExpressions(eq(exp))).thenAnswer(invocation -> {
            expressions = new Expressions();
            expressions.add(
                    new Expression("property",
                            new Expression("someMeaning",
                                    new Value("someValue"))));

            return expressions;
        });
        when(expressionProvider.parseExpressions(not(eq(exp)))).thenAnswer(invocation -> new Expressions());
        IMemoryItemConverter memoryItemConverter = mock(IMemoryItemConverter.class);
        ITemplatingEngine templateEngine = mock(ITemplatingEngine.class);
        IResourceClientLibrary resourceClientLibrary = mock(IResourceClientLibrary.class);
        propertySetterTask = new PropertySetterTask(propertySetter,
                expressionProvider, memoryItemConverter, templateEngine, dataFactory, resourceClientLibrary);
    }

    @Test
    public void executeTask() throws LifecycleException {
        //setup
        final String userInput = "Some Input From the User";
        List<Property> propertyEntries = new LinkedList<>();
        propertyEntries.add(new Property("someMeaning", "someValue", conversation));

        propertyEntries.add(new Property("user_input", userInput, conversation));
        propertyEntries.add(new Property("someContextMeaning", "someContextValue", conversation));
        IData<List<Property>> expectedPropertyData = new Data<>("properties:extracted", propertyEntries);

        final String propertyExpression = "property(someMeaning(someValue))";
        when(currentStep.getLatestData(eq(KEY_EXPRESSIONS_PARSED))).thenAnswer(invocation ->
                new Data<>(KEY_EXPRESSIONS_PARSED, propertyExpression));
        when(propertySetter.extractProperties(eq(expressions))).thenAnswer(invocation -> {
            List<Property> ret = new LinkedList<>();
            ret.add(new Property("someMeaning", "someValue", conversation));
            return ret;
        });
        when(conversationMemory.getPreviousSteps().size()).thenAnswer(invocation -> 1);
        when(previousStep.getLatestData(eq(KEY_ACTIONS))).thenAnswer(invocation ->
                new Data<>(KEY_ACTIONS, Arrays.asList("CATCH_ANY_INPUT_AS_PROPERTY", "someOtherAction")));
        when(currentStep.getLatestData(eq(KEY_INPUT_INITIAL))).thenAnswer(invocation ->
                new Data<>(KEY_INPUT_INITIAL, userInput));
        when(currentStep.getAllData(KEY_CONTEXT)).thenAnswer(invocation -> {
            Context context = new Context();
            context.setType(Context.ContextType.expressions);
            context.setValue("property(someContextMeaning(someContextValue))");
            return Collections.singletonList(new Data<>(KEY_CONTEXT + ":" + "properties", context));
        });
        when(dataFactory.createData(eq("properties:extracted"), any(List.class), eq(true))).
                thenAnswer(invocation -> {
                    Data<List<Property>> ret = new Data<>("properties:extracted", propertyEntries);
                    ret.setPublic(true);
                    return ret;
                });
        when(conversationMemory.getConversationProperties()).thenAnswer(invocation -> new ConversationProperties(conversationMemory));

        //test
        propertySetterTask.executeTask(conversationMemory);

        //assert
        verify(currentStep, times(1)).getLatestData(KEY_EXPRESSIONS_PARSED);
        verify(previousStep, times(1)).getLatestData(KEY_ACTIONS);
        verify(currentStep, times(1)).getLatestData(KEY_INPUT_INITIAL);
        verify(currentStep, times(1)).getAllData(KEY_CONTEXT);
        verify(currentStep, times(1)).storeData(ArgumentMatchers.eq(expectedPropertyData));
        verify(conversationMemory, times(1)).getConversationProperties();
    }
}