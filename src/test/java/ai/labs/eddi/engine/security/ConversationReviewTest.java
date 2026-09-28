/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.engine.api.IRestAgentProfile;
import ai.labs.eddi.engine.internal.RestAgentProfile;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.WorkspaceSettings;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.security.Principal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Conversation review lets an agent's maintainers read other people's
 * conversations with it. It is a decision about other people's data, so the
 * rules are strict and each is pinned: opt-in per agent version, only for
 * current maintainers, read-only, and announced.
 */
@DisplayName("conversation review")
class ConversationReviewTest {

    private static final String AGENT = "6aba70011a25f8be7920e055";
    private static final URI AGENT_V1 = URI.create("eddi://ai.labs.agent/agentstore/agents/" + AGENT + "?version=1");
    private static final URI AGENT_V2 = URI.create("eddi://ai.labs.agent/agentstore/agents/" + AGENT + "?version=2");

    private IAgentStore agentStore;
    private ResourceAccessGuard guard;
    private SecurityIdentity identity;
    private ConversationReviewPolicy policy;

    @BeforeEach
    void setUp() throws Exception {
        agentStore = mock(IAgentStore.class);
        guard = mock(ResourceAccessGuard.class);
        identity = mock(SecurityIdentity.class);
        when(guard.settings()).thenReturn(new WorkspaceSettings(true, true, "groups", WorkspaceSettings.LEGACY_SHARED, Optional.empty()));
        // v1 predates review; v2 turned it on.
        when(agentStore.read(AGENT, 1)).thenReturn(new AgentConfiguration());
        when(agentStore.read(AGENT, 2)).thenReturn(reviewing(null));
        policy = new ConversationReviewPolicy(agentStore, guard, identity);
    }

    private static AgentConfiguration reviewing(String notice) {
        var configuration = new AgentConfiguration();
        var review = new AgentConfiguration.ConversationReview();
        review.setEnabled(true);
        review.setNotice(notice);
        configuration.setConversationReview(review);
        return configuration;
    }

    @Nested
    @DisplayName("the policy")
    class Policy {

        @Test
        @DisplayName("admits a maintainer to a conversation on a version that had review enabled")
        void maintainerOnEnabledVersion() {
            assertTrue(policy.mayReview(AGENT_V2));
        }

        @Test
        @DisplayName("never exposes a conversation from before review was switched on")
        void notBeforeOptIn() {
            assertFalse(policy.mayReview(AGENT_V1), "nobody chatting on v1 was told their conversation could be read");
        }

        @Test
        @DisplayName("ends with the caller's edit access")
        void notWithoutEdit() {
            doThrow(new ForbiddenException("no")).when(guard).requireAccess(AGENT, AccessLevel.EDIT, "agent");

            assertFalse(policy.mayReview(AGENT_V2));
        }

        @Test
        @DisplayName("without enforcement, only people who may author agents count as maintainers")
        void withoutEnforcementEditorsOnly() {
            when(guard.settings()).thenReturn(new WorkspaceSettings(false, true, "groups", WorkspaceSettings.LEGACY_SHARED, Optional.empty()));

            assertFalse(policy.mayReview(AGENT_V2), "a chat-only eddi-user must not read other people's conversations");
            when(identity.hasRole("eddi-editor")).thenReturn(true);
            assertTrue(policy.mayReview(AGENT_V2));
        }

        @Test
        @DisplayName("gives a default notice, or the designer's own")
        void notice() throws Exception {
            assertEquals(Optional.of(ConversationReviewPolicy.DEFAULT_NOTICE), policy.noticeFor(AGENT, 2));
            assertEquals(Optional.empty(), policy.noticeFor(AGENT, 1));

            when(agentStore.read(AGENT, 3)).thenReturn(reviewing("  The support team reads these to improve answers.  "));
            assertEquals(Optional.of("The support team reads these to improve answers."), policy.noticeFor(AGENT, 3));
        }

        @Test
        @DisplayName("reads each version's setting once")
        void cachesPerVersion() throws Exception {
            policy.mayReview(AGENT_V2);
            policy.mayReview(AGENT_V2);

            verify(agentStore, times(1)).read(AGENT, 2);
        }
    }

    @Nested
    @DisplayName("the conversation guard")
    class Guard {

        private IConversationDescriptorStore descriptors;
        private ConversationAccessGuard sut;

        @BeforeEach
        @SuppressWarnings("unchecked")
        void setUp() throws Exception {
            var callerIdentity = mock(SecurityIdentity.class);
            Principal principal = () -> "bob";
            when(callerIdentity.getPrincipal()).thenReturn(principal);
            descriptors = mock(IConversationDescriptorStore.class);
            sut = new ConversationAccessGuard(callerIdentity, new OwnershipValidator(true), descriptors);
            Instance<ConversationReviewPolicy> instance = mock(Instance.class);
            when(instance.isResolvable()).thenReturn(true);
            when(instance.get()).thenReturn(policy);
            sut.reviewPolicyInstance = instance;
        }

        private void conversation(URI agentResource) throws Exception {
            var descriptor = new ConversationDescriptor();
            descriptor.setUserId("alice");
            descriptor.setAgentResource(agentResource);
            when(descriptors.readDescriptor(anyString(), eq(0))).thenReturn(descriptor);
        }

        @Test
        @DisplayName("lets a maintainer read somebody else's conversation on a reviewed version")
        void readerAdmitted() throws Exception {
            conversation(AGENT_V2);

            assertEquals("alice", sut.requireConversationReader("c1"));
        }

        @Test
        @DisplayName("keeps every other conversation private")
        void othersStayPrivate() throws Exception {
            conversation(AGENT_V1);

            assertThrows(ForbiddenException.class, () -> sut.requireConversationReader("c1"));
        }

        @Test
        @DisplayName("never extends to acting on the conversation")
        void readOnly() throws Exception {
            conversation(AGENT_V2);

            assertThrows(ForbiddenException.class, () -> sut.requireConversationOwner("c1"),
                    "owner-only paths — continue, delete — must stay owner-only");
        }
    }

    @Nested
    @DisplayName("the chat profile")
    class Profile {

        @Test
        @DisplayName("carries the notice for the deployed version, so the chat can show it before the first message")
        void carriesNotice() throws Exception {
            var descriptorStore = mock(IDocumentDescriptorStore.class);
            var descriptor = new DocumentDescriptor();
            descriptor.setName("Support Agent");
            when(descriptorStore.readCurrentDescriptor(AGENT)).thenReturn(descriptor);
            var factory = mock(IAgentFactory.class);
            var deployed = mock(IAgent.class);
            when(deployed.getAgentVersion()).thenReturn(2);
            when(factory.getLatestReadyAgent(Deployment.Environment.production, AGENT)).thenReturn(deployed);

            IRestAgentProfile.AgentProfile profile = new RestAgentProfile(guard, descriptorStore, factory, policy).readProfile(AGENT,
                    Deployment.Environment.production);

            assertEquals("Support Agent", profile.name());
            assertEquals(ConversationReviewPolicy.DEFAULT_NOTICE, profile.reviewNotice());
            verify(guard).requireAgentUseAccess(AGENT);
        }

        @Test
        @DisplayName("says nothing when the deployed version does not review")
        void noNotice() throws Exception {
            var factory = mock(IAgentFactory.class);
            var deployed = mock(IAgent.class);
            when(deployed.getAgentVersion()).thenReturn(1);
            when(factory.getLatestReadyAgent(Deployment.Environment.production, AGENT)).thenReturn(deployed);

            var profile = new RestAgentProfile(guard, mock(IDocumentDescriptorStore.class), factory, policy).readProfile(AGENT, null);

            assertNull(profile.reviewNotice());
        }
    }
}
