/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.context;

import ai.labs.eddi.configs.groups.ISharedArtifactStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.configs.groups.model.SharedArtifact;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.configs.hitl.model.ApprovalContextBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Renders a group phase or task pause's {@code approvalContext} blocks — the
 * group counterpart of {@link ApprovalContextRenderer}, sharing its pipeline
 * (restricted engine, limits, redaction, caps). It builds the {@code group} and
 * {@code hitl} template roots from the discussion, then hands the blocks to the
 * shared renderer.
 * <p>
 * {@code group} holds: {@code groupId}, {@code conversationId},
 * {@code question}, {@code phase {index, name, type}}, {@code granularity},
 * {@code members[] {agentId, displayName, role}}, {@code transcript[]} (the
 * last {@value #MAX_TRANSCRIPT_ENTRIES} entries as
 * {@code {speaker, displayName, role, type, phaseName, content}}),
 * {@code lastByRole.<ROLE>}, {@code lastByType.<TYPE>} and
 * {@code lastBySpeaker.<agentId>} (each {@code {content, displayName,
 * phaseName}}; errors and skips do not count), {@code tasksAwaitingApproval[]
 * {id, subject, description, assignee, result}} and {@code artifacts.<name>
 * {type, version, status, content, json}} ({@code json} is the parsed content
 * of a JSON artifact, else null). Every string is capped at
 * {@value ApprovalContextTemplateData#MAX_STRING_CHARS} characters.
 * <p>
 * Artifacts are read from the artifact store once per pause (at most
 * {@value #MAX_ARTIFACTS}); a failed read leaves {@code group.artifacts} empty
 * and is logged, never failing the pause.
 */
@ApplicationScoped
public class GroupApprovalContextRenderer {

    private static final Logger LOGGER = Logger.getLogger(GroupApprovalContextRenderer.class);

    /** Transcript entries exposed to a template. */
    public static final int MAX_TRANSCRIPT_ENTRIES = 50;
    /** Artifacts exposed to a template. */
    public static final int MAX_ARTIFACTS = 20;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ApprovalContextRenderer renderer;
    private final ISharedArtifactStore artifactStore;

    @Inject
    public GroupApprovalContextRenderer(ApprovalContextRenderer renderer, ISharedArtifactStore artifactStore) {
        this.renderer = renderer;
        this.artifactStore = artifactStore;
    }

    /**
     * Renders {@code config.hitlConfig.approvalContext} for the pause just
     * committed on {@code gc}.
     *
     * @return the context, or {@code null} when none is configured, nothing
     *         rendered, or rendering failed as a whole — never throws
     */
    public RenderedApprovalContext render(GroupConversation gc, AgentGroupConfiguration config, int phaseIdx, String granularity) {
        try {
            if (config == null || config.getHitlConfig() == null) {
                return null;
            }
            List<ApprovalContextBlock> blocks = config.getHitlConfig().getApprovalContext();
            if (blocks == null || blocks.isEmpty()) {
                return null;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("hitl", hitl(gc, granularity));
            data.put("group", group(gc, config, phaseIdx, granularity, readArtifacts(gc)));
            List<ApprovalContextRenderer.Work> work = new ArrayList<>();
            for (ApprovalContextBlock block : blocks) {
                work.add(new ApprovalContextRenderer.Work(block, block.getLabel(), RenderedApprovalContext.SOURCE_GROUP, null, data));
            }
            return renderer.renderAll(work, ApprovalContextRenderer.SURFACE_GROUP);
        } catch (RuntimeException | StackOverflowError e) {
            LOGGER.warnf("Group approval context not rendered for %s: %s", sanitize(gc != null ? gc.getId() : null), sanitize(e.getMessage()));
            return null;
        }
    }

    static Map<String, Object> hitl(GroupConversation gc, String granularity) {
        Map<String, Object> hitl = new LinkedHashMap<>();
        hitl.put("pauseType", granularity);
        hitl.put("reason", ApprovalContextTemplateData.capString(gc.getHitlPauseReason()));
        hitl.put("toolCalls", List.of());
        hitl.put("executedUngatedCalls", List.of());
        return hitl;
    }

    private List<SharedArtifact> readArtifacts(GroupConversation gc) {
        if (artifactStore == null || gc.getId() == null) {
            return List.of();
        }
        try {
            List<SharedArtifact> all = artifactStore.listByGroupConversationId(gc.getId());
            return all == null ? List.of() : all.size() > MAX_ARTIFACTS ? all.subList(0, MAX_ARTIFACTS) : all;
        } catch (Exception e) {
            LOGGER.warnf("Artifacts of %s not readable for the approval context: %s", sanitize(gc.getId()), sanitize(e.getMessage()));
            return List.of();
        }
    }

    /** The {@code group} root. Package-private for tests. */
    static Map<String, Object> group(GroupConversation gc, AgentGroupConfiguration config, int phaseIdx, String granularity,
                                     List<SharedArtifact> artifacts) {
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("groupId", gc.getGroupId());
        group.put("conversationId", gc.getId());
        group.put("question", ApprovalContextTemplateData.capString(gc.getOriginalQuestion()));
        group.put("granularity", granularity);

        Map<String, Object> phase = new LinkedHashMap<>();
        phase.put("index", phaseIdx);
        List<AgentGroupConfiguration.DiscussionPhase> phases = config.getPhases();
        if (phases != null && phaseIdx >= 0 && phaseIdx < phases.size()) {
            var p = phases.get(phaseIdx);
            phase.put("name", p.name());
            phase.put("type", p.type() != null ? p.type().name() : null);
        } else {
            phase.put("name", gc.getPausedPhaseName());
            phase.put("type", null);
        }
        group.put("phase", phase);

        Map<String, String> roleByAgent = new LinkedHashMap<>();
        List<Map<String, Object>> members = new ArrayList<>();
        if (config.getMembers() != null) {
            for (var member : config.getMembers()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("agentId", member.agentId());
                m.put("displayName", member.displayName());
                m.put("role", member.role());
                members.add(m);
                if (member.agentId() != null && member.role() != null) {
                    roleByAgent.put(member.agentId(), member.role());
                }
            }
        }
        group.put("members", members);

        List<Map<String, Object>> transcript = new ArrayList<>();
        Map<String, Object> lastByRole = new LinkedHashMap<>();
        Map<String, Object> lastByType = new LinkedHashMap<>();
        Map<String, Object> lastBySpeaker = new LinkedHashMap<>();
        List<TranscriptEntry> entries = gc.getTranscript() == null ? List.of() : gc.getTranscript();
        int from = Math.max(0, entries.size() - MAX_TRANSCRIPT_ENTRIES);
        for (TranscriptEntry entry : entries.subList(from, entries.size())) {
            String role = entry.speakerAgentId() != null ? roleByAgent.get(entry.speakerAgentId()) : null;
            String type = entry.type() != null ? entry.type().name() : null;
            String content = ApprovalContextTemplateData.capString(entry.content());
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("speaker", entry.speakerAgentId());
            t.put("displayName", entry.speakerDisplayName());
            t.put("role", role);
            t.put("type", type);
            t.put("phaseName", entry.phaseName());
            t.put("content", content);
            transcript.add(t);
            if (entry.type() == TranscriptEntryType.ERROR || entry.type() == TranscriptEntryType.SKIPPED) {
                continue;
            }
            Map<String, Object> last = new LinkedHashMap<>();
            last.put("content", content);
            last.put("displayName", entry.speakerDisplayName());
            last.put("phaseName", entry.phaseName());
            if (role != null) {
                lastByRole.put(role, last);
            }
            if (type != null) {
                lastByType.put(type, last);
            }
            if (entry.speakerAgentId() != null) {
                lastBySpeaker.put(entry.speakerAgentId(), last);
            }
        }
        group.put("transcript", transcript);
        group.put("lastByRole", lastByRole);
        group.put("lastByType", lastByType);
        group.put("lastBySpeaker", lastBySpeaker);

        List<Map<String, Object>> awaiting = new ArrayList<>();
        if (gc.getTaskList() != null) {
            for (var task : gc.getTaskList().all()) {
                if (task.status() != SharedTaskList.TaskStatus.AWAITING_APPROVAL) {
                    continue;
                }
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("id", task.id());
                t.put("subject", ApprovalContextTemplateData.capString(task.subject()));
                t.put("description", ApprovalContextTemplateData.capString(task.description()));
                t.put("assignee", task.assignedDisplayName() != null ? task.assignedDisplayName() : task.assignedAgentId());
                t.put("result", ApprovalContextTemplateData.capString(task.result()));
                awaiting.add(t);
            }
        }
        group.put("tasksAwaitingApproval", awaiting);

        Map<String, Object> artifactMap = new LinkedHashMap<>();
        for (SharedArtifact artifact : artifacts) {
            if (artifact.getName() == null) {
                continue;
            }
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("type", artifact.getType() != null ? artifact.getType().name() : null);
            a.put("version", artifact.getVersion());
            a.put("status", artifact.getStatus() != null ? artifact.getStatus().name() : null);
            a.put("content", ApprovalContextTemplateData.capString(artifact.getContent()));
            a.put("json", artifact.getType() == SharedArtifact.ArtifactType.JSON ? parseJson(artifact.getContent()) : null);
            artifactMap.put(artifact.getName(), a);
        }
        group.put("artifacts", artifactMap);
        return group;
    }

    private static Object parseJson(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            return ApprovalContextTemplateData.cap(MAPPER.readValue(content, Object.class));
        } catch (Exception e) {
            return null;
        }
    }
}
