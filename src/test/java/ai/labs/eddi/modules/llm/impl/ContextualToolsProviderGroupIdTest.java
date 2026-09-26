/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.engine.internal.groups.LiveDiscussionRegistry;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.model.Context;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Regression tests for {@code ContextualToolsProvider.resolveGroupIds}.
 * <p>
 * {@code groupId} reaches a member conversation as a <b>context</b> value; no
 * code path anywhere writes it as a conversation <em>property</em>. Reading
 * only the property (as this did before) left {@code groupIds} empty on every
 * group member turn, so {@code UserMemoryTool} silently ran self-scoped and
 * could neither recall nor write group-visible memories — while conversation
 * init, which reads the context correctly, loaded them. Raised by Copilot on PR
 * #626.
 *
 * @author tests
 */
class ContextualToolsProviderGroupIdTest {

    private static final String CONTEXT_KEY = "context:groupId";

    @SuppressWarnings("unchecked")
    private IData<Object> contextData(Object value) {
        IData<Object> data = mock(IData.class);
        when(data.getResult()).thenReturn(value);
        return data;
    }

    private IConversationMemory memoryWithCurrentStepContext(Object value) {
        var memory = mock(IConversationMemory.class);
        var currentStep = mock(IConversationMemory.IWritableConversationStep.class);
        // Build the inner mock BEFORE opening the when(...) — creating a mock inside
        // an in-progress stubbing is what Mockito reports as UnfinishedStubbing.
        var data = contextData(value);
        when(currentStep.getData(CONTEXT_KEY)).thenReturn(data);
        when(memory.getCurrentStep()).thenReturn(currentStep);
        return memory;
    }

    @Test
    void groupIdFromCurrentStepContext_isResolved() {
        var memory = memoryWithCurrentStepContext(new Context(Context.ContextType.string, "group-42"));

        assertEquals(List.of("group-42"), ContextualToolsProvider.resolveGroupIds(memory),
                "the context value MemberTurnExecutor injects must reach UserMemoryTool's group scope");
    }

    @Test
    void groupIdStoredAsBareString_isResolved() {
        // Defensive: a persisted/rehydrated step may hand back the raw value rather
        // than a Context wrapper.
        var memory = memoryWithCurrentStepContext("group-7");

        assertEquals(List.of("group-7"), ContextualToolsProvider.resolveGroupIds(memory));
    }

    private static final String DISCUSSION_KEY = "context:groupConversationId";

    /** A conversation whose only group context sits on an EARLIER step. */
    private IConversationMemory memoryWithEarlierStep(String groupId, String discussionId) {
        var memory = mock(IConversationMemory.class);
        when(memory.getConversationId()).thenReturn("conv-1");
        var currentStep = mock(IConversationMemory.IWritableConversationStep.class);
        when(currentStep.getData(CONTEXT_KEY)).thenReturn(null);
        when(memory.getCurrentStep()).thenReturn(currentStep);
        // Built before stubbing: creating a mock inside thenReturn(...) is unfinished
        // stubbing.
        List<IData<Object>> groupIds = List.of(contextData(new Context(Context.ContextType.string, groupId)));
        List<IData<Object>> discussions = discussionId == null
                ? Arrays.asList((IData<Object>) null)
                : List.of(contextData(new Context(Context.ContextType.string, discussionId)));
        var allSteps = mock(IConversationMemory.IConversationStepStack.class);
        when(allSteps.getExactDataPerStep(CONTEXT_KEY)).thenReturn(groupIds);
        when(allSteps.getExactDataPerStep(DISCUSSION_KEY)).thenReturn(discussions);
        when(memory.getAllSteps()).thenReturn(allSteps);
        return memory;
    }

    private static LiveDiscussionRegistry registryWithLiveMember(String discussionId, String groupId, String memberConversationId) {
        var registry = new LiveDiscussionRegistry();
        var gc = new GroupConversation();
        gc.setId(discussionId);
        gc.setGroupId(groupId);
        gc.getMemberConversationIds().put("agent-1", memberConversationId);
        registry.register(gc);
        return registry;
    }

    @Test
    void groupIdFromAnEarlierStep_isResolvedForAVerifiedMember() {
        // A resumed turn re-enters without the original context map, so the value
        // only exists on an earlier step — trusted because the running discussion
        // confirms this conversation is its member, in that group.
        var memory = memoryWithEarlierStep("group-earlier", "gc-1");

        assertEquals(List.of("group-earlier"),
                ContextualToolsProvider.resolveGroupIds(memory, registryWithLiveMember("gc-1", "group-earlier", "conv-1")));
    }

    @Test
    void forgedPreFixGroupId_onAnEarlierStep_isIgnored() {
        // Review #4: a client that forged context:groupId before the strip existed
        // left it on an earlier step, with no discussion behind it.
        var memory = memoryWithEarlierStep("another-teams-group", null);

        assertTrue(ContextualToolsProvider.resolveGroupIds(memory, new LiveDiscussionRegistry()).isEmpty());
    }

    @Test
    void earlierGroupId_notMatchingTheDiscussionsGroup_isIgnored() {
        var memory = memoryWithEarlierStep("another-teams-group", "gc-1");

        assertTrue(ContextualToolsProvider.resolveGroupIds(memory, registryWithLiveMember("gc-1", "my-group", "conv-1")).isEmpty());
    }

    @Test
    void earlierGroupId_forANonMemberConversation_isIgnored() {
        var memory = memoryWithEarlierStep("my-group", "gc-1");

        assertTrue(ContextualToolsProvider.resolveGroupIds(memory, registryWithLiveMember("gc-1", "my-group", "someone-else")).isEmpty());
    }

    @Test
    void earlierGroupId_withoutARegistry_isIgnored() {
        assertTrue(ContextualToolsProvider.resolveGroupIds(memoryWithEarlierStep("my-group", "gc-1")).isEmpty());
    }

    @Test
    void groupIdProperty_isNotTrusted() {
        // C3c: a client can set conversation properties (a properties* context entry
        // of type expressions), so a groupId property must never scope group memory.
        var memory = mock(IConversationMemory.class);
        when(memory.getCurrentStep()).thenReturn(null);
        when(memory.getAllSteps()).thenReturn(null);
        var props = mock(IConversationMemory.IConversationProperties.class);
        when(props.get("groupId")).thenReturn(new Property("groupId", "another-teams-group", Property.Scope.conversation));
        when(memory.getConversationProperties()).thenReturn(props);

        assertTrue(ContextualToolsProvider.resolveGroupIds(memory).isEmpty(),
                "only the engine-written context:groupId may name the group");
    }

    @Test
    void noGroupContextAnywhere_yieldsEmpty() {
        var memory = mock(IConversationMemory.class);
        when(memory.getCurrentStep()).thenReturn(null);
        when(memory.getAllSteps()).thenReturn(null);
        when(memory.getConversationProperties()).thenReturn(null);

        assertTrue(ContextualToolsProvider.resolveGroupIds(memory).isEmpty(),
                "an ordinary non-group conversation must stay self-scoped");
    }

    @Test
    void blankGroupId_isTreatedAsAbsent() {
        var memory = memoryWithCurrentStepContext(new Context(Context.ContextType.string, "   "));
        when(memory.getAllSteps()).thenReturn(null);
        when(memory.getConversationProperties()).thenReturn(null);

        assertTrue(ContextualToolsProvider.resolveGroupIds(memory).isEmpty(),
                "a blank id must not become a real group scope");
    }
}
