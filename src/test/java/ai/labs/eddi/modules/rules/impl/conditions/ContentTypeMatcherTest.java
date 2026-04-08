package ai.labs.eddi.modules.rules.impl.conditions;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.MemoryKey;
import ai.labs.eddi.engine.memory.model.Attachment;
import ai.labs.eddi.engine.memory.model.Data;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static ai.labs.eddi.modules.rules.impl.conditions.IRuleCondition.ExecutionState.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

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
        doReturn(data).when(currentStep).getLatestData(any(MemoryKey.class));
    }

    @Test
    void execute_matchesExactMimeType() {
        matcher.setConfigs(Map.of("mimeType", "image/png"));

        var att = new Attachment("image/png", "photo.png", 1024, "ref");
        stubAttachments(new Data<>("attachments", List.of(att)));

        assertEquals(SUCCESS, matcher.execute(memory, List.of()));
    }

    @Test
    void execute_matchesWildcardMimeType() {
        matcher.setConfigs(Map.of("mimeType", "image/*"));

        var att = new Attachment("image/jpeg", "photo.jpg", 2048, "ref");
        stubAttachments(new Data<>("attachments", List.of(att)));

        assertEquals(SUCCESS, matcher.execute(memory, List.of()));
    }

    @Test
    void execute_failsOnNonMatchingMimeType() {
        matcher.setConfigs(Map.of("mimeType", "image/*"));

        var att = new Attachment("audio/mp3", "song.mp3", 4096, "ref");
        stubAttachments(new Data<>("attachments", List.of(att)));

        assertEquals(FAIL, matcher.execute(memory, List.of()));
    }

    @Test
    void execute_failsOnNoAttachments() {
        matcher.setConfigs(Map.of("mimeType", "image/*"));
        doReturn(null).when(currentStep).getLatestData(any(MemoryKey.class));

        assertEquals(FAIL, matcher.execute(memory, List.of()));
    }

    @Test
    void execute_failsOnBlankMimeType() {
        matcher.setConfigs(Map.of("mimeType", ""));

        assertEquals(FAIL, matcher.execute(memory, List.of()));
    }

    @Test
    void execute_minCountRequiresMultiple() {
        matcher.setConfigs(Map.of("mimeType", "image/*", "minCount", "2"));

        var att = new Attachment("image/png", "a.png", 1024, "ref");
        stubAttachments(new Data<>("attachments", List.of(att)));

        // Only 1 match, need 2
        assertEquals(FAIL, matcher.execute(memory, List.of()));
    }

    @Test
    void execute_minCountSucceedsWithEnough() {
        matcher.setConfigs(Map.of("mimeType", "image/*", "minCount", "2"));

        var att1 = new Attachment("image/png", "a.png", 1024, "ref1");
        var att2 = new Attachment("image/jpeg", "b.jpg", 2048, "ref2");
        stubAttachments(new Data<>("attachments", List.of(att1, att2)));

        assertEquals(SUCCESS, matcher.execute(memory, List.of()));
    }

    @Test
    void execute_globalWildcardMatchesAll() {
        matcher.setConfigs(Map.of("mimeType", "*/*"));

        var att = new Attachment("application/pdf", "doc.pdf", 5000, "ref");
        stubAttachments(new Data<>("attachments", List.of(att)));

        assertEquals(SUCCESS, matcher.execute(memory, List.of()));
    }

    @Test
    void clone_preservesConfig() {
        matcher.setConfigs(Map.of("mimeType", "audio/*", "minCount", "3"));
        ContentTypeMatcher cloned = (ContentTypeMatcher) matcher.clone();

        assertEquals("audio/*", cloned.getConfigs().get("mimeType"));
        assertEquals("3", cloned.getConfigs().get("minCount"));
    }
}
