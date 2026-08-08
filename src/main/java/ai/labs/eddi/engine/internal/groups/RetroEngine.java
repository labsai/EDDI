/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.RetroConfig;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionEventListener;
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink;
import ai.labs.eddi.utils.LogSanitizer;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Turns a RETRO phase's contributions into team-owned group memory (I8).
 * <p>
 * Every discussion otherwise evaporates: nothing a group learns about its own
 * process survives the transcript. Parsed lessons are upserted into
 * {@link IUserMemoryStore} under the synthetic team owner
 * ({@code "group:"+groupId}, {@code group} visibility), which member
 * conversations already load at init — so lessons surface as
 * {@code {properties.*}} in every later discussion with no new namespace.
 * <p>
 * Bounded growth is non-negotiable: per-turn lessons cap at
 * {@code maxLessonsPerRun} (enforced here, whatever the model was told), the
 * stored set FIFOs at {@code maxStoredLessons}, and the idempotency key
 * {@code retro:<hash(lesson)>} with a fixed source agent makes re-running the
 * same retro a no-op rather than a duplicate.
 * <p>
 * Every failure is warn-and-continue — a lesson that fails to store must never
 * fail the discussion that produced it.
 *
 * @author ginccc
 */
public final class RetroEngine {

    private static final Logger LOGGER = Logger.getLogger(RetroEngine.class);

    /**
     * Fixed {@code sourceAgentId} for every lesson. The upsert identity for
     * group-visible entries is {@code (userId, key, sourceAgentId)} — a real
     * speaker id there would make the same lesson from two speakers two rows,
     * defeating the idempotency key.
     */
    static final String RETRO_SOURCE = "retro";

    /** Idempotency-key prefix: {@code retro:<sha256(lesson)[0..15]>}. */
    static final String KEY_PREFIX = "retro:";

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private RetroEngine() {
    }

    /** One parsed lesson. */
    public record Lesson(String lesson, String context) {
    }

    /**
     * Three-tier parse of one retro contribution: strict JSON → JSON embedded in
     * prose/fence → give up (empty). Truncated at {@code maxLessonsPerRun}
     * regardless of what the model produced.
     */
    static List<Lesson> parseLessons(String content, int maxLessonsPerRun) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        JsonNode node = readJson(content);
        if (node == null) {
            node = readJson(embeddedJson(content));
        }
        if (node == null || !node.path("lessons").isArray()) {
            return List.of();
        }
        List<Lesson> lessons = new ArrayList<>();
        for (JsonNode lessonNode : node.path("lessons")) {
            String lesson = lessonNode.path("lesson").isTextual() ? lessonNode.path("lesson").asText().trim() : null;
            if (lesson == null || lesson.isBlank()) {
                continue;
            }
            String context = lessonNode.path("context").isTextual() ? lessonNode.path("context").asText().trim() : null;
            lessons.add(new Lesson(lesson, context != null && !context.isBlank() ? context : null));
            if (lessons.size() >= maxLessonsPerRun) {
                break;
            }
        }
        return lessons;
    }

    /**
     * Harvests a completed RETRO phase: parses each RETRO entry, upserts the
     * lessons team-owned, FIFO-evicts past the stored cap, and fires
     * {@code retro_recorded}.
     */
    public static void harvest(GroupConversation gc, RetroConfig retroConfig, List<TranscriptEntry> repeatEntries,
                               IUserMemoryStore userMemoryStore, String phaseName, GroupDiscussionEventListener listener) {
        if (userMemoryStore == null) {
            LOGGER.warnf("Group %s: RETRO phase '%s' ran but no user memory store is available — lessons are not persisted",
                    LogSanitizer.sanitize(gc.getId()), LogSanitizer.sanitize(phaseName));
            // The event contract holds even when nothing can be stored: a RETRO
            // phase always emits retro_recorded (review finding — this path
            // returned silently, leaving SSE consumers waiting).
            if (listener != null) {
                listener.onRetroRecorded(new GroupConversationEventSink.RetroRecordedEvent(gc.getGroupId(), phaseName, 0));
            }
            return;
        }
        RetroConfig config = retroConfig != null ? retroConfig : new RetroConfig();
        String teamOwner = IUserMemoryStore.TEAM_OWNER_PREFIX + gc.getGroupId();

        // maxLessonsPerRun bounds the whole HARVEST, not each transcript entry —
        // a multi-participant or multi-repeat RETRO phase produces several RETRO
        // entries, and a per-entry cap multiplied by entry count (review finding).
        int remaining = config.maxLessonsPerRun();
        int stored = 0;
        for (TranscriptEntry entry : repeatEntries) {
            if (remaining <= 0) {
                break;
            }
            if (entry == null || entry.type() != TranscriptEntryType.RETRO) {
                continue;
            }
            for (Lesson lesson : parseLessons(entry.content(), remaining)) {
                try {
                    String value = lesson.context() != null ? lesson.lesson() + " (applies: " + lesson.context() + ")" : lesson.lesson();
                    userMemoryStore.upsert(new UserMemoryEntry(null, teamOwner, KEY_PREFIX + lessonHash(lesson.lesson()), value,
                            "context", Visibility.group, RETRO_SOURCE, List.of(gc.getGroupId()), gc.getId(), false, 0,
                            Instant.now(), Instant.now()));
                    stored++;
                    remaining--;
                } catch (Exception e) {
                    LOGGER.warnf("Group %s: failed to store a retro lesson: %s",
                            LogSanitizer.sanitize(gc.getId()), LogSanitizer.sanitize(e.getMessage()));
                }
            }
        }
        if (stored > 0) {
            evictPastCap(userMemoryStore, teamOwner, gc.getGroupId(), config.maxStoredLessons());
            LOGGER.infof("Group %s: RETRO phase '%s' stored %d lesson(s) for team %s",
                    LogSanitizer.sanitize(gc.getId()), LogSanitizer.sanitize(phaseName), stored, LogSanitizer.sanitize(teamOwner));
        }
        if (listener != null) {
            listener.onRetroRecorded(new GroupConversationEventSink.RetroRecordedEvent(gc.getGroupId(), phaseName, stored));
        }
    }

    /**
     * FIFO at the stored cap: the recall is newest-first, so everything past the
     * cap in that ordering is the oldest — evicted so institutional memory stays
     * bounded. Team-owned entries are untouchable by {@code UserMemoryTool}'s own
     * eviction (which only ever removes the calling agent's {@code self} entries),
     * so this is the ONLY reaper.
     */
    private static void evictPastCap(IUserMemoryStore store, String teamOwner, String groupId, int maxStoredLessons) {
        try {
            List<UserMemoryEntry> entries = store.getVisibleEntries(teamOwner, RETRO_SOURCE, List.of(groupId), "most_recent", -1);
            for (int i = maxStoredLessons; i < entries.size(); i++) {
                UserMemoryEntry oldest = entries.get(i);
                if (oldest.id() != null) {
                    store.deleteEntry(oldest.id());
                }
            }
        } catch (Exception e) {
            LOGGER.warnf("Failed to FIFO-evict retro lessons for %s: %s",
                    LogSanitizer.sanitize(teamOwner), LogSanitizer.sanitize(e.getMessage()));
        }
    }

    /** First 16 hex chars of SHA-256 — collision-safe enough for a 50-entry set. */
    static String lessonHash(String lesson) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(lesson.toLowerCase().strip().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory on every JVM; unreachable.
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode readJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(text.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String embeddedJson(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        return start >= 0 && end > start ? content.substring(start, end + 1) : null;
    }
}
