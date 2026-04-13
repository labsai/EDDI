package ai.labs.eddi.modules.rules.impl.conditions;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.MemoryKey;
import ai.labs.eddi.engine.memory.model.Attachment;
import ai.labs.eddi.engine.memory.model.Data;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.util.List;
import java.util.Map;

import static ai.labs.eddi.modules.rules.impl.conditions.IRuleCondition.ExecutionState.FAIL;
import static ai.labs.eddi.modules.rules.impl.conditions.IRuleCondition.ExecutionState.SUCCESS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ContentTypeMatcher} covering MIME type matching,
 * wildcard patterns, minCount behavior, config parsing, clone, and edge cases.
 */
class ContentTypeMatcherTest {

    private ContentTypeMatcher matcher;
    private IConversationMemory memory;
    private IConversationMemory.IWritableConversationStep currentStep;

    @BeforeEach
    void setUp() {
        matcher = new ContentTypeMatcher();
        memory = mock(IConversationMemory.class);
        currentStep = mock(IConversationMemory.IWritableConversationStep.class);
        when(memory.getCurrentStep()).thenReturn(currentStep);
    }

    /**
     * Helper: stubs {@code currentStep.getLatestData(any())} to return the given
     * data. Uses {@code doReturn().when()} to avoid unchecked generic warnings that
     * arise with {@code when().thenReturn()} on generic methods.
     */
    private void stubAttachments(IData<List<Attachment>> data) {
        doReturn(data).when(currentStep).getLatestData(ArgumentMatchers.<MemoryKey<?>>any());
    }

    private void stubAttachmentList(Attachment... attachments) {
        stubAttachments(new Data<>("attachments", List.of(attachments)));
    }

    // ─── Identity ───────────────────────────────────────────────

    @Nested
    @DisplayName("Identity")
    class Identity {

        @Test
        @DisplayName("should have correct ID matching documented JSON type")
        void correctId() {
            assertEquals("contentTypeMatcher", matcher.getId());
            assertEquals(ContentTypeMatcher.ID, matcher.getId());
        }
    }

    // ─── Configuration ──────────────────────────────────────────

    @Nested
    @DisplayName("Configuration")
    class Configuration {

        @Test
        @DisplayName("should store and retrieve mimeType config")
        void storesAndRetrievesMimeType() {
            matcher.setConfigs(Map.of("mimeType", "image/png"));

            assertEquals("image/png", matcher.getConfigs().get("mimeType"));
        }

        @Test
        @DisplayName("should parse valid minCount")
        void parsesValidMinCount() {
            matcher.setConfigs(Map.of("mimeType", "image/*", "minCount", "3"));

            assertEquals("3", matcher.getConfigs().get("minCount"));
        }

        @Test
        @DisplayName("should fallback to 1 on invalid minCount")
        void fallbackOnInvalidMinCount() {
            matcher.setConfigs(Map.of("mimeType", "image/*", "minCount", "not_a_number"));

            assertEquals("1", matcher.getConfigs().get("minCount"));
        }

        @Test
        @DisplayName("should default minCount to 1 when not specified")
        void defaultMinCount() {
            matcher.setConfigs(Map.of("mimeType", "application/pdf"));

            assertEquals("1", matcher.getConfigs().get("minCount"));
        }

        @Test
        @DisplayName("should handle null configs without error")
        void handlesNullConfigs() {
            matcher.setConfigs(null);

            assertFalse(matcher.getConfigs().containsKey("mimeType"));
            assertEquals("1", matcher.getConfigs().get("minCount"));
        }

        @Test
        @DisplayName("should omit mimeType from getConfigs if not set")
        void omitsMimeTypeIfNull() {
            matcher.setConfigs(Map.of());

            assertFalse(matcher.getConfigs().containsKey("mimeType"));
        }

        @Test
        @DisplayName("should fallback to 1 on empty minCount string")
        void fallbackOnEmptyMinCount() {
            matcher.setConfigs(Map.of("mimeType", "image/*", "minCount", ""));

            assertEquals("1", matcher.getConfigs().get("minCount"));
        }

        @Test
        @DisplayName("should parse negative minCount — consistent with SizeMatcher/Occurrence sentinel pattern")
        void parsesNegativeMinCount() {
            matcher.setConfigs(Map.of("mimeType", "image/*", "minCount", "-1"));

            // Negative values are an established pattern in the rules engine:
            // SizeMatcher.min defaults to -1 (meaning "not configured"),
            // Occurrence.minTimesOccurred defaults to -1. ContentTypeMatcher
            // follows the same convention for consistency.
            assertEquals("-1", matcher.getConfigs().get("minCount"));
        }

        @Test
        @DisplayName("should parse zero minCount")
        void parsesZeroMinCount() {
            matcher.setConfigs(Map.of("mimeType", "image/*", "minCount", "0"));

            assertEquals("0", matcher.getConfigs().get("minCount"));
        }
    }

    // ─── Execution: No mimeType ─────────────────────────────────

    @Nested
    @DisplayName("Execution — no mimeType configured")
    class NoMimeType {

        @Test
        @DisplayName("should FAIL when mimeType is null (no config)")
        void failsWhenMimeTypeNull() {
            assertEquals(FAIL, matcher.execute(memory, List.of()));
        }

        @Test
        @DisplayName("should FAIL when mimeType is blank")
        void failsWhenMimeTypeBlank() {
            matcher.setConfigs(Map.of("mimeType", "   "));

            assertEquals(FAIL, matcher.execute(memory, List.of()));
        }

        @Test
        @DisplayName("should FAIL when mimeType is empty string")
        void failsWhenMimeTypeEmpty() {
            matcher.setConfigs(Map.of("mimeType", ""));

            assertEquals(FAIL, matcher.execute(memory, List.of()));
        }
    }

    // ─── Execution: No attachments ──────────────────────────────

    @Nested
    @DisplayName("Execution — no attachments in memory")
    class NoAttachments {

        @Test
        @DisplayName("should FAIL when no attachment data in memory")
        void failsWhenNoDataInMemory() {
            matcher.setConfigs(Map.of("mimeType", "image/png"));
            doReturn(null).when(currentStep).getLatestData(ArgumentMatchers.<MemoryKey<?>>any());

            assertEquals(FAIL, matcher.execute(memory, List.of()));
        }

        @Test
        @DisplayName("should FAIL when attachment data result is null")
        void failsWhenDataResultNull() {
            matcher.setConfigs(Map.of("mimeType", "image/png"));
            stubAttachments(new Data<>("attachments", null));

            assertEquals(FAIL, matcher.execute(memory, List.of()));
        }

        @Test
        @DisplayName("should FAIL when attachment list is empty")
        void failsWhenAttachmentListEmpty() {
            matcher.setConfigs(Map.of("mimeType", "image/png"));
            stubAttachments(new Data<>("attachments", List.of()));

            assertEquals(FAIL, matcher.execute(memory, List.of()));
        }
    }

    // ─── Execution: Exact MIME match ────────────────────────────

    @Nested
    @DisplayName("Execution — exact match")
    class ExactMatch {

        @Test
        @DisplayName("should SUCCESS on exact mimeType match")
        void successOnExactMatch() {
            matcher.setConfigs(Map.of("mimeType", "image/png"));
            stubAttachmentList(new Attachment("image/png", "photo.png", 1024, "ref"));

            assertEquals(SUCCESS, matcher.execute(memory, List.of()));
        }

        @Test
        @DisplayName("should FAIL when mimeType does not match")
        void failsWhenNoMatch() {
            matcher.setConfigs(Map.of("mimeType", "image/png"));
            stubAttachmentList(new Attachment("application/pdf", "doc.pdf", 5000, "ref"));

            assertEquals(FAIL, matcher.execute(memory, List.of()));
        }

        @Test
        @DisplayName("should FAIL on partial match without wildcard")
        void failsOnPartialMatch() {
            matcher.setConfigs(Map.of("mimeType", "image"));
            stubAttachmentList(new Attachment("image/png", "photo.png", 1024, "ref"));

            assertEquals(FAIL, matcher.execute(memory, List.of()));
        }

        @Test
        @DisplayName("should match application/pdf exactly")
        void matchesApplicationPdf() {
            matcher.setConfigs(Map.of("mimeType", "application/pdf"));
            stubAttachmentList(new Attachment("application/pdf", "doc.pdf", 5000, "ref"));

            assertEquals(SUCCESS, matcher.execute(memory, List.of()));
        }

        @Test
        @DisplayName("should be case-sensitive (MIME types are lowercase by convention)")
        void caseSensitiveMatch() {
            matcher.setConfigs(Map.of("mimeType", "image/png"));
            var att = new Attachment();
            att.setMimeType("IMAGE/PNG");
            stubAttachmentList(att);

            // image/png != IMAGE/PNG — case-sensitive
            assertEquals(FAIL, matcher.execute(memory, List.of()));
        }
    }

    // ─── Execution: Wildcard MIME match ──────────────────────────

    @Nested
    @DisplayName("Execution — wildcard match")
    class WildcardMatch {

        @Test
        @DisplayName("should match image/* against image/png")
        void matchesImagePng() {
            matcher.setConfigs(Map.of("mimeType", "image/*"));
            stubAttachmentList(new Attachment("image/png", "a.png", 1024, "ref"));

            assertEquals(SUCCESS, matcher.execute(memory, List.of()));
        }

        @Test
        @DisplayName("should match image/* against image/jpeg")
        void matchesImageJpeg() {
            matcher.setConfigs(Map.of("mimeType", "image/*"));
            stubAttachmentList(new Attachment("image/jpeg", "b.jpg", 2048, "ref"));

            assertEquals(SUCCESS, matcher.execute(memory, List.of()));
        }

        @Test
        @DisplayName("should match image/* against image/svg+xml")
        void matchesImageSvg() {
            matcher.setConfigs(Map.of("mimeType", "image/*"));
            var att = new Attachment();
            att.setMimeType("image/svg+xml");
            stubAttachmentList(att);

            assertEquals(SUCCESS, matcher.execute(memory, List.of()));
        }

        @Test
        @DisplayName("should NOT match image/* against application/pdf")
        void doesNotMatchCrossType() {
            matcher.setConfigs(Map.of("mimeType", "image/*"));
            stubAttachmentList(new Attachment("application/pdf", "doc.pdf", 5000, "ref"));

            assertEquals(FAIL, matcher.execute(memory, List.of()));
        }

        @Test
        @DisplayName("should NOT match image/* against audio/mp3")
        void doesNotMatchAudio() {
            matcher.setConfigs(Map.of("mimeType", "image/*"));
            stubAttachmentList(new Attachment("audio/mp3", "song.mp3", 4096, "ref"));

            assertEquals(FAIL, matcher.execute(memory, List.of()));
        }

        @Test
        @DisplayName("should match */* against any MIME type")
        void matchesUniversalWildcard() {
            matcher.setConfigs(Map.of("mimeType", "*/*"));
            stubAttachmentList(new Attachment("video/mp4", "vid.mp4", 10000, "ref"));

            assertEquals(SUCCESS, matcher.execute(memory, List.of()));
        }

        @Test
        @DisplayName("should match application/* against application/json")
        void matchesApplicationWildcard() {
            matcher.setConfigs(Map.of("mimeType", "application/*"));
            var att = new Attachment();
            att.setMimeType("application/json");
            stubAttachmentList(att);

            assertEquals(SUCCESS, matcher.execute(memory, List.of()));
        }

        @Test
        @DisplayName("should match text/* against text/plain")
        void matchesTextWildcard() {
            matcher.setConfigs(Map.of("mimeType", "text/*"));
            var att = new Attachment();
            att.setMimeType("text/plain");
            stubAttachmentList(att);

            assertEquals(SUCCESS, matcher.execute(memory, List.of()));
        }

        @Test
        @DisplayName("should match audio/* against audio/mpeg")
        void matchesAudioWildcard() {
            matcher.setConfigs(Map.of("mimeType", "audio/*"));
            var att = new Attachment();
            att.setMimeType("audio/mpeg");
            stubAttachmentList(att);

            assertEquals(SUCCESS, matcher.execute(memory, List.of()));
        }
    }

    // ─── Execution: minCount ────────────────────────────────────

    @Nested
    @DisplayName("Execution — minCount")
    class MinCount {

        @Test
        @DisplayName("should SUCCESS when match count equals minCount")
        void successWhenCountEqualsMin() {
            matcher.setConfigs(Map.of("mimeType", "image/*", "minCount", "2"));
            stubAttachmentList(
                    new Attachment("image/png", "a.png", 1024, "ref1"),
                    new Attachment("image/jpeg", "b.jpg", 2048, "ref2"));

            assertEquals(SUCCESS, matcher.execute(memory, List.of()));
        }

        @Test
        @DisplayName("should SUCCESS when match count exceeds minCount")
        void successWhenCountExceedsMin() {
            matcher.setConfigs(Map.of("mimeType", "image/*", "minCount", "2"));
            stubAttachmentList(
                    new Attachment("image/png", "a.png", 1024, "ref1"),
                    new Attachment("image/jpeg", "b.jpg", 2048, "ref2"),
                    new Attachment("image/gif", "c.gif", 512, "ref3"));

            assertEquals(SUCCESS, matcher.execute(memory, List.of()));
        }

        @Test
        @DisplayName("should FAIL when match count is below minCount")
        void failsWhenCountBelowMin() {
            matcher.setConfigs(Map.of("mimeType", "image/*", "minCount", "3"));
            stubAttachmentList(
                    new Attachment("image/png", "a.png", 1024, "ref1"),
                    new Attachment("image/jpeg", "b.jpg", 2048, "ref2"));

            assertEquals(FAIL, matcher.execute(memory, List.of()));
        }

        @Test
        @DisplayName("should only count matching attachments toward minCount")
        void countsOnlyMatchingAttachments() {
            matcher.setConfigs(Map.of("mimeType", "image/*", "minCount", "2"));
            stubAttachmentList(
                    new Attachment("image/png", "a.png", 1024, "ref1"),
                    new Attachment("application/pdf", "d.pdf", 5000, "ref3"),
                    new Attachment("image/jpeg", "b.jpg", 2048, "ref2"));

            assertEquals(SUCCESS, matcher.execute(memory, List.of()));
        }

        @Test
        @DisplayName("should FAIL when only non-matching types present")
        void failsWithOnlyNonMatching() {
            matcher.setConfigs(Map.of("mimeType", "image/*", "minCount", "1"));
            stubAttachmentList(
                    new Attachment("application/pdf", "d.pdf", 5000, "ref1"),
                    new Attachment("text/plain", "t.txt", 100, "ref2"));

            assertEquals(FAIL, matcher.execute(memory, List.of()));
        }

        @Test
        @DisplayName("should FAIL with minCount 1 (default) when single attachment doesn't match")
        void failsDefaultMinCountNoMatch() {
            matcher.setConfigs(Map.of("mimeType", "video/*"));
            stubAttachmentList(new Attachment("image/png", "a.png", 1024, "ref1"));

            assertEquals(FAIL, matcher.execute(memory, List.of()));
        }
    }

    // ─── Execution: Mixed payload ───────────────────────────────

    @Nested
    @DisplayName("Execution — mixed payload types")
    class MixedPayload {

        @Test
        @DisplayName("should ignore non-Attachment objects in the list")
        @SuppressWarnings("unchecked")
        void ignoresNonAttachmentObjects() {
            matcher.setConfigs(Map.of("mimeType", "image/*"));
            var att = new Attachment();
            att.setMimeType("image/png");
            List<?> mixed = List.of(att, "not-an-attachment", 42);
            doReturn(new Data<>("attachments", mixed)).when(currentStep)
                    .getLatestData(ArgumentMatchers.<MemoryKey<?>>any());

            assertEquals(SUCCESS, matcher.execute(memory, List.of()));
        }

        @Test
        @DisplayName("should FAIL when all entries are non-Attachment objects")
        @SuppressWarnings("unchecked")
        void failsWhenAllNonAttachment() {
            matcher.setConfigs(Map.of("mimeType", "image/*"));
            List<?> nonAttachments = List.of("string", 123, Map.of("key", "val"));
            doReturn(new Data<>("attachments", nonAttachments)).when(currentStep)
                    .getLatestData(ArgumentMatchers.<MemoryKey<?>>any());

            assertEquals(FAIL, matcher.execute(memory, List.of()));
        }
    }

    // ─── Clone ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Clone")
    class CloneTests {

        @Test
        @DisplayName("should clone with same configs")
        void clonePreservesConfigs() {
            matcher.setConfigs(Map.of("mimeType", "audio/*", "minCount", "3"));
            ContentTypeMatcher cloned = (ContentTypeMatcher) matcher.clone();

            assertNotSame(matcher, cloned);
            assertEquals("audio/*", cloned.getConfigs().get("mimeType"));
            assertEquals("3", cloned.getConfigs().get("minCount"));
        }

        @Test
        @DisplayName("clone should be independent from original")
        void cloneIsIndependent() {
            matcher.setConfigs(Map.of("mimeType", "image/*", "minCount", "2"));
            ContentTypeMatcher cloned = (ContentTypeMatcher) matcher.clone();

            // Modify original
            matcher.setConfigs(Map.of("mimeType", "text/*", "minCount", "10"));

            // Clone should retain original values
            assertEquals("image/*", cloned.getConfigs().get("mimeType"));
            assertEquals("2", cloned.getConfigs().get("minCount"));
        }

        @Test
        @DisplayName("clone should have same ID")
        void cloneHasSameId() {
            ContentTypeMatcher cloned = (ContentTypeMatcher) matcher.clone();

            assertEquals(ContentTypeMatcher.ID, cloned.getId());
        }
    }

    // ─── Edge Cases ─────────────────────────────────────────────

    @Nested
    @DisplayName("Execution — edge cases")
    class EdgeCases {

        @Test
        @DisplayName("should not match attachment with null mimeType")
        void ignoresAttachmentWithNullMimeType() {
            matcher.setConfigs(Map.of("mimeType", "image/*"));
            var nullMime = new Attachment(); // mimeType is null
            stubAttachmentList(nullMime);

            assertEquals(FAIL, matcher.execute(memory, List.of()));
        }

        @Test
        @DisplayName("should SUCCESS with minCount 0 and no matching attachments")
        void minCountZeroAlwaysSucceeds() {
            matcher.setConfigs(Map.of("mimeType", "image/*", "minCount", "0"));
            stubAttachmentList(new Attachment("application/pdf", "d.pdf", 5000, "ref"));

            // 0 matching >= 0 minCount → SUCCESS
            assertEquals(SUCCESS, matcher.execute(memory, List.of()));
        }

        @Test
        @DisplayName("should handle large attachment list efficiently")
        void handlesLargeList() {
            matcher.setConfigs(Map.of("mimeType", "image/png", "minCount", "5"));
            Attachment[] atts = new Attachment[100];
            for (int i = 0; i < 100; i++) {
                atts[i] = new Attachment("image/png", "img" + i + ".png", 1024, "ref" + i);
            }
            stubAttachmentList(atts);

            assertEquals(SUCCESS, matcher.execute(memory, List.of()));
        }

        @Test
        @DisplayName("should count exactly — minCount boundary at exact attachment count")
        void exactBoundary() {
            matcher.setConfigs(Map.of("mimeType", "image/*", "minCount", "3"));
            stubAttachmentList(
                    new Attachment("image/png", "a.png", 100, "r1"),
                    new Attachment("image/jpeg", "b.jpg", 200, "r2"),
                    new Attachment("image/gif", "c.gif", 300, "r3"));

            assertEquals(SUCCESS, matcher.execute(memory, List.of()));
        }

        @Test
        @DisplayName("should FAIL with exactly one less than minCount")
        void oneLessThanMinCount() {
            matcher.setConfigs(Map.of("mimeType", "image/*", "minCount", "3"));
            stubAttachmentList(
                    new Attachment("image/png", "a.png", 100, "r1"),
                    new Attachment("image/jpeg", "b.jpg", 200, "r2"));

            assertEquals(FAIL, matcher.execute(memory, List.of()));
        }
    }
}
