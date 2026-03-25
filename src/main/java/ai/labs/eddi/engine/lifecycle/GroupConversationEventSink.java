package ai.labs.eddi.engine.lifecycle;

import ai.labs.eddi.configs.groups.model.GroupConversation;

import java.util.List;

/**
 * SSE event definitions for group conversation streaming. Each record
 * represents a distinct event type that can be sent over a Server-Sent Events
 * connection.
 *
 * @author ginccc
 */
public final class GroupConversationEventSink {

    private GroupConversationEventSink() {
    }

    // --- Event type constants ---

    public static final String EVENT_GROUP_START = "group_start";
    public static final String EVENT_PHASE_START = "phase_start";
    public static final String EVENT_SPEAKER_START = "speaker_start";
    public static final String EVENT_TOKEN = "token";
    public static final String EVENT_SPEAKER_COMPLETE = "speaker_complete";
    public static final String EVENT_PHASE_COMPLETE = "phase_complete";
    public static final String EVENT_SYNTHESIS_START = "synthesis_start";
    public static final String EVENT_SYNTHESIS_COMPLETE = "synthesis_complete";
    public static final String EVENT_GROUP_COMPLETE = "group_complete";
    public static final String EVENT_GROUP_ERROR = "group_error";

    // --- Event payloads ---

    public record GroupStartEvent(String groupConversationId, String groupId, String question, String style, int totalPhases,
            List<String> memberAgentIds) {
    }

    public record PhaseStartEvent(int phaseIndex, String phaseName, String phaseType, String participants) {
    }

    public record SpeakerStartEvent(String agentId, String displayName, int phaseIndex, String phaseName) {
    }

    public record TokenEvent(String agentId, String token) {
    }

    public record SpeakerCompleteEvent(String agentId, String displayName, String response, int phaseIndex, String phaseName) {
    }

    public record PhaseCompleteEvent(int phaseIndex, String phaseName) {
    }

    public record SynthesisStartEvent(String moderatorAgentId) {
    }

    public record SynthesisCompleteEvent(String synthesizedAnswer) {
    }

    public record GroupCompleteEvent(GroupConversation.GroupConversationState state, String synthesizedAnswer) {
    }

    public record GroupErrorEvent(String error) {
    }
}
