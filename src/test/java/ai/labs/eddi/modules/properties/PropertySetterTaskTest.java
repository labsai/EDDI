/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.properties;

import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.model.ConversationProperties;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import ai.labs.eddi.modules.nlp.expressions.value.Value;
import ai.labs.eddi.modules.properties.impl.PropertySetterTask;
import ai.labs.eddi.modules.properties.model.SetOnActions;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import ai.labs.eddi.configs.properties.model.PropertyInstruction;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static ai.labs.eddi.configs.properties.model.Property.Scope.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalMatchers.not;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for PropertySetterTask — EDDI's importance extraction
 * mechanism.
 * <p>
 * PropertySetter is a critical pipeline component: it extracts properties from
 * expressions, context, and action-based instructions, and manages property
 * scopes (step, conversation, longTerm, secret).
 *
 * @author ginccc
 */
@DisplayName("PropertySetterTask")
public class PropertySetterTaskTest {
    private static final String KEY_EXPRESSIONS_PARSED = "expressions:parsed";
    private static final String KEY_ACTIONS = "actions";
    private static final String KEY_CONTEXT = "context";
    private static final String KEY_INPUT_INITIAL = "input:initial";

    private PropertySetterTask propertySetterTask;
    private IConversationMemory conversationMemory;
    private IConversationMemory.IWritableConversationStep currentStep;
    private IConversationMemory.IConversationStep previousStep;
    private IConversationMemory.IConversationStepStack previousConversationSteps;
    private IDataFactory dataFactory;
    private IExpressionProvider expressionProvider;
    private IMemoryItemConverter memoryItemConverter;
    private ITemplatingEngine templateEngine;
    private ai.labs.eddi.secrets.ISecretProvider secretProvider;
    private ConversationProperties conversationProperties;
    private Expressions expressions;

    @BeforeEach
    public void setUp() {
        dataFactory = mock(IDataFactory.class);
        conversationMemory = mock(IConversationMemory.class);
        currentStep = mock(IConversationMemory.IWritableConversationStep.class);
        previousConversationSteps = mock(IConversationMemory.IConversationStepStack.class);
        previousStep = mock(IConversationMemory.IConversationStep.class);
        when(previousConversationSteps.get(eq(0))).thenAnswer(invocation -> previousStep);
        when(conversationMemory.getCurrentStep()).thenAnswer(invocation -> currentStep);
        when(conversationMemory.getPreviousSteps()).thenAnswer(invocation -> previousConversationSteps);

        expressionProvider = mock(IExpressionProvider.class);
        String exp = "property(someMeaning(someValue))";
        when(expressionProvider.parseExpressions(eq(exp))).thenAnswer(invocation -> {
            expressions = new Expressions();
            expressions.add(new Expression("property", new Expression("someMeaning", new Value("someValue"))));
            return expressions;
        });
        when(expressionProvider.parseExpressions(not(eq(exp)))).thenAnswer(invocation -> new Expressions());

        memoryItemConverter = mock(IMemoryItemConverter.class);
        templateEngine = mock(ITemplatingEngine.class);
        IResourceClientLibrary resourceClientLibrary = mock(IResourceClientLibrary.class);
        secretProvider = mock(ai.labs.eddi.secrets.ISecretProvider.class);

        conversationProperties = new ConversationProperties(conversationMemory);
        when(conversationMemory.getConversationProperties()).thenReturn(conversationProperties);

        propertySetterTask = new PropertySetterTask(expressionProvider, memoryItemConverter, templateEngine, dataFactory, resourceClientLibrary,
                new ObjectMapper(), secretProvider);
    }

    // ==================== Identity Tests ====================

    @Nested
    @DisplayName("Task Identity")
    class IdentityTests {

        @Test
        @DisplayName("getId should return correct identifier")
        void testGetId() {
            assertEquals("ai.labs.property", propertySetterTask.getId());
        }

        @Test
        @DisplayName("getType should return 'properties'")
        void testGetType() {
            assertEquals("properties", propertySetterTask.getType());
        }
    }

    // ==================== Expression Extraction Tests ====================

    @Nested
    @DisplayName("Expression-based property extraction")
    class ExpressionExtractionTests {

        @Test
        @DisplayName("should extract property from parsed expressions and CATCH_ANY_INPUT_AS_PROPERTY action")
        void executeTask_fullExtractionPath() throws LifecycleException {
            // setup
            final String userInput = "Some Input From the User";
            List<Property> propertyEntries = new LinkedList<>();
            propertyEntries.add(new Property("someMeaning", "someValue", conversation));
            propertyEntries.add(new Property("user_input", userInput, conversation));
            propertyEntries.add(new Property("someContextMeaning", "someContextValue", conversation));
            IData<List<Property>> expectedPropertyData = new Data<>("properties:extracted", propertyEntries);

            IPropertySetter propertySetter = mock(IPropertySetter.class);
            final String propertyExpression = "property(someMeaning(someValue))";
            when(currentStep.getLatestData(eq(KEY_EXPRESSIONS_PARSED))).thenReturn(new Data<>(KEY_EXPRESSIONS_PARSED, propertyExpression));
            when(propertySetter.extractProperties(eq(expressions))).thenAnswer(invocation -> {
                List<Property> ret = new LinkedList<>();
                ret.add(new Property("someMeaning", "someValue", conversation));
                return ret;
            });
            when(previousConversationSteps.size()).thenReturn(1);
            when(previousStep.getLatestData(eq(KEY_ACTIONS)))
                    .thenReturn(new Data<>(KEY_ACTIONS, Arrays.asList("CATCH_ANY_INPUT_AS_PROPERTY", "someOtherAction")));
            when(currentStep.getLatestData(eq(KEY_INPUT_INITIAL))).thenReturn(new Data<>(KEY_INPUT_INITIAL, userInput));
            when(currentStep.getAllData(KEY_CONTEXT)).thenAnswer(invocation -> {
                Context context = new Context();
                context.setType(Context.ContextType.expressions);
                context.setValue("property(someContextMeaning(someContextValue))");
                return Collections.singletonList(new Data<>(KEY_CONTEXT + ":" + "properties", context));
            });
            when(dataFactory.createData(eq("properties:extracted"), any(List.class), eq(true))).thenAnswer(invocation -> {
                Data<List<Property>> ret = new Data<>("properties:extracted", propertyEntries);
                ret.setPublic(true);
                return ret;
            });

            // test
            propertySetterTask.execute(conversationMemory, propertySetter);

            // assert — verify all data sources were read
            verify(currentStep, times(1)).getLatestData(KEY_EXPRESSIONS_PARSED);
            verify(previousStep, times(1)).getLatestData(KEY_ACTIONS);
            verify(currentStep, times(1)).getLatestData(KEY_INPUT_INITIAL);
            verify(currentStep, times(1)).getAllData(KEY_CONTEXT);
            verify(currentStep, times(1)).storeData(eq(expectedPropertyData));
            verify(conversationMemory, times(1)).getConversationProperties();
        }
    }

    // ==================== No-op / Early Return Tests ====================

    @Nested
    @DisplayName("Early return paths")
    class EarlyReturnTests {

        @Test
        @DisplayName("should return immediately when no expressions, context, or actions are present")
        void execute_noData_returnsImmediately() throws LifecycleException {
            IPropertySetter propertySetter = mock(IPropertySetter.class);

            when(currentStep.getLatestData(KEY_EXPRESSIONS_PARSED)).thenReturn(null);
            when(currentStep.getAllData(KEY_CONTEXT)).thenReturn(null);
            when(currentStep.getLatestData(KEY_ACTIONS)).thenReturn(null);

            propertySetterTask.execute(conversationMemory, propertySetter);

            // Should not try to extract properties or store anything
            verify(propertySetter, never()).extractProperties(any());
            verify(currentStep, never()).storeData(any());
        }

        @Test
        @DisplayName("should skip CATCH_ANY_INPUT when no previous steps exist")
        void execute_noPreviousSteps_skipsCatchAny() throws LifecycleException {
            IPropertySetter propertySetter = mock(IPropertySetter.class);

            // Only expressions present — no actions, no context
            when(currentStep.getLatestData(KEY_EXPRESSIONS_PARSED)).thenReturn(
                    new Data<>(KEY_EXPRESSIONS_PARSED, "property(someMeaning(someValue))"));
            when(currentStep.getAllData(KEY_CONTEXT)).thenReturn(null);
            when(currentStep.getLatestData(KEY_ACTIONS)).thenReturn(null);

            when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>(
                    List.of(new Property("someMeaning", "someValue", conversation))));
            when(previousConversationSteps.size()).thenReturn(0); // no previous steps

            when(dataFactory.createData(eq("properties:extracted"), any(List.class), eq(true)))
                    .thenReturn(new Data<>("properties:extracted", List.of()));

            propertySetterTask.execute(conversationMemory, propertySetter);

            // Should NOT attempt to read previous step actions
            verify(previousStep, never()).getLatestData(KEY_ACTIONS);
        }

        @Test
        @DisplayName("should skip user input capture when previous action is NOT CATCH_ANY_INPUT_AS_PROPERTY")
        void execute_previousActionNotCatchAny_skipsCapture() throws LifecycleException {
            IPropertySetter propertySetter = mock(IPropertySetter.class);

            when(currentStep.getLatestData(KEY_EXPRESSIONS_PARSED)).thenReturn(
                    new Data<>(KEY_EXPRESSIONS_PARSED, "property(someMeaning(someValue))"));
            when(currentStep.getAllData(KEY_CONTEXT)).thenReturn(null);
            when(currentStep.getLatestData(KEY_ACTIONS)).thenReturn(null);

            when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>(
                    List.of(new Property("someMeaning", "someValue", conversation))));

            when(previousConversationSteps.size()).thenReturn(1);
            // Previous action is NOT CATCH_ANY_INPUT_AS_PROPERTY
            when(previousStep.getLatestData(KEY_ACTIONS))
                    .thenReturn(new Data<>(KEY_ACTIONS, List.of("greet")));

            when(dataFactory.createData(eq("properties:extracted"), any(List.class), eq(true)))
                    .thenReturn(new Data<>("properties:extracted", List.of()));

            propertySetterTask.execute(conversationMemory, propertySetter);

            // Should NOT attempt to read user input
            verify(currentStep, never()).getLatestData(KEY_INPUT_INITIAL);
        }
    }

    // ==================== Action-based Property Instructions ====================

    @Nested
    @DisplayName("Action-based property instructions (setOnActions)")
    class ActionBasedPropertyTests {

        @Test
        @DisplayName("should set valueString property on matching action")
        void execute_setOnAction_setsStringProperty() throws Exception {
            IPropertySetter propertySetter = mock(IPropertySetter.class);

            // No expressions or context
            when(currentStep.getLatestData(KEY_EXPRESSIONS_PARSED)).thenReturn(null);
            when(currentStep.getAllData(KEY_CONTEXT)).thenReturn(null);

            // Current step has action "greet"
            when(currentStep.getLatestData(KEY_ACTIONS)).thenReturn(new Data<>(KEY_ACTIONS, List.of("greet")));

            // Configure setOnActions with a string property instruction
            PropertyInstruction instruction = new PropertyInstruction();
            instruction.setName("greeting_type");
            instruction.setValueString("friendly");
            instruction.setScope(conversation);
            instruction.setOverride(true);

            SetOnActions setOnAction = new SetOnActions();
            setOnAction.setActions(List.of("greet"));
            setOnAction.setSetProperties(List.of(instruction));

            when(propertySetter.getSetOnActionsList()).thenReturn(List.of(setOnAction));
            when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>());
            when(previousConversationSteps.size()).thenReturn(0);
            when(memoryItemConverter.convert(conversationMemory)).thenReturn(new HashMap<>());
            when(templateEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            propertySetterTask.execute(conversationMemory, propertySetter);

            // Verify property was stored in conversation properties
            assertTrue(conversationProperties.containsKey("greeting_type"));
            assertEquals("friendly", conversationProperties.get("greeting_type").getValueString());
            assertEquals(conversation, conversationProperties.get("greeting_type").getScope());
        }

        @Test
        @DisplayName("should set longTerm scoped property on matching action")
        void execute_setOnAction_setsLongTermProperty() throws Exception {
            IPropertySetter propertySetter = mock(IPropertySetter.class);

            when(currentStep.getLatestData(KEY_EXPRESSIONS_PARSED)).thenReturn(null);
            when(currentStep.getAllData(KEY_CONTEXT)).thenReturn(null);
            when(currentStep.getLatestData(KEY_ACTIONS)).thenReturn(new Data<>(KEY_ACTIONS, List.of("remember")));

            PropertyInstruction instruction = new PropertyInstruction();
            instruction.setName("preferred_language");
            instruction.setValueString("German");
            instruction.setScope(longTerm);
            instruction.setOverride(true);

            SetOnActions setOnAction = new SetOnActions();
            setOnAction.setActions(List.of("remember"));
            setOnAction.setSetProperties(List.of(instruction));

            when(propertySetter.getSetOnActionsList()).thenReturn(List.of(setOnAction));
            when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>());
            when(previousConversationSteps.size()).thenReturn(0);
            when(memoryItemConverter.convert(conversationMemory)).thenReturn(new HashMap<>());
            when(templateEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            propertySetterTask.execute(conversationMemory, propertySetter);

            assertTrue(conversationProperties.containsKey("preferred_language"));
            assertEquals("German", conversationProperties.get("preferred_language").getValueString());
            assertEquals(longTerm, conversationProperties.get("preferred_language").getScope());
        }

        @Test
        @DisplayName("should NOT override existing property when override=false")
        void execute_setOnAction_noOverride() throws Exception {
            IPropertySetter propertySetter = mock(IPropertySetter.class);

            // Pre-populate the property
            conversationProperties.put("language", new Property("language", "English", conversation));

            when(currentStep.getLatestData(KEY_EXPRESSIONS_PARSED)).thenReturn(null);
            when(currentStep.getAllData(KEY_CONTEXT)).thenReturn(null);
            when(currentStep.getLatestData(KEY_ACTIONS)).thenReturn(new Data<>(KEY_ACTIONS, List.of("set_lang")));

            PropertyInstruction instruction = new PropertyInstruction();
            instruction.setName("language");
            instruction.setValueString("German");
            instruction.setScope(conversation);
            instruction.setOverride(false); // should NOT override

            SetOnActions setOnAction = new SetOnActions();
            setOnAction.setActions(List.of("set_lang"));
            setOnAction.setSetProperties(List.of(instruction));

            when(propertySetter.getSetOnActionsList()).thenReturn(List.of(setOnAction));
            when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>());
            when(previousConversationSteps.size()).thenReturn(0);
            when(memoryItemConverter.convert(conversationMemory)).thenReturn(new HashMap<>());
            when(templateEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            propertySetterTask.execute(conversationMemory, propertySetter);

            // Should keep original value "English", NOT overwritten to "German"
            assertEquals("English", conversationProperties.get("language").getValueString());
        }

        @Test
        @DisplayName("should match wildcard action '*'")
        void execute_setOnAction_wildcardMatch() throws Exception {
            IPropertySetter propertySetter = mock(IPropertySetter.class);

            when(currentStep.getLatestData(KEY_EXPRESSIONS_PARSED)).thenReturn(null);
            when(currentStep.getAllData(KEY_CONTEXT)).thenReturn(null);
            when(currentStep.getLatestData(KEY_ACTIONS)).thenReturn(new Data<>(KEY_ACTIONS, List.of("any_random_action")));

            PropertyInstruction instruction = new PropertyInstruction();
            instruction.setName("timestamp");
            instruction.setValueString("2026-04-11");
            instruction.setScope(step);
            instruction.setOverride(true);

            SetOnActions setOnAction = new SetOnActions();
            setOnAction.setActions(List.of("*")); // wildcard
            setOnAction.setSetProperties(List.of(instruction));

            when(propertySetter.getSetOnActionsList()).thenReturn(List.of(setOnAction));
            when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>());
            when(previousConversationSteps.size()).thenReturn(0);
            when(memoryItemConverter.convert(conversationMemory)).thenReturn(new HashMap<>());
            when(templateEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            propertySetterTask.execute(conversationMemory, propertySetter);

            assertTrue(conversationProperties.containsKey("timestamp"));
            assertEquals("2026-04-11", conversationProperties.get("timestamp").getValueString());
        }

        @Test
        @DisplayName("should NOT set property when action does not match")
        void execute_setOnAction_noMatch() throws Exception {
            IPropertySetter propertySetter = mock(IPropertySetter.class);

            when(currentStep.getLatestData(KEY_EXPRESSIONS_PARSED)).thenReturn(null);
            when(currentStep.getAllData(KEY_CONTEXT)).thenReturn(null);
            when(currentStep.getLatestData(KEY_ACTIONS)).thenReturn(new Data<>(KEY_ACTIONS, List.of("unmatched_action")));

            PropertyInstruction instruction = new PropertyInstruction();
            instruction.setName("special_prop");
            instruction.setValueString("value");
            instruction.setScope(conversation);
            instruction.setOverride(true);

            SetOnActions setOnAction = new SetOnActions();
            setOnAction.setActions(List.of("specific_action")); // does NOT match "unmatched_action"
            setOnAction.setSetProperties(List.of(instruction));

            when(propertySetter.getSetOnActionsList()).thenReturn(List.of(setOnAction));
            when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>());
            when(previousConversationSteps.size()).thenReturn(0);
            when(memoryItemConverter.convert(conversationMemory)).thenReturn(new HashMap<>());
            when(templateEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            propertySetterTask.execute(conversationMemory, propertySetter);

            assertFalse(conversationProperties.containsKey("special_prop"));
        }
    }

    // ==================== Secret Scope Tests ====================

    @Nested
    @DisplayName("Secret scope auto-vaulting")
    class SecretScopeTests {

        @Test
        @DisplayName("should auto-vault secret scope properties and store vault reference")
        void execute_secretScope_autoVaults() throws Exception {
            IPropertySetter propertySetter = mock(IPropertySetter.class);

            when(currentStep.getLatestData(KEY_EXPRESSIONS_PARSED)).thenReturn(null);
            when(currentStep.getAllData(KEY_CONTEXT)).thenReturn(null);
            when(currentStep.getLatestData(KEY_ACTIONS)).thenReturn(new Data<>(KEY_ACTIONS, List.of("store_key")));
            when(conversationMemory.getAgentId()).thenReturn("agent-123");

            PropertyInstruction instruction = new PropertyInstruction();
            instruction.setName("userApiKey");
            instruction.setValueString("sk-secret-key-123");
            instruction.setScope(secret);
            instruction.setOverride(true);

            SetOnActions setOnAction = new SetOnActions();
            setOnAction.setActions(List.of("store_key"));
            setOnAction.setSetProperties(List.of(instruction));

            when(propertySetter.getSetOnActionsList()).thenReturn(List.of(setOnAction));
            when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>());
            when(previousConversationSteps.size()).thenReturn(0);
            when(memoryItemConverter.convert(conversationMemory)).thenReturn(new HashMap<>());
            when(templateEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            propertySetterTask.execute(conversationMemory, propertySetter);

            // Verify vault store was called
            verify(secretProvider).store(any(), eq("sk-secret-key-123"), anyString(), any());

            // Verify the property scope is stored as 'conversation' (not 'secret')
            // because the vault reference replaces the plaintext
            assertTrue(conversationProperties.containsKey("userApiKey"));
            assertEquals(conversation, conversationProperties.get("userApiKey").getScope());
        }
    }

    // ==================== Context Property Tests ====================

    @Nested
    @DisplayName("Context-based property extraction")
    class ContextPropertyTests {

        @Test
        @DisplayName("should extract context properties when context type is 'expressions'")
        void execute_contextExpressions_extractsProperties() throws LifecycleException {
            IPropertySetter propertySetter = mock(IPropertySetter.class);

            // No expressions from parser, no actions
            when(currentStep.getLatestData(KEY_EXPRESSIONS_PARSED)).thenReturn(null);
            when(currentStep.getLatestData(KEY_ACTIONS)).thenReturn(null);

            // Context with properties type
            Context context = new Context();
            context.setType(Context.ContextType.expressions);
            context.setValue("property(contextProp(contextVal))");

            when(currentStep.getAllData(KEY_CONTEXT)).thenReturn(
                    Collections.singletonList(new Data<>(KEY_CONTEXT + ":properties", context)));

            when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>(
                    List.of(new Property("contextProp", "contextVal", conversation))));
            when(previousConversationSteps.size()).thenReturn(0);

            when(dataFactory.createData(eq("properties:extracted"), any(List.class), eq(true)))
                    .thenReturn(new Data<>("properties:extracted", List.of()));

            propertySetterTask.execute(conversationMemory, propertySetter);

            // Verify expressionProvider was called for the context value
            verify(expressionProvider).parseExpressions("property(contextProp(contextVal))");
        }

        @Test
        @DisplayName("should ignore non-properties context types")
        void execute_nonPropertiesContext_ignoresContext() throws LifecycleException {
            IPropertySetter propertySetter = mock(IPropertySetter.class);

            when(currentStep.getLatestData(KEY_EXPRESSIONS_PARSED)).thenReturn(null);
            when(currentStep.getLatestData(KEY_ACTIONS)).thenReturn(null);

            // Context with key "context:userInfo" (NOT "context:properties")
            Context context = new Context();
            context.setType(Context.ContextType.expressions);
            context.setValue("property(ignored(val))");

            when(currentStep.getAllData(KEY_CONTEXT)).thenReturn(
                    Collections.singletonList(new Data<>(KEY_CONTEXT + ":userInfo", context)));

            when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>());
            when(previousConversationSteps.size()).thenReturn(0);

            propertySetterTask.execute(conversationMemory, propertySetter);

            // Should NOT parse expressions from non-properties context
            verify(expressionProvider, never()).parseExpressions("property(ignored(val))");
        }
    }

    // ==================== ExtensionDescriptor Tests ====================

    @Nested
    @DisplayName("ExtensionDescriptor")
    class ExtensionDescriptorTests {

        @Test
        @DisplayName("should return correct descriptor with ID and display name")
        void testExtensionDescriptor() {
            var descriptor = propertySetterTask.getExtensionDescriptor();

            assertNotNull(descriptor);
            assertEquals("ai.labs.property", descriptor.getType());
            assertEquals("Property Extraction", descriptor.getDisplayName());
        }
    }
}