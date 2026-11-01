/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.agents.model.AgentConfiguration.RatingConfig.Scale;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.engine.api.IRestConversationRatingAdmin;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.ratings.IConversationRatingStore;
import ai.labs.eddi.engine.ratings.model.ConversationRating;
import ai.labs.eddi.engine.ratings.model.RatingFilter;
import ai.labs.eddi.engine.ratings.model.RatingGroupBy;
import ai.labs.eddi.engine.ratings.model.RatingSort;
import ai.labs.eddi.engine.ratings.model.ReviewStatus;
import ai.labs.eddi.engine.ratings.rest.RestConversationRatingAdmin;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rating tools run the REST resource's own scoping against the MCP caller:
 * these tests wire the real {@link RestConversationRatingAdmin} with mocked
 * guards and store, so every refusal below is the one the Manager gets too —
 * not a re-implementation that could drift from it.
 */
@DisplayName("McpRatingTools")
class McpRatingToolsTest {

    private static final String AGENT = "6aba70011a25f8be7920e055";
    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private ResourceAccessGuard accessGuard;
    private ConversationAccessGuard conversationGuard;
    private IConversationRatingStore store;
    private SecurityIdentity identity;
    private McpRatingTools tools;

    @BeforeEach
    void setUp() {
        accessGuard = mock(ResourceAccessGuard.class);
        conversationGuard = mock(ConversationAccessGuard.class);
        store = mock(IConversationRatingStore.class);
        identity = identityWith(Set.of("eddi-editor"));
        when(conversationGuard.callerActor(anyString())).thenReturn("alice");
        var rest = new RestConversationRatingAdmin(accessGuard, conversationGuard, mock(IConversationDescriptorStore.class), store);
        tools = new McpRatingTools(rest, new JsonSerialization(mapper), identity, true);
    }

    private static SecurityIdentity identityWith(Set<String> roles) {
        SecurityIdentity id = mock(SecurityIdentity.class);
        when(id.isAnonymous()).thenReturn(false);
        when(id.hasRole(anyString())).thenAnswer(inv -> roles.contains(inv.getArgument(0, String.class)));
        when(id.getRoles()).thenReturn(roles);
        return id;
    }

    private void asEditor() {
        when(accessGuard.seesEverything()).thenReturn(false);
        when(accessGuard.isAdmin()).thenReturn(false);
    }

    private void asAdmin() {
        when(accessGuard.seesEverything()).thenReturn(true);
        when(accessGuard.isAdmin()).thenReturn(true);
    }

    private static ConversationRating rating(String agentId) {
        return new ConversationRating("r-1", "conv-1", -1, agentId, 3, "production", "chat-ui", "rater-7", Scale.FIVE_STAR, 2, 0.25,
                List.of("Too slow"), "It took forever", NOW, NOW);
    }

    private JsonNode json(String text) throws Exception {
        return mapper.readTree(text);
    }

    private String list(String agentId) {
        return tools.listConversationRatings(agentId, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null);
    }

    @Nested
    @DisplayName("role tier")
    class RoleTier {

        @Test
        @DisplayName("eddi-viewer and eddi-user are refused by every rating tool")
        void viewerAndUserRefused() {
            for (String role : List.of("eddi-viewer", "eddi-user")) {
                var refused = new McpRatingTools(mock(IRestConversationRatingAdmin.class),
                        new JsonSerialization(mapper), identityWith(Set.of(role)), true);
                assertThrows(ForbiddenException.class, () -> refused.listConversationRatings(AGENT, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null), role);
                assertThrows(ForbiddenException.class, () -> refused.summarizeConversationRatings(AGENT, null, null, null, null, null, null,
                        null, null, null, null, null, null), role);
                assertThrows(ForbiddenException.class, () -> refused.countRatingsNeedingAttention(AGENT), role);
                assertThrows(ForbiddenException.class, () -> refused.reviewConversationRating("r-1", "REVIEWED", null), role);
            }
        }
    }

    @Nested
    @DisplayName("scoping — the REST rule, applied to the MCP caller")
    class Scoping {

        @Test
        @DisplayName("an editor who names no agent gets AGENT_ID_REQUIRED and the store is never asked")
        void editorMustNameAgent() throws Exception {
            asEditor();

            assertEquals("AGENT_ID_REQUIRED", json(list(null)).get("errorCode").asText());
            assertEquals("AGENT_ID_REQUIRED", json(tools.summarizeConversationRatings(" ", null, null, null, null, null, null, null, null,
                    null, null, null, null)).get("errorCode").asText());
            assertEquals("AGENT_ID_REQUIRED", json(tools.countRatingsNeedingAttention(null)).get("errorCode").asText());
            verify(store, never()).query(any(), any(), anyInt(), anyInt());
            verify(store, never()).summarize(any(), any());
            verify(store, never()).count(any());
        }

        @Test
        @DisplayName("an editor without EDIT on the agent is refused with FORBIDDEN before the store is asked")
        void editorWithoutEdit() throws Exception {
            asEditor();
            doThrow(new ForbiddenException("Access denied: you do not have edit access to this agent")).when(accessGuard)
                    .requireAccess(AGENT, AccessLevel.EDIT, "agent");

            JsonNode listed = json(list(AGENT));
            assertEquals("FORBIDDEN", listed.get("errorCode").asText());
            assertTrue(listed.get("error").asText().contains("edit access"));
            assertEquals("FORBIDDEN", json(tools.countRatingsNeedingAttention(AGENT)).get("errorCode").asText());
            verify(store, never()).query(any(), any(), anyInt(), anyInt());
            verify(store, never()).count(any());
        }

        @Test
        @DisplayName("an editor with EDIT lists the agent's ratings, without the rater's userId")
        void editorListsWithoutUserId() throws Exception {
            asEditor();
            when(store.query(any(), eq(RatingSort.LOWEST), eq(0), eq(5))).thenReturn(List.of(rating(AGENT)));
            when(store.count(any())).thenReturn(1L);

            JsonNode page = json(tools.listConversationRatings(AGENT, 3, "production", null, null, null, null, null, null, null, "NEW",
                    true, "conv-1", "lowest", 0, 5));

            verify(accessGuard).requireAccess(AGENT, AccessLevel.EDIT, "agent");
            ArgumentCaptor<RatingFilter> filter = ArgumentCaptor.forClass(RatingFilter.class);
            verify(store).query(filter.capture(), eq(RatingSort.LOWEST), eq(0), eq(5));
            assertEquals(AGENT, filter.getValue().agentId());
            assertEquals(3, filter.getValue().agentVersion());
            assertEquals(ReviewStatus.NEW, filter.getValue().reviewStatus());
            assertEquals(Boolean.TRUE, filter.getValue().needsAttention());
            assertEquals("conv-1", filter.getValue().conversationId());
            assertEquals(1, page.get("total").asLong());
            JsonNode row = page.get("ratings").get(0);
            assertEquals("It took forever", row.get("comment").asText());
            assertTrue(row.get("userId") == null || row.get("userId").isNull(), "an editor must not see the rater's userId");
        }

        @Test
        @DisplayName("an administrator may list across agents, and sees the rater's userId")
        void adminListsEverything() throws Exception {
            asAdmin();
            when(store.query(any(), any(), anyInt(), anyInt())).thenReturn(List.of(rating(AGENT)));

            JsonNode page = json(list(null));

            ArgumentCaptor<RatingFilter> filter = ArgumentCaptor.forClass(RatingFilter.class);
            verify(store).query(filter.capture(), eq(RatingSort.NEWEST), eq(0), eq(20));
            assertNull(filter.getValue().agentId());
            assertEquals("rater-7", page.get("ratings").get(0).get("userId").asText());
        }

        @Test
        @DisplayName("summaries pass the grouping through; counts ask for exactly the triage queue")
        void summarizeAndCount() throws Exception {
            asEditor();
            when(store.summarize(any(), any())).thenReturn(List.of());
            when(store.count(any())).thenReturn(3L);

            JsonNode summary = json(tools.summarizeConversationRatings(AGENT, null, null, "2026-10-01", null, null, null, null, null, null,
                    null, null, "agentVersion"));
            assertEquals("agentVersion", summary.get("groupBy").asText());
            verify(store).summarize(any(RatingFilter.class), eq(RatingGroupBy.AGENT_VERSION));

            assertEquals(3, json(tools.countRatingsNeedingAttention(AGENT)).get("count").asLong());
            verify(store).count(RatingFilter.needingAttention(AGENT));
        }
    }

    @Nested
    @DisplayName("review_conversation_rating")
    class Review {

        @Test
        @DisplayName("the reviewer is the caller's principal — the tool has no reviewer argument to spoof")
        void reviewerFromIdentity() throws Exception {
            asEditor();
            ConversationRating stored = rating(AGENT);
            when(store.readById("r-1")).thenReturn(Optional.of(stored));
            when(store.updateReview(eq("r-1"), eq(NOW), eq(ReviewStatus.REVIEWED), eq("alice"), any(), eq("Known latency issue")))
                    .thenAnswer(inv -> Optional.of(stored));

            String result = tools.reviewConversationRating("r-1", "reviewed", "Known latency issue");

            assertFalse(result.startsWith("{\"error\""), result);
            verify(accessGuard).requireAccess(AGENT, AccessLevel.EDIT, "agent");
            verify(store).updateReview(eq("r-1"), eq(NOW), eq(ReviewStatus.REVIEWED), eq("alice"), any(), eq("Known latency issue"));
        }

        @Test
        @DisplayName("without EDIT on the rating's own agent nothing is written")
        void reviewNeedsEditOnTheRatingsAgent() throws Exception {
            asEditor();
            when(store.readById("r-1")).thenReturn(Optional.of(rating(AGENT)));
            doThrow(new ForbiddenException("Access denied")).when(accessGuard).requireAccess(AGENT, AccessLevel.EDIT, "agent");

            assertEquals("FORBIDDEN", json(tools.reviewConversationRating("r-1", "REVIEWED", null)).get("errorCode").asText());
            verify(store, never()).updateReview(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("a rating that names no agent is reviewable by administrators only")
        void agentlessRatingIsAdminOnly() throws Exception {
            asEditor();
            when(store.readById("r-1")).thenReturn(Optional.of(rating(null)));

            assertEquals("NOT_PERMITTED", json(tools.reviewConversationRating("r-1", "REVIEWED", null)).get("errorCode").asText());
            verify(store, never()).updateReview(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("a re-rating meanwhile is RATING_CHANGED; an unknown id is RATING_NOT_FOUND")
        void conflictAndNotFound() throws Exception {
            asAdmin();
            when(store.readById("r-1")).thenReturn(Optional.of(rating(AGENT)));
            when(store.updateReview(any(), any(), any(), any(), any(), any())).thenReturn(Optional.empty());
            when(store.readById("missing")).thenReturn(Optional.empty());

            assertEquals("RATING_CHANGED", json(tools.reviewConversationRating("r-1", "NEW", null)).get("errorCode").asText());
            assertEquals("RATING_NOT_FOUND", json(tools.reviewConversationRating("missing", "NEW", null)).get("errorCode").asText());
        }

        @Test
        @DisplayName("a call without ratingId, or without anything to change, is refused before any read")
        void badRequests() throws Exception {
            assertEquals("BAD_REQUEST", json(tools.reviewConversationRating(" ", "REVIEWED", null)).get("errorCode").asText());
            assertEquals("BAD_REQUEST", json(tools.reviewConversationRating("r-1", null, null)).get("errorCode").asText());
            verify(store, never()).readById(any());
        }
    }

    @Nested
    @DisplayName("errors")
    class Errors {

        @Test
        @DisplayName("an unreadable parameter keeps the REST code INVALID_PARAMETER")
        void invalidParameter() throws Exception {
            asAdmin();
            JsonNode error = json(tools.listConversationRatings(null, null, null, null, null, null, null, null, null, null, null, null, null,
                    "sideways", null, null));
            assertEquals("INVALID_PARAMETER", error.get("errorCode").asText());
            assertTrue(error.get("error").asText().contains("sort"));
        }

        @Test
        @DisplayName("a store failure is INTERNAL with a reference, never the store's own text")
        void storeFailureDoesNotLeak() throws Exception {
            asAdmin();
            when(store.count(any())).thenThrow(new IllegalStateException("mongodb://db-7.internal:27017 timed out"));

            JsonNode error = json(tools.countRatingsNeedingAttention(null));
            assertEquals("INTERNAL", error.get("errorCode").asText());
            assertFalse(error.get("error").asText().contains("db-7"));
        }
    }
}
