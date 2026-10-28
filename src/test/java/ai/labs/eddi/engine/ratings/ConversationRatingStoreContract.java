/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.ratings;

import ai.labs.eddi.configs.agents.model.AgentConfiguration.RatingConfig.Scale;
import ai.labs.eddi.engine.ratings.model.ConversationRating;
import ai.labs.eddi.engine.ratings.model.RatingFilter;
import ai.labs.eddi.engine.ratings.model.RatingGroupBy;
import ai.labs.eddi.engine.ratings.model.RatingKind;
import ai.labs.eddi.engine.ratings.model.RatingSort;
import ai.labs.eddi.engine.ratings.model.RatingSummary;
import ai.labs.eddi.engine.ratings.model.ReviewStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link IConversationRatingStore} contract, run against a real database by
 * each implementation's {@code *IT}. Each implementing class must hand out an
 * empty store per test.
 */
public interface ConversationRatingStoreContract {

    /** A store over an empty collection/table. */
    IConversationRatingStore store();

    static ConversationRating rating(String conversationId, int step, String agentId, int version, Scale scale, int score, String user,
                                     List<String> tags, String comment, String channel) {
        return new ConversationRating(null, conversationId, step, agentId, version, "production", channel, user, scale, score,
                RatingScales.normalize(scale, score), tags, comment, null, null);
    }

    static ConversationRating stars(String conversationId, String agentId, int version, int score, String user) {
        return rating(conversationId, ConversationRating.CONVERSATION_STEP_INDEX, agentId, version, Scale.FIVE_STAR, score, user, List.of(),
                null, "chat-ui");
    }

    @Test
    default void upsertInsertsThenReplacesKeepingIdAndCreatedAt() throws Exception {
        IConversationRatingStore store = store();
        ConversationRating first = store.upsert(stars("c1", "a1", 1, 2, "u1"));
        assertNotNull(first.id());
        assertNotNull(first.createdAt());
        Thread.sleep(15);

        ConversationRating second = store.upsert(rating("c1", -1, "a1", 1, Scale.FIVE_STAR, 5, "u1", List.of("Helpful"), "great", "manager"));

        assertEquals(first.id(), second.id(), "a re-rating replaces, it does not add a row");
        assertEquals(first.createdAt().truncatedTo(ChronoUnit.MILLIS), second.createdAt().truncatedTo(ChronoUnit.MILLIS));
        assertTrue(second.updatedAt().isAfter(first.updatedAt()));
        assertEquals(5, second.score());
        assertEquals(1.0, second.normalizedScore());
        assertEquals(List.of("Helpful"), second.tags());
        assertEquals("great", second.comment());
        assertEquals("manager", second.channel());
        assertEquals(1, store.readByConversation("c1").size());
    }

    @Test
    default void conversationAndStepRatingsAreSeparateRows() {
        IConversationRatingStore store = store();
        store.upsert(stars("c1", "a1", 1, 4, "u1"));
        store.upsert(rating("c1", 3, "a1", 1, Scale.THUMBS, 0, "u1", List.of(), null, "chat-ui"));
        store.upsert(rating("c1", 1, "a1", 1, Scale.THUMBS, 1, "u1", List.of(), null, "chat-ui"));

        List<ConversationRating> all = store.readByConversation("c1");

        assertEquals(List.of(-1, 1, 3), all.stream().map(ConversationRating::stepIndex).toList());
    }

    @Test
    default void concurrentFirstRatingsLeaveOneRow() throws Exception {
        IConversationRatingStore store = store();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<ConversationRating>> tasks = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                int score = 1 + (i % 5);
                tasks.add(() -> store.upsert(stars("race", "a1", 1, score, "u1")));
            }
            for (Future<ConversationRating> future : pool.invokeAll(tasks)) {
                assertNotNull(future.get().id());
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, store.readByConversation("race").size());
    }

    @Test
    default void deleteRemovesOnlyTheAddressedRating() {
        IConversationRatingStore store = store();
        store.upsert(stars("c1", "a1", 1, 4, "u1"));
        store.upsert(rating("c1", 2, "a1", 1, Scale.THUMBS, 1, "u1", List.of(), null, "chat-ui"));

        assertTrue(store.delete("c1", 2));
        assertFalse(store.delete("c1", 2));
        assertEquals(1, store.readByConversation("c1").size());
    }

    @Test
    default void queryFiltersSortsAndPages() throws InterruptedException {
        IConversationRatingStore store = store();
        // Pauses keep createdAt distinct, so "newest first" has one right answer.
        store.upsert(stars("c1", "a1", 1, 1, "u1"));
        Thread.sleep(5);
        store.upsert(stars("c2", "a1", 2, 5, "u2"));
        Thread.sleep(5);
        store.upsert(rating("c3", -1, "a1", 2, Scale.FIVE_STAR, 3, "u3", List.of("Too slow"), "meh", "api"));
        Thread.sleep(5);
        store.upsert(rating("c3", 0, "a1", 2, Scale.THUMBS, 0, "u3", List.of(), null, "api"));
        store.upsert(stars("c4", "other-agent", 1, 2, "u4"));

        assertEquals(4, store.count(RatingFilter.ofAgent("a1")));
        assertEquals(3, store.count(new RatingFilter("a1", null, null, null, null, null, null, RatingKind.CONVERSATION, null, null)));
        assertEquals(1, store.count(new RatingFilter("a1", null, null, null, null, null, null, RatingKind.MESSAGE, null, null)));
        assertEquals(3, store.count(new RatingFilter("a1", 2, null, null, null, null, null, null, null, null)));
        assertEquals(1, store.count(new RatingFilter("a1", null, null, null, null, null, null, null, "Too slow", null)));
        assertEquals(1, store.count(new RatingFilter("a1", null, null, null, null, null, null, null, null, true)));
        assertEquals(3, store.count(new RatingFilter("a1", null, null, null, null, null, null, null, null, false)));
        assertEquals(2, store.count(new RatingFilter("a1", null, null, null, null, 0.4, 1.0, null, null, null)));
        assertEquals(0, store.count(new RatingFilter("a1", null, "test", null, null, null, null, null, null, null)));
        assertEquals(2, store.count(new RatingFilter("a1", null, null, null, null, null, null, null, null, null, null, null, "c3")));
        assertEquals(0, store.count(new RatingFilter("other-agent", null, null, null, null, null, null, null, null, null, null, null, "c3")),
                "the conversation filter narrows the agent scope, never widens it");

        List<ConversationRating> lowest = store.query(RatingFilter.ofAgent("a1"), RatingSort.LOWEST, 0, 2);
        assertEquals(2, lowest.size());
        assertEquals(0.0, lowest.get(0).normalizedScore());
        assertEquals(0.0, lowest.get(1).normalizedScore());

        List<ConversationRating> newest = store.query(RatingFilter.ofAgent("a1"), RatingSort.NEWEST, 0, 10);
        assertEquals("c3", newest.get(0).conversationId(), "newest first");
        assertEquals("c1", newest.get(newest.size() - 1).conversationId());
        assertEquals(1, store.query(RatingFilter.ofAgent("a1"), RatingSort.NEWEST, 3, 10).size());
    }

    @Test
    default void dateRangeIsFromInclusiveToExclusive() {
        IConversationRatingStore store = store();
        ConversationRating stored = store.upsert(stars("c1", "a1", 1, 4, "u1"));
        Instant at = stored.createdAt();

        assertEquals(1, store.count(new RatingFilter("a1", null, null, at.minusSeconds(1), at.plusSeconds(1), null, null, null, null, null)));
        assertEquals(0, store.count(new RatingFilter("a1", null, null, at.plusSeconds(1), null, null, null, null, null, null)));
        assertEquals(0, store.count(new RatingFilter("a1", null, null, null, at.minusSeconds(1), null, null, null, null, null)));
    }

    @Test
    default void summaryIsComputedPerBucket() {
        IConversationRatingStore store = store();
        store.upsert(rating("c1", -1, "a1", 1, Scale.FIVE_STAR, 5, "u1", List.of("Helpful", "Friendly"), "great", "chat-ui"));
        store.upsert(rating("c2", -1, "a1", 1, Scale.FIVE_STAR, 4, "u2", List.of("Helpful"), null, "chat-ui"));
        store.upsert(rating("c3", -1, "a1", 2, Scale.FIVE_STAR, 1, "u3", List.of("Too slow"), "slow", "api"));
        store.upsert(rating("c3", 0, "a1", 2, Scale.THUMBS, 1, "u3", List.of(), null, "api"));
        store.upsert(rating("c3", 2, "a1", 2, Scale.THUMBS, 0, "u3", List.of("Too slow"), null, "api"));
        store.upsert(stars("cx", "other-agent", 10, 1, "u9"));

        List<RatingSummary> all = store.summarize(RatingFilter.ofAgent("a1"), RatingGroupBy.NONE);
        assertEquals(1, all.size());
        RatingSummary total = all.get(0);
        assertEquals(RatingGroupBy.ALL_BUCKET, total.bucket());
        assertEquals(5, total.count());
        assertEquals((1.0 + 0.75 + 0.0 + 1.0 + 0.0) / 5, total.avgNormalized(), 1e-9);
        assertEquals(1, total.distribution().thumbsUp());
        assertEquals(1, total.distribution().thumbsDown());
        assertEquals(1L, total.distribution().stars().get(5));
        assertEquals(1L, total.distribution().stars().get(4));
        assertEquals(1L, total.distribution().stars().get(1));
        assertEquals(2.0 / 3, total.csat(), 1e-9);
        assertEquals(0.5, total.thumbsUpRate(), 1e-9);
        assertEquals(2, total.commentCount());
        assertEquals("Helpful", total.topTags().get(0).tag());
        assertEquals(2, total.topTags().get(0).count());
        assertEquals("Too slow", total.topTags().get(1).tag());
        assertEquals(2, total.topTags().get(1).count());

        List<RatingSummary> byVersion = store.summarize(RatingFilter.ofAgent("a1"), RatingGroupBy.AGENT_VERSION);
        assertEquals(List.of("1", "2"), byVersion.stream().map(RatingSummary::bucket).toList());
        assertEquals(2, byVersion.get(0).count());
        assertEquals(3, byVersion.get(1).count());

        List<RatingSummary> byChannel = store.summarize(RatingFilter.ofAgent("a1"), RatingGroupBy.CHANNEL);
        assertEquals(List.of("api", "chat-ui"), byChannel.stream().map(RatingSummary::bucket).toList());

        List<RatingSummary> byDay = store.summarize(RatingFilter.ofAgent("a1"), RatingGroupBy.DAY);
        assertEquals(1, byDay.size());
        assertTrue(byDay.get(0).bucket().matches("\\d{4}-\\d{2}-\\d{2}"), byDay.get(0).bucket());

        List<RatingSummary> byWeek = store.summarize(RatingFilter.ofAgent("a1"), RatingGroupBy.WEEK);
        assertEquals(1, byWeek.size());
        assertTrue(byWeek.get(0).bucket().matches("\\d{4}-W\\d{2}"), byWeek.get(0).bucket());
    }

    @Test
    default void summaryOfNothingIsOneEmptyBucket() {
        List<RatingSummary> none = store().summarize(RatingFilter.ofAgent("nobody"), RatingGroupBy.NONE);
        assertEquals(1, none.size());
        assertEquals(0, none.get(0).count());
        assertNull(none.get(0).avgNormalized());
        assertNull(none.get(0).csat());
        assertNull(none.get(0).thumbsUpRate());
        assertTrue(store().summarize(RatingFilter.ofAgent("nobody"), RatingGroupBy.DAY).isEmpty());
    }

    @Test
    default void streamHonoursTheCap() {
        IConversationRatingStore store = store();
        for (int i = 0; i < 5; i++) {
            store.upsert(stars("s" + i, "a1", 1, 3, "u1"));
        }
        List<ConversationRating> seen = new ArrayList<>();
        store.stream(RatingFilter.ofAgent("a1"), 3, seen::add);
        assertEquals(3, seen.size());
    }

    @Test
    default void gdprReadAndEraseByUser() {
        IConversationRatingStore store = store();
        store.upsert(stars("c1", "a1", 1, 4, "erase-me"));
        store.upsert(rating("c1", 0, "a1", 1, Scale.THUMBS, 1, "erase-me", List.of(), null, "chat-ui"));
        store.upsert(stars("c2", "a1", 1, 4, "keep-me"));

        assertEquals(2, store.readByUserId("erase-me", 100).size());
        assertEquals(1, store.readByUserId("erase-me", 1).size());
        assertEquals(2, store.deleteByUserId("erase-me"));
        assertTrue(store.readByUserId("erase-me", 100).isEmpty());
        assertEquals(1, store.readByUserId("keep-me", 100).size());
        assertEquals(0, store.deleteByUserId(null));
    }

    @Test
    default void reviewIsSetAndAReRatingResetsIt() throws Exception {
        IConversationRatingStore store = store();
        ConversationRating stored = store.upsert(stars("c1", "a1", 1, 2, "u1"));
        assertEquals(ReviewStatus.NEW, stored.reviewStatus());
        Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        ConversationRating reviewed = store.updateReview(stored.id(), stored.updatedAt(), ReviewStatus.REVIEWED, "maintainer", at, "called back")
                .orElseThrow();
        assertEquals(ReviewStatus.REVIEWED, reviewed.reviewStatus());
        assertEquals("maintainer", reviewed.reviewedBy());
        assertEquals(at, reviewed.reviewedAt());
        assertEquals("called back", reviewed.reviewNote());
        assertEquals(stored.updatedAt(), reviewed.updatedAt(), "triage does not move the rater's updatedAt");
        assertEquals(reviewed, store.readById(stored.id()).orElseThrow());

        Thread.sleep(5);
        ConversationRating reRated = store.upsert(stars("c1", "a1", 1, 1, "u1"));
        assertEquals(ReviewStatus.NEW, reRated.reviewStatus(), "a re-rating is a new opinion");
        assertNull(reRated.reviewedBy());
        assertNull(reRated.reviewedAt());
        assertNull(reRated.reviewNote());
    }

    @Test
    default void reviewOfAReRatedRatingIsRefused() throws Exception {
        IConversationRatingStore store = store();
        ConversationRating seen = store.upsert(stars("c1", "a1", 1, 2, "u1"));
        Thread.sleep(5);
        store.upsert(stars("c1", "a1", 1, 5, "u1"));

        Optional<ConversationRating> result = store.updateReview(seen.id(), seen.updatedAt(), ReviewStatus.REVIEWED, "m", Instant.now(), null);

        assertTrue(result.isEmpty(), "the maintainer has not seen the new rating");
        assertEquals(ReviewStatus.NEW, store.readById(seen.id()).orElseThrow().reviewStatus());
    }

    @Test
    default void unknownOrMalformedIdsReadAsEmpty() {
        assertTrue(store().readById("000000000000000000000000").isEmpty());
        assertTrue(store().readById("not-an-id").isEmpty());
        assertTrue(store().readById(null).isEmpty());
        assertTrue(store().updateReview("not-an-id", Instant.now(), ReviewStatus.REVIEWED, "m", Instant.now(), null).isEmpty());
    }

    @Test
    default void attentionAndBulkReview() {
        IConversationRatingStore store = store();
        store.upsert(stars("low", "a1", 1, 2, "u1")); // 0.25 -> attention
        store.upsert(stars("mid", "a1", 1, 3, "u2")); // 0.5 -> attention (at the threshold)
        store.upsert(stars("high", "a1", 1, 5, "u3")); // 1.0 -> no
        store.upsert(rating("commented", -1, "a1", 1, Scale.FIVE_STAR, 5, "u4", List.of(), "loved it", "api")); // comment -> attention
        store.upsert(stars("elsewhere", "a2", 1, 1, "u5"));

        assertEquals(3, store.count(RatingFilter.needingAttention("a1")));
        assertEquals(4, store.count(RatingFilter.needingAttention(null)));

        RatingFilter lowOnly = new RatingFilter("a1", null, null, null, null, null, 0.3, null, null, null);
        assertEquals(1, store.markReviewed(lowOnly, "maintainer", Instant.now()));
        assertEquals(0, store.markReviewed(lowOnly, "maintainer", Instant.now()), "already reviewed");
        assertEquals(2, store.count(RatingFilter.needingAttention("a1")));
        assertEquals(1, store.count(new RatingFilter("a1", null, null, null, null, null, null, null, null, null, ReviewStatus.REVIEWED, null)));
        assertEquals(1, store.count(new RatingFilter("a2", null, null, null, null, null, null, null, null, null, ReviewStatus.NEW, null)));

        assertEquals(1, store.clearReviewer("maintainer"));
        ConversationRating cleared = store.query(new RatingFilter("a1", null, null, null, null, null, null, null, null, null,
                ReviewStatus.REVIEWED, null), RatingSort.NEWEST, 0, 10).get(0);
        assertNull(cleared.reviewedBy());
        assertEquals(ReviewStatus.REVIEWED, cleared.reviewStatus(), "erasing the reviewer does not reopen the rating");
        assertEquals(0, store.clearReviewer(null));
    }
}
