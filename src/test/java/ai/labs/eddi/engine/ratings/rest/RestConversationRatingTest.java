/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.ratings.rest;

import ai.labs.eddi.configs.agents.model.AgentConfiguration.CommentSettings;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.ConversationRatingSettings;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.MessageRatingSettings;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.RatingConfig;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.RatingConfig.Scale;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.engine.api.IRestConversationRating.ConversationRatings;
import ai.labs.eddi.engine.api.IRestConversationRating.RatingRequest;
import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.gdpr.ProcessingRestrictedException;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.ratings.ConversationRatingMetrics;
import ai.labs.eddi.engine.ratings.ConversationRatingPolicy;
import ai.labs.eddi.engine.ratings.IConversationRatingStore;
import ai.labs.eddi.engine.ratings.model.ConversationRating;
import ai.labs.eddi.engine.ratings.model.ReviewStatus;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import ai.labs.eddi.engine.security.OwnershipValidator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every check the owner's rating endpoints make, and the stable error code each
 * failure carries.
 */
@DisplayName("rating a conversation (owner endpoints)")
class RestConversationRatingTest {

    private static final String CONVERSATION = "conv-1";
    private static final String OWNER = "alice";
    private static final String AGENT = "6aba70011a25f8be7920e055";
    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");
    /**
     * Built from its code point: the formatter would decode an escape into a raw
     * NUL.
     */
    private static final String NUL = String.valueOf((char) 0);

    private ConversationAccessGuard guard;
    private OwnershipValidator ownership;
    private SecurityIdentity identity;
    private IConversationDescriptorStore descriptorStore;
    private IConversationMemoryStore memoryStore;
    private ConversationRatingPolicy policy;
    private IConversationRatingStore store;
    private GdprComplianceService gdpr;
    private SimpleMeterRegistry registry;
    private RestConversationRating rest;

    private ConversationDescriptor descriptor;
    private ConversationMemorySnapshot snapshot;
    private RatingConfig config;

    @BeforeEach
    void setUp() throws Exception {
        guard = mock(ConversationAccessGuard.class);
        ownership = mock(OwnershipValidator.class);
        identity = mock(SecurityIdentity.class);
        descriptorStore = mock(IConversationDescriptorStore.class);
        memoryStore = mock(IConversationMemoryStore.class);
        policy = mock(ConversationRatingPolicy.class);
        store = mock(IConversationRatingStore.class);
        gdpr = mock(GdprComplianceService.class);
        registry = new SimpleMeterRegistry();

        when(guard.requireConversationOwner(CONVERSATION)).thenReturn(OWNER);
        when(ownership.isOwner(identity, OWNER)).thenReturn(true);

        descriptor = new ConversationDescriptor();
        descriptor.setUserId(OWNER);
        descriptor.setAgentResource(URI.create("eddi://ai.labs.agent/agentstore/agents/" + AGENT + "?version=1"));
        descriptor.setLastModifiedOn(Date.from(NOW.minus(Duration.ofHours(1))));
        when(descriptorStore.readDescriptor(CONVERSATION, 0)).thenReturn(descriptor);

        snapshot = new ConversationMemorySnapshot();
        snapshot.setAgentId(AGENT);
        snapshot.setAgentVersion(2);
        snapshot.setEnvironment(Deployment.Environment.production);
        List<ConversationOutput> outputs = new ArrayList<>();
        outputs.add(output(List.of("Hello!")));
        outputs.add(output(null));
        outputs.add(output(List.of("Here is your answer")));
        snapshot.setConversationOutputs(outputs);
        when(memoryStore.loadConversationMemorySnapshot(CONVERSATION)).thenReturn(snapshot);

        config = new RatingConfig();
        config.setEnabled(true);
        config.setScale(Scale.FIVE_STAR);
        MessageRatingSettings messages = new MessageRatingSettings();
        messages.setEnabled(true);
        config.setMessageRating(messages);
        CommentSettings comment = new CommentSettings();
        comment.setEnabled(true);
        comment.setMaxLength(20);
        config.setComment(comment);
        config.setReasonTags(List.of("Helpful", "Too slow"));
        when(policy.ratingConfigOf(AGENT, 2)).thenReturn(Optional.of(config));

        when(store.upsert(any())).thenAnswer(call -> {
            ConversationRating r = call.getArgument(0);
            return new ConversationRating("id-1", r.conversationId(), r.stepIndex(), r.agentId(), r.agentVersion(), r.environment(),
                    r.channel(), r.userId(), r.scale(), r.score(), r.normalizedScore(), r.tags(), r.comment(), NOW, NOW, ReviewStatus.NEW,
                    null, null, "internal note");
        });

        rest = new RestConversationRating(guard, ownership, identity, descriptorStore, null, memoryStore, policy, store, gdpr,
                new ConversationRatingMetrics(registry), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ConversationOutput output(Object output) {
        ConversationOutput conversationOutput = new ConversationOutput();
        conversationOutput.put("input", "hi");
        if (output != null) {
            conversationOutput.put("output", output);
        }
        return conversationOutput;
    }

    private static RatingRequest score(int score) {
        return new RatingRequest(score, null, null, null);
    }

    /** Asserts the call fails with this HTTP status and stable error code. */
    @SuppressWarnings("unchecked")
    private static void assertError(int status, String code, Executable call) {
        WebApplicationException e = assertThrows(WebApplicationException.class, call);
        assertEquals(status, e.getResponse().getStatus());
        assertEquals(code, ((Map<String, String>) e.getResponse().getEntity()).get("error"));
    }

    private ConversationRating stored() {
        ArgumentCaptor<ConversationRating> rating = ArgumentCaptor.forClass(ConversationRating.class);
        verify(store).upsert(rating.capture());
        return rating.getValue();
    }

    @Nested
    @DisplayName("who may rate")
    class Who {

        @Test
        @DisplayName("the owner rates; the rating names the owner and the version the conversation runs on")
        void ownerRates() {
            ConversationRating response = rest.rateConversation(CONVERSATION, new RatingRequest(4, List.of("Helpful"), "  nice  ", "Chat-UI"));

            ConversationRating rating = stored();
            assertEquals(OWNER, rating.userId());
            assertEquals(AGENT, rating.agentId());
            assertEquals(2, rating.agentVersion(), "the memory's version, not the descriptor's possibly stale one");
            assertEquals(ConversationRating.CONVERSATION_STEP_INDEX, rating.stepIndex());
            assertEquals(Scale.FIVE_STAR, rating.scale());
            assertEquals(0.75, rating.normalizedScore());
            assertEquals("nice", rating.comment());
            assertEquals("chat-ui", rating.channel());
            assertEquals("production", rating.environment());
            assertEquals("id-1", response.id());
            assertNull(response.reviewNote(), "the maintainers' note is never shown to the rater");
            assertNull(response.reviewStatus());
        }

        @Test
        @DisplayName("an administrator cannot rate somebody else's conversation")
        void adminCannotForge() {
            when(ownership.isOwner(identity, OWNER)).thenReturn(false);

            assertError(403, RatingErrors.NOT_CONVERSATION_OWNER, () -> rest.rateConversation(CONVERSATION, score(5)));
            verify(store, never()).upsert(any());
        }

        @Test
        @DisplayName("somebody the guard refuses gets its refusal, and nothing is stored")
        void guardRefusal() {
            when(guard.requireConversationOwner(CONVERSATION)).thenThrow(new ForbiddenException("no"));

            assertThrows(ForbiddenException.class, () -> rest.rateConversation(CONVERSATION, score(5)));
            assertThrows(ForbiddenException.class, () -> rest.rateStep(CONVERSATION, 0, score(1)));
            assertThrows(ForbiddenException.class, () -> rest.readRatings(CONVERSATION));
            assertThrows(ForbiddenException.class, () -> rest.withdrawConversationRating(CONVERSATION));
            verify(store, never()).upsert(any());
            verify(store, never()).delete(anyString(), anyInt());
            verify(store, never()).readByConversation(anyString());
        }

        @Test
        @DisplayName("a conversation that records no owner cannot be rated")
        void noOwner() {
            when(guard.requireConversationOwner(CONVERSATION)).thenReturn(null);

            assertError(404, RatingErrors.CONVERSATION_NOT_FOUND, () -> rest.rateConversation(CONVERSATION, score(5)));
        }

        @Test
        @DisplayName("a restricted user (GDPR Art. 18) cannot add ratings")
        void processingRestricted() {
            when(gdpr.isProcessingRestricted(OWNER)).thenReturn(true);

            assertThrows(ProcessingRestrictedException.class, () -> rest.rateConversation(CONVERSATION, score(5)));
            verify(store, never()).upsert(any());
        }

        @Test
        @DisplayName("a soft-deleted conversation takes no new ratings")
        void archived() throws Exception {
            when(descriptorStore.readDescriptor(CONVERSATION, 0)).thenThrow(new ResourceNotFoundException("gone"));
            when(descriptorStore.readDescriptorWithHistory(CONVERSATION, 0)).thenReturn(descriptor);

            assertError(404, RatingErrors.CONVERSATION_NOT_FOUND, () -> rest.rateConversation(CONVERSATION, score(5)));
        }
    }

    @Nested
    @DisplayName("whether the agent version takes the rating")
    class Config {

        @Test
        @DisplayName("ratings off on the version → 409 RATING_DISABLED")
        void disabled() {
            when(policy.ratingConfigOf(AGENT, 2)).thenReturn(Optional.empty());

            assertError(409, RatingErrors.RATING_DISABLED, () -> rest.rateConversation(CONVERSATION, score(5)));
        }

        @Test
        @DisplayName("conversation rating switched off → 409 RATING_DISABLED, reply ratings still work")
        void conversationRatingOff() {
            ConversationRatingSettings settings = new ConversationRatingSettings();
            settings.setEnabled(false);
            config.setConversationRating(settings);

            assertError(409, RatingErrors.RATING_DISABLED, () -> rest.rateConversation(CONVERSATION, score(5)));
            rest.rateStep(CONVERSATION, 0, score(1));
        }

        @Test
        @DisplayName("reply ratings off → 409 RATING_DISABLED")
        void messageRatingOff() {
            config.setMessageRating(null);

            assertError(409, RatingErrors.RATING_DISABLED, () -> rest.rateStep(CONVERSATION, 0, score(1)));
        }

        @Test
        @DisplayName("after the window → 409 RATING_WINDOW_CLOSED; a window of 0 never closes")
        void window() {
            descriptor.setLastModifiedOn(Date.from(NOW.minus(Duration.ofHours(169))));

            assertError(409, RatingErrors.RATING_WINDOW_CLOSED, () -> rest.rateConversation(CONVERSATION, score(5)));

            config.setRatingWindowHours(0);
            rest.rateConversation(CONVERSATION, score(5));
        }
    }

    @Nested
    @DisplayName("what is rated")
    class What {

        @Test
        @DisplayName("a reply rating is always thumbs, on the step's agent output")
        void stepRating() {
            rest.rateStep(CONVERSATION, 2, score(0));

            ConversationRating rating = stored();
            assertEquals(2, rating.stepIndex());
            assertEquals(Scale.THUMBS, rating.scale());
            assertEquals(0.0, rating.normalizedScore());
            assertEquals("api", rating.channel(), "absent channel");
        }

        @Test
        @DisplayName("a step without agent output, beyond the end, or negative → 400 INVALID_STEP")
        void invalidStep() {
            assertError(400, RatingErrors.INVALID_STEP, () -> rest.rateStep(CONVERSATION, 1, score(1)));
            assertError(400, RatingErrors.INVALID_STEP, () -> rest.rateStep(CONVERSATION, 3, score(1)));
            assertError(400, RatingErrors.INVALID_STEP, () -> rest.rateStep(CONVERSATION, -1, score(1)));
            assertError(400, RatingErrors.INVALID_STEP, () -> rest.withdrawStepRating(CONVERSATION, -2));
        }

        @Test
        @DisplayName("a score off the scale → 400 INVALID_SCORE")
        void invalidScore() {
            assertError(400, RatingErrors.INVALID_SCORE, () -> rest.rateConversation(CONVERSATION, score(6)));
            assertError(400, RatingErrors.INVALID_SCORE, () -> rest.rateConversation(CONVERSATION, score(0)));
            assertError(400, RatingErrors.INVALID_SCORE, () -> rest.rateStep(CONVERSATION, 0, score(2)));
            assertError(400, RatingErrors.INVALID_SCORE, () -> rest.rateConversation(CONVERSATION, new RatingRequest(null, null, null, null)));
            assertError(400, RatingErrors.INVALID_SCORE, () -> rest.rateConversation(CONVERSATION, null));
        }

        @Test
        @DisplayName("tags outside the allow-list → 400 INVALID_TAG; duplicates collapse")
        void tags() {
            assertError(400, RatingErrors.INVALID_TAG,
                    () -> rest.rateConversation(CONVERSATION, new RatingRequest(2, List.of("Helpful", "<script>"), null, null)));

            rest.rateConversation(CONVERSATION, new RatingRequest(2, List.of("Too slow", "Too slow", "Helpful"), null, null));
            assertEquals(List.of("Too slow", "Helpful"), stored().tags());
        }

        @Test
        @DisplayName("a comment when comments are off → 400 COMMENT_NOT_ALLOWED; a blank one is simply absent")
        void commentOff() {
            config.setComment(null);

            assertError(400, RatingErrors.COMMENT_NOT_ALLOWED,
                    () -> rest.rateConversation(CONVERSATION, new RatingRequest(3, null, "hello", null)));

            rest.rateConversation(CONVERSATION, new RatingRequest(3, null, "   ", null));
            assertNull(stored().comment());
        }

        @Test
        @DisplayName("a comment over maxLength → 400 COMMENT_TOO_LONG; control characters are dropped")
        void commentLength() {
            assertError(400, RatingErrors.COMMENT_TOO_LONG,
                    () -> rest.rateConversation(CONVERSATION, new RatingRequest(3, null, "x".repeat(21), null)));

            rest.rateConversation(CONVERSATION, new RatingRequest(3, null, "a" + NUL + "b\nc", null));
            assertEquals("ab\nc", stored().comment());
        }

        @Test
        @DisplayName("an unknown channel is stored as other")
        void channel() {
            assertEquals("other", RestConversationRating.channelOf("carrier-pigeon"));
            assertEquals("mcp", RestConversationRating.channelOf(" MCP "));
            assertEquals("api", RestConversationRating.channelOf(null));
        }
    }

    @Nested
    @DisplayName("reading and withdrawing")
    class ReadAndWithdraw {

        @Test
        @DisplayName("GET splits the conversation rating from the reply ratings, without the triage")
        void read() {
            ConversationRating conversation = new ConversationRating("a", CONVERSATION, -1, AGENT, 1, null, "api", OWNER, Scale.THUMBS, 1,
                    1.0, List.of(), null, NOW, NOW, ReviewStatus.REVIEWED, "bob", NOW, "spoke to the team");
            ConversationRating step = new ConversationRating("b", CONVERSATION, 2, AGENT, 1, null, "api", OWNER, Scale.THUMBS, 0, 0.0,
                    List.of(), null, NOW, NOW);
            when(store.readByConversation(CONVERSATION)).thenReturn(List.of(conversation, step));

            ConversationRatings ratings = rest.readRatings(CONVERSATION);

            assertEquals("a", ratings.conversation().id());
            assertNull(ratings.conversation().reviewNote());
            assertNull(ratings.conversation().reviewedBy());
            assertEquals(List.of("b"), ratings.steps().stream().map(ConversationRating::id).toList());
        }

        @Test
        @DisplayName("GET carries the controls of the version the conversation runs on, not the latest one")
        void controlsFollowTheConversationsVersion() {
            // The snapshot pins version 2; version 3 (what a new conversation would get) is
            // off.
            when(policy.ratingConfigOf(AGENT, 3)).thenReturn(Optional.empty());

            ConversationRatings ratings = rest.readRatings(CONVERSATION);

            assertEquals("FIVE_STAR", ratings.controls().scale());
            verify(policy).ratingConfigOf(AGENT, 2);
        }

        @Test
        @DisplayName("GET carries no controls when the conversation's version takes no ratings")
        void noControlsWhenTheVersionIsOff() {
            when(policy.ratingConfigOf(AGENT, 2)).thenReturn(Optional.empty());

            assertNull(rest.readRatings(CONVERSATION).controls());
        }

        @Test
        @DisplayName("withdrawing deletes and answers 204, also when nothing was there")
        void withdraw() {
            when(store.delete(CONVERSATION, -1)).thenReturn(true);

            assertEquals(204, rest.withdrawConversationRating(CONVERSATION).getStatus());
            assertEquals(204, rest.withdrawStepRating(CONVERSATION, 4).getStatus());
            verify(store).delete(CONVERSATION, 4);
            assertEquals(1.0, registry.get("eddi.conversation.ratings.withdrawn").tag("kind", "conversation").counter().count());
        }

        @Test
        @DisplayName("a missing conversation is the guard's 404")
        void missing() {
            when(guard.requireConversationOwner(CONVERSATION)).thenThrow(new NotFoundException("Conversation not found"));

            assertThrows(NotFoundException.class, () -> rest.readRatings(CONVERSATION));
        }
    }

    @Test
    @DisplayName("each stored rating is counted, tagged by kind, scale, bucket and agent")
    void metrics() {
        rest.rateConversation(CONVERSATION, score(1));
        rest.rateStep(CONVERSATION, 0, score(1));

        assertEquals(1.0, registry.get("eddi.conversation.ratings").tag("kind", "conversation").tag("scale", "FIVE_STAR")
                .tag("bucket", "low").tag("agentId", AGENT).counter().count());
        assertEquals(1.0, registry.get("eddi.conversation.ratings").tag("kind", "message").tag("bucket", "high").counter().count());
        assertEquals(2, registry.get("eddi.conversation.ratings.normalized").tag("agentId", AGENT).summaries().stream()
                .mapToLong(s -> s.count()).sum());
    }
}
