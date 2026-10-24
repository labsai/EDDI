/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.ratings.rest;

import ai.labs.eddi.configs.agents.model.AgentConfiguration.RatingConfig.Scale;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.engine.api.IRestConversationRatingAdmin.RatingPage;
import ai.labs.eddi.engine.api.IRestConversationRatingAdmin.RatingRow;
import ai.labs.eddi.engine.api.IRestConversationRatingAdmin.ReviewRequest;
import ai.labs.eddi.engine.api.RatingQueryParams;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.ratings.IConversationRatingStore;
import ai.labs.eddi.engine.ratings.model.ConversationRating;
import ai.labs.eddi.engine.ratings.model.RatingFilter;
import ai.labs.eddi.engine.ratings.model.RatingGroupBy;
import ai.labs.eddi.engine.ratings.model.RatingKind;
import ai.labs.eddi.engine.ratings.model.RatingSort;
import ai.labs.eddi.engine.ratings.model.RatingSummary;
import ai.labs.eddi.engine.ratings.model.ReviewStatus;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.quarkus.security.ForbiddenException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Scoping, redaction and parameter handling of the maintainers'
 * {@code /ratings} endpoints, and the triage loop.
 */
@DisplayName("/ratings (maintainer endpoints)")
class RestConversationRatingAdminTest {

    private static final String AGENT = "6aba70011a25f8be7920e055";
    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");

    private ResourceAccessGuard accessGuard;
    private ConversationAccessGuard conversationGuard;
    private IConversationDescriptorStore descriptorStore;
    private IConversationRatingStore store;
    private RestConversationRatingAdmin rest;

    @BeforeEach
    void setUp() {
        accessGuard = mock(ResourceAccessGuard.class);
        conversationGuard = mock(ConversationAccessGuard.class);
        descriptorStore = mock(IConversationDescriptorStore.class);
        store = mock(IConversationRatingStore.class);
        when(conversationGuard.callerActor(anyString())).thenReturn("maintainer");
        rest = new RestConversationRatingAdmin(accessGuard, conversationGuard, descriptorStore, store, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** An editor under enforced workspaces: sees only what they hold EDIT on. */
    private void asEditor() {
        when(accessGuard.seesEverything()).thenReturn(false);
        when(accessGuard.isAdmin()).thenReturn(false);
    }

    private void asAdmin() {
        when(accessGuard.seesEverything()).thenReturn(true);
        when(accessGuard.isAdmin()).thenReturn(true);
    }

    private static ConversationRating rating(String conversationId, String comment) {
        return new ConversationRating("r-" + conversationId, conversationId, -1, AGENT, 1, "production", "chat-ui", "alice", Scale.FIVE_STAR,
                2, 0.25, List.of("Too slow"), comment, NOW, NOW);
    }

    @SuppressWarnings("unchecked")
    private static void assertError(int status, String code, Executable call) {
        WebApplicationException e = assertThrows(WebApplicationException.class, call);
        assertEquals(status, e.getResponse().getStatus());
        assertEquals(code, ((Map<String, String>) e.getResponse().getEntity()).get("error"));
    }

    @Nested
    @DisplayName("scoping")
    class Scoping {

        @Test
        @DisplayName("an editor must name an agent → 400 AGENT_ID_REQUIRED, on every endpoint")
        void editorNeedsAgent() {
            asEditor();
            RatingQueryParams none = new RatingQueryParams();

            assertError(400, RatingErrors.AGENT_ID_REQUIRED, () -> rest.listRatings(none, null, 0, 20));
            assertError(400, RatingErrors.AGENT_ID_REQUIRED, () -> rest.summarizeRatings(none, null));
            assertError(400, RatingErrors.AGENT_ID_REQUIRED, () -> rest.exportRatings(none));
            assertError(400, RatingErrors.AGENT_ID_REQUIRED, () -> rest.markReviewed(none));
            assertError(400, RatingErrors.AGENT_ID_REQUIRED, () -> rest.attentionCount(" "));
            verify(store, never()).query(any(), any(), anyInt(), anyInt());
            verify(store, never()).markReviewed(any(), any(), any());
        }

        @Test
        @DisplayName("an editor without EDIT on the agent is refused before the store is asked")
        void editorWithoutEdit() {
            asEditor();
            doThrow(new ForbiddenException("no")).when(accessGuard).requireAccess(AGENT, AccessLevel.EDIT, "agent");
            RatingQueryParams query = RatingQueryParams.ofAgent(AGENT);

            assertThrows(ForbiddenException.class, () -> rest.listRatings(query, null, 0, 20));
            assertThrows(ForbiddenException.class, () -> rest.summarizeRatings(query, "day"));
            assertThrows(ForbiddenException.class, () -> rest.exportRatings(query));
            assertThrows(ForbiddenException.class, () -> rest.markReviewed(query));
            assertThrows(ForbiddenException.class, () -> rest.attentionCount(AGENT));
            verify(store, never()).query(any(), any(), anyInt(), anyInt());
            verify(store, never()).summarize(any(), any());
            verify(store, never()).count(any());
            verify(store, never()).markReviewed(any(), any(), any());
        }

        @Test
        @DisplayName("an editor with EDIT sees the agent's ratings, without any user reference")
        void editorSeesNoUser() {
            asEditor();
            when(store.query(any(), any(), anyInt(), anyInt())).thenReturn(List.of(rating("c1", "slow")));

            RatingPage page = rest.listRatings(RatingQueryParams.ofAgent(AGENT), null, 0, 20);

            verify(accessGuard).requireAccess(AGENT, AccessLevel.EDIT, "agent");
            assertNull(page.ratings().get(0).userId(), "editors never see who rated");
            assertEquals("slow", page.ratings().get(0).comment());
            ArgumentCaptor<RatingFilter> filter = ArgumentCaptor.forClass(RatingFilter.class);
            verify(store).query(filter.capture(), eq(RatingSort.NEWEST), eq(0), eq(20));
            assertEquals(AGENT, filter.getValue().agentId());
        }

        @Test
        @DisplayName("an administrator may omit the agent and sees the user")
        void adminSeesAll() {
            asAdmin();
            when(store.query(any(), any(), anyInt(), anyInt())).thenReturn(List.of(rating("c1", null)));

            RatingPage page = rest.listRatings(new RatingQueryParams(), "lowest", 0, 500);

            assertEquals("alice", page.ratings().get(0).userId());
            assertEquals(RestConversationRatingAdmin.MAX_PAGE_SIZE, page.limit(), "page size is capped");
            verify(store).query(any(), eq(RatingSort.LOWEST), eq(0), eq(RestConversationRatingAdmin.MAX_PAGE_SIZE));
            verify(accessGuard, never()).requireAccess(anyString(), any(), anyString());
        }

        @Test
        @DisplayName("without workspace enforcement an editor sees every agent, still without user ids")
        void notEnforcing() {
            when(accessGuard.seesEverything()).thenReturn(true);
            when(accessGuard.isAdmin()).thenReturn(false);
            when(store.query(any(), any(), anyInt(), anyInt())).thenReturn(List.of(rating("c1", null)));

            RatingPage page = rest.listRatings(new RatingQueryParams(), null, 0, 20);

            assertNull(page.ratings().get(0).userId());
        }
    }

    @Nested
    @DisplayName("the transcript link")
    class Transcript {

        @Test
        @DisplayName("is offered only where the conversation-read rule admits the caller")
        void followsTheReadRule() throws Exception {
            asEditor();
            ConversationDescriptor reviewable = new ConversationDescriptor();
            reviewable.setUserId("alice");
            reviewable.setAgentResource(URI.create("eddi://ai.labs.agent/agentstore/agents/" + AGENT + "?version=1"));
            ConversationDescriptor privateOne = new ConversationDescriptor();
            privateOne.setUserId("bob");
            privateOne.setAgentResource(URI.create("eddi://ai.labs.agent/agentstore/agents/" + AGENT + "?version=2"));
            when(descriptorStore.readDescriptor("c1", 0)).thenReturn(reviewable);
            when(descriptorStore.readDescriptor("c2", 0)).thenReturn(privateOne);
            when(descriptorStore.readDescriptor("purged", 0)).thenThrow(new ResourceNotFoundException("gone"));
            when(conversationGuard.canReview(reviewable.getAgentResource())).thenReturn(true);
            when(store.query(any(), any(), anyInt(), anyInt()))
                    .thenReturn(List.of(rating("c1", null), rating("c2", null), rating("purged", null)));

            List<RatingRow> rows = rest.listRatings(RatingQueryParams.ofAgent(AGENT), null, 0, 20).ratings();

            assertTrue(rows.get(0).canOpenTranscript(), "review is enabled on that version");
            assertFalse(rows.get(1).canOpenTranscript(), "no review, not the owner: the comment is visible, the dialogue is not");
            assertFalse(rows.get(2).canOpenTranscript(), "the conversation is gone");
        }
    }

    @Nested
    @DisplayName("parameters")
    class Parameters {

        @Test
        @DisplayName("bad values → 400 INVALID_PARAMETER")
        void invalid() {
            asAdmin();
            RatingQueryParams badKind = new RatingQueryParams();
            badKind.kind = "everything";
            RatingQueryParams badScore = new RatingQueryParams();
            badScore.minScore = 4.0;
            RatingQueryParams badDate = new RatingQueryParams();
            badDate.from = "yesterday";
            RatingQueryParams badStatus = new RatingQueryParams();
            badStatus.reviewStatus = "DONE";

            assertError(400, RatingErrors.INVALID_PARAMETER, () -> rest.listRatings(badKind, null, 0, 20));
            assertError(400, RatingErrors.INVALID_PARAMETER, () -> rest.listRatings(badScore, null, 0, 20));
            assertError(400, RatingErrors.INVALID_PARAMETER, () -> rest.listRatings(badDate, null, 0, 20));
            assertError(400, RatingErrors.INVALID_PARAMETER, () -> rest.listRatings(badStatus, null, 0, 20));
            assertError(400, RatingErrors.INVALID_PARAMETER, () -> rest.listRatings(new RatingQueryParams(), "random", 0, 20));
            assertError(400, RatingErrors.INVALID_PARAMETER, () -> rest.listRatings(new RatingQueryParams(), null, -1, 20));
            assertError(400, RatingErrors.INVALID_PARAMETER, () -> rest.summarizeRatings(new RatingQueryParams(), "hour"));
        }

        @Test
        @DisplayName("every filter reaches the store; a date 'to' includes that whole day")
        void filterMapping() {
            asAdmin();
            RatingQueryParams q = RatingQueryParams.ofAgent(AGENT);
            q.agentVersion = 3;
            q.environment = "production";
            q.from = "2026-10-01";
            q.to = "2026-10-07";
            q.minScore = 0.0;
            q.maxScore = 0.5;
            q.kind = "message";
            q.tag = "Too slow";
            q.hasComment = true;
            q.reviewStatus = "new";
            q.needsAttention = true;

            RatingFilter filter = rest.filter(q);

            assertEquals(new RatingFilter(AGENT, 3, "production", Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-08T00:00:00Z"),
                    0.0, 0.5, RatingKind.MESSAGE, "Too slow", true, ReviewStatus.NEW, true), filter);
            assertEquals(Instant.parse("2026-10-03T10:00:00Z"), RestConversationRatingAdmin.instant("to", "2026-10-03T10:00:00Z", true));

            q.conversationId = " conv-9 ";
            assertEquals("conv-9", rest.filter(q).conversationId(), "the conversation view's filter reaches the store");
        }

        @Test
        @DisplayName("the summary hands the grouping to the store")
        void summary() {
            asAdmin();
            when(store.summarize(any(), eq(RatingGroupBy.AGENT_VERSION))).thenReturn(List.of(RatingSummary.empty("1")));

            var summaries = rest.summarizeRatings(RatingQueryParams.ofAgent(AGENT), "agentVersion");

            assertEquals("agentVersion", summaries.groupBy());
            assertEquals("1", summaries.buckets().get(0).bucket());
        }
    }

    @Nested
    @DisplayName("export")
    class Export {

        @SuppressWarnings("unchecked")
        private String body(Response response) throws Exception {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ((StreamingOutput) response.getEntity()).write(out);
            return out.toString(StandardCharsets.UTF_8);
        }

        @Test
        @DisplayName("streams escaped CSV; user ids only for administrators")
        void csv() throws Exception {
            asEditor();
            doAnswer(call -> {
                Consumer<ConversationRating> sink = call.getArgument(2);
                sink.accept(rating("c1", "=HYPERLINK(\"http://evil\")"));
                return null;
            }).when(store).stream(any(), eq(RestConversationRatingAdmin.MAX_EXPORT_ROWS), any());

            Response response = rest.exportRatings(RatingQueryParams.ofAgent(AGENT));
            String csv = body(response);

            assertTrue(response.getHeaderString("Content-Disposition").contains("attachment"));
            assertFalse(csv.contains("userId"), "editors get no user column");
            assertFalse(csv.contains("alice"));
            assertTrue(csv.contains("\"'=HYPERLINK(\"\"http://evil\"\")\""), csv);
            assertNull(response.getHeaderString(RestConversationRatingAdmin.TRUNCATED_HEADER));
        }

        @Test
        @DisplayName("administrators get the user column, and a cut-off export says so")
        void adminCsv() throws Exception {
            asAdmin();
            when(store.count(any())).thenReturn((long) RestConversationRatingAdmin.MAX_EXPORT_ROWS + 1);
            doAnswer(call -> {
                Consumer<ConversationRating> sink = call.getArgument(2);
                sink.accept(rating("c1", null));
                return null;
            }).when(store).stream(any(), anyInt(), any());

            Response response = rest.exportRatings(new RatingQueryParams());
            String csv = body(response);

            assertTrue(csv.startsWith("id,conversationId"));
            assertTrue(csv.lines().findFirst().orElseThrow().endsWith(",userId"));
            assertTrue(csv.contains("\"alice\""));
            assertEquals("true", response.getHeaderString(RestConversationRatingAdmin.TRUNCATED_HEADER));
        }
    }

    @Nested
    @DisplayName("triage")
    class Triage {

        private final ConversationRating fresh = rating("c1", "kept looping");

        @BeforeEach
        void stored() {
            when(store.readById(fresh.id())).thenReturn(Optional.of(fresh));
            when(store.updateReview(anyString(), any(), any(), any(), any(), any())).thenAnswer(call -> {
                ConversationRating r = fresh;
                return Optional.of(new ConversationRating(r.id(), r.conversationId(), r.stepIndex(), r.agentId(), r.agentVersion(),
                        r.environment(), r.channel(), r.userId(), r.scale(), r.score(), r.normalizedScore(), r.tags(), r.comment(),
                        r.createdAt(), r.updatedAt(), call.getArgument(2), call.getArgument(3), call.getArgument(4), call.getArgument(5)));
            });
        }

        @Test
        @DisplayName("marking reviewed records the caller as reviewer, never the body, conditional on the rating seen")
        void markReviewed() {
            asEditor();

            RatingRow row = rest.reviewRating(fresh.id(), new ReviewRequest("REVIEWED", "  called the customer "));

            verify(accessGuard).requireAccess(AGENT, AccessLevel.EDIT, "agent");
            verify(store).updateReview(fresh.id(), fresh.updatedAt(), ReviewStatus.REVIEWED, "maintainer", NOW, "called the customer");
            assertEquals(ReviewStatus.REVIEWED, row.reviewStatus());
            assertEquals("maintainer", row.reviewedBy());
            assertNull(row.userId());
        }

        @Test
        @DisplayName("a note alone keeps the status; reopening clears the reviewer; an empty note removes it")
        void noteAndReopen() {
            asAdmin();

            rest.reviewRating(fresh.id(), new ReviewRequest(null, "watch this"));
            verify(store).updateReview(fresh.id(), fresh.updatedAt(), ReviewStatus.NEW, null, null, "watch this");

            rest.reviewRating(fresh.id(), new ReviewRequest("new", ""));
            verify(store).updateReview(fresh.id(), fresh.updatedAt(), ReviewStatus.NEW, null, null, null);
        }

        @Test
        @DisplayName("an editor without EDIT on the rating's agent cannot triage it")
        void needsEditOnTheRatingsAgent() {
            asEditor();
            doThrow(new ForbiddenException("no")).when(accessGuard).requireAccess(AGENT, AccessLevel.EDIT, "agent");

            assertThrows(ForbiddenException.class, () -> rest.reviewRating(fresh.id(), new ReviewRequest("REVIEWED", null)));
            verify(store, never()).updateReview(anyString(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("unknown id → 404, bad status or long note → 400, re-rated meanwhile → 409")
        void errors() {
            asAdmin();
            assertError(404, RatingErrors.RATING_NOT_FOUND, () -> rest.reviewRating("nope", new ReviewRequest("REVIEWED", null)));
            assertError(400, RatingErrors.INVALID_PARAMETER, () -> rest.reviewRating(fresh.id(), new ReviewRequest("DONE", null)));
            assertError(400, RatingErrors.INVALID_PARAMETER, () -> rest.reviewRating(fresh.id(), null));
            assertError(400, RatingErrors.NOTE_TOO_LONG,
                    () -> rest.reviewRating(fresh.id(), new ReviewRequest(null, "n".repeat(ConversationRating.MAX_REVIEW_NOTE_LENGTH + 1))));

            when(store.updateReview(anyString(), any(), any(), any(), any(), any())).thenReturn(Optional.empty());
            assertError(409, RatingErrors.RATING_CHANGED, () -> rest.reviewRating(fresh.id(), new ReviewRequest("REVIEWED", null)));
        }

        @Test
        @DisplayName("bulk review marks the scoped filter, reviewer from the principal")
        void bulk() {
            asEditor();
            RatingQueryParams q = RatingQueryParams.ofAgent(AGENT);
            q.needsAttention = true;
            when(store.markReviewed(any(), eq("maintainer"), eq(NOW))).thenReturn(7L);

            assertEquals(7, rest.markReviewed(q).reviewed());
            ArgumentCaptor<RatingFilter> filter = ArgumentCaptor.forClass(RatingFilter.class);
            verify(store).markReviewed(filter.capture(), eq("maintainer"), eq(NOW));
            assertEquals(AGENT, filter.getValue().agentId());
            assertTrue(filter.getValue().needsAttention());
        }

        @Test
        @DisplayName("the attention count is the needs-attention count within scope")
        void attentionCount() {
            asEditor();
            when(store.count(RatingFilter.needingAttention(AGENT))).thenReturn(3L);

            assertEquals(3, rest.attentionCount(AGENT).count());

            asAdmin();
            when(store.count(RatingFilter.needingAttention(null))).thenReturn(9L);
            assertEquals(9, rest.attentionCount(null).count());
        }
    }
}
