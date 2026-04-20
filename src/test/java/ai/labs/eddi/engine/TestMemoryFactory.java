package ai.labs.eddi.engine;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IConversationStepStack;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.MemoryKey;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationProperties;
import ai.labs.eddi.engine.memory.model.Data;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Shared utility for building pre-populated {@link IConversationMemory} mocks.
 * <p>
 * Most {@code ILifecycleTask} tests need a realistic memory mock with a
 * writable step, conversation properties, previous steps stack, and
 * conversation outputs. This factory builds one in 1-2 lines.
 *
 * <pre>{@code
 * var ctx = TestMemoryFactory.create();
 * myTask.execute(ctx.memory(), component);
 * }</pre>
 */
public final class TestMemoryFactory {

    private TestMemoryFactory() {
    }

    /**
     * A fully-wired memory mock context. Access individual pieces via record
     * accessors.
     */
    public record MemoryContext(
            IConversationMemory memory,
            IWritableConversationStep currentStep,
            IConversationStepStack previousSteps,
            ConversationProperties conversationProperties,
            List<ConversationOutput> conversationOutputs) {
    }

    /**
     * Create a default memory context with empty current step and no previous
     * steps.
     */
    public static MemoryContext create() {
        return create("conv-test", "agent-test", 1, "user-test");
    }

    /**
     * Create a memory context with specified identifiers.
     */
    public static MemoryContext create(String conversationId, String agentId, int agentVersion, String userId) {
        IConversationMemory memory = mock(IConversationMemory.class);
        IWritableConversationStep currentStep = mock(IWritableConversationStep.class);
        IConversationStepStack previousSteps = mock(IConversationStepStack.class);
        ConversationProperties props = new ConversationProperties(memory);
        List<ConversationOutput> outputs = new ArrayList<>();
        outputs.add(new ConversationOutput()); // current step output

        lenient().when(memory.getCurrentStep()).thenReturn(currentStep);
        lenient().when(memory.getPreviousSteps()).thenReturn(previousSteps);
        lenient().when(memory.getConversationProperties()).thenReturn(props);
        lenient().when(memory.getConversationOutputs()).thenReturn(outputs);
        lenient().when(memory.getConversationId()).thenReturn(conversationId);
        lenient().when(memory.getAgentId()).thenReturn(agentId);
        lenient().when(memory.getAgentVersion()).thenReturn(agentVersion);
        lenient().when(memory.getUserId()).thenReturn(userId);
        lenient().when(previousSteps.size()).thenReturn(0);

        // Default: currentStep returns null for all data queries (no data stored)
        // Stub BOTH the String and MemoryKey overloads — tasks use MemoryKey typed API
        lenient().when(currentStep.getLatestData(anyString())).thenReturn(null);
        lenient().when(currentStep.getLatestData(any(MemoryKey.class))).thenReturn(null);
        lenient().when(currentStep.getData(anyString())).thenReturn(null);
        lenient().when(currentStep.getData(any(MemoryKey.class))).thenReturn(null);
        lenient().when(currentStep.get(any(MemoryKey.class))).thenReturn(null);
        lenient().when(currentStep.getAllData(anyString())).thenReturn(List.of());
        lenient().when(currentStep.getConversationOutput()).thenReturn(outputs.getFirst());

        return new MemoryContext(memory, currentStep, previousSteps, props, outputs);
    }

    /**
     * Create a memory context with user input pre-populated in the current step.
     * Stubs both String and MemoryKey overloads for "input".
     */
    public static MemoryContext createWithInput(String input) {
        var ctx = create();
        Data<String> inputData = new Data<>("input", input);
        inputData.setPublic(true);
        // Stub both overloads: getLatestData(String) and getLatestData(MemoryKey)
        when(ctx.currentStep().getLatestData(eq("input"))).thenAnswer(inv -> inputData);
        when(ctx.currentStep().getLatestData(any(MemoryKey.class))).thenAnswer(invocation -> {
            MemoryKey<?> key = invocation.getArgument(0);
            if ("input".equals(key.key())) {
                return inputData;
            }
            return null;
        });
        return ctx;
    }

    /**
     * Create a memory context with actions pre-populated in the current step. Stubs
     * both String and MemoryKey overloads for "actions".
     */
    public static MemoryContext createWithActions(List<String> actions) {
        var ctx = create();
        Data<List<String>> actionsData = new Data<>("actions", actions);
        actionsData.setPublic(true);
        when(ctx.currentStep().getLatestData(eq("actions"))).thenAnswer(inv -> actionsData);
        when(ctx.currentStep().getLatestData(any(MemoryKey.class))).thenAnswer(invocation -> {
            MemoryKey<?> key = invocation.getArgument(0);
            if ("actions".equals(key.key())) {
                return actionsData;
            }
            return null;
        });
        return ctx;
    }

    /**
     * Create a memory context with parsed expressions in the current step.
     */
    public static MemoryContext createWithExpressions(String expressions) {
        var ctx = create();
        when(ctx.currentStep().getLatestData(eq("expressions:parsed")))
                .thenReturn(new Data<>("expressions:parsed", expressions));
        return ctx;
    }

    /**
     * Add a previous step with actions to the memory context.
     */
    public static void addPreviousStepWithActions(MemoryContext ctx, List<String> actions) {
        IConversationMemory.IConversationStep prevStep = mock(IConversationMemory.IConversationStep.class);
        when(prevStep.getLatestData(eq("actions"))).thenReturn(new Data<>("actions", actions));
        when(ctx.previousSteps().size()).thenReturn(1);
        when(ctx.previousSteps().get(eq(0))).thenReturn(prevStep);
    }
}
