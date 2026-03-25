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
    public static final String EVENT_ROUND_START = "round_start";
    public static final String EVENT_SPEAKER_START = "speaker_start";
    public static final String EVENT_TOKEN = "token";
    public static final String EVENT_SPEAKER_COMPLETE = "speaker_complete";
    public static final String EVENT_ROUND_COMPLETE = "round_complete";
    public static final String EVENT_SYNTHESIS_START = "synthesis_start";
    public static final String EVENT_SYNTHESIS_COMPLETE = "synthesis_complete";
    public static final String EVENT_GROUP_COMPLETE = "group_complete";
    public static final String EVENT_GROUP_ERROR = "group_error";

    // --- Event payloads ---

    public record GroupStartEvent(String groupConversationId, String groupId, String question, int maxRounds, List<String> memberAgentIds) {
    }

    public record RoundStartEvent(int round, int totalRounds) {
    }

    public record SpeakerStartEvent(String agentId, String displayName, int round) {
    }

    public record TokenEvent(String agentId, String token) {
    }

    public record SpeakerCompleteEvent(String agentId, String displayName, String response, int round) {
    }

    public record RoundCompleteEvent(int round) {
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
