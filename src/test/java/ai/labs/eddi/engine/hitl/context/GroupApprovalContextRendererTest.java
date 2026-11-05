/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.context;

import ai.labs.eddi.configs.groups.ISharedArtifactStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.configs.groups.model.SharedArtifact;
import ai.labs.eddi.configs.groups.model.SharedArtifact.ArtifactType;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskStatus;
import ai.labs.eddi.configs.hitl.model.ApprovalContextBlock;
import ai.labs.eddi.modules.templating.impl.RuntimeTemplateEngineFactory.Settings;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.qute.Engine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("GroupApprovalContextRenderer")
class GroupApprovalContextRendererTest {

    private ISharedArtifactStore artifactStore;
    private GroupApprovalContextRenderer renderer;
    private GroupConversation gc;
    private AgentGroupConfiguration config;

    @BeforeEach
    void setUp() throws Exception {
        artifactStore = mock(ISharedArtifactStore.class);
        var base = new ApprovalContextRenderer(
                new TemplatingEngine(Engine.builder().addDefaults().build(),
                        new Settings(16_000, 2_000, false, true, Settings.DEFAULT_ITERATION_METADATA_PREFIX, 500)),
                m -> new LinkedHashMap<>(), new SimpleMeterRegistry(), 8_000);
        renderer = new GroupApprovalContextRenderer(base, artifactStore);

        gc = new GroupConversation();
        gc.setId("gc-1");
        gc.setGroupId("group-1");
        gc.setOriginalQuestion("Should we publish the Acme post?");
        gc.setHitlPauseReason("Requires human approval (PHASE) — phase: Synthesis");

        config = new AgentGroupConfiguration();
        config.setMembers(List.of(new GroupMember("writer", "Writer", 1, "WRITER"), new GroupMember("da", "Devil's Advocate", 2, "DEVIL_ADVOCATE")));
        config.setPhases(List.of(new DiscussionPhase("Draft", PhaseType.OPINION), new DiscussionPhase("Synthesis", PhaseType.SYNTHESIS)));
        var hitl = new AgentGroupConfiguration.HitlConfig();
        config.setHitlConfig(hitl);
    }

    private void blocks(ApprovalContextBlock... blocks) {
        config.getHitlConfig().setApprovalContext(List.of(blocks));
    }

    private static ApprovalContextBlock block(String label, String template) {
        return new ApprovalContextBlock(label, template, null);
    }

    private void say(String agentId, String name, String content, TranscriptEntryType type, String phase) {
        gc.getTranscript().add(new TranscriptEntry(agentId, name, content, 0, phase, type, Instant.now(), null, null));
    }

    private static SharedArtifact artifact(String name, ArtifactType type, String content) {
        var a = new SharedArtifact();
        a.setName(name);
        a.setType(type);
        a.setContent(content);
        a.setVersion(2);
        return a;
    }

    @Test
    @DisplayName("no config means no context")
    void noConfigIsNull() {
        assertNull(renderer.render(gc, config, 1, "PHASE"));
        config.setHitlConfig(null);
        assertNull(renderer.render(gc, config, 1, "PHASE"));
    }

    @Test
    @DisplayName("lastByRole joins speaker to role from the member config; the last entry wins")
    void lastByRoleJoinsSpeakerToRole() {
        say("da", "Devil's Advocate", "first objection", TranscriptEntryType.CHALLENGE, "Critique");
        say("writer", "Writer", "the draft", TranscriptEntryType.OPINION, "Draft");
        say("da", "Devil's Advocate", "'40% fewer tickets' compares October to December", TranscriptEntryType.CHALLENGE, "Critique");
        blocks(block("Objection", "{group.lastByRole.DEVIL_ADVOCATE.content}"), block("By type", "{group.lastByType.OPINION.content}"),
                block("By speaker", "{group.lastBySpeaker.writer.displayName}"));

        var ctx = renderer.render(gc, config, 1, "PHASE");

        assertEquals("'40% fewer tickets' compares October to December", ctx.getItems().get(0).getText());
        assertEquals("the draft", ctx.getItems().get(1).getText());
        assertEquals("Writer", ctx.getItems().get(2).getText());
        assertEquals("group", ctx.getItems().get(0).getSource());
    }

    @Test
    @DisplayName("errors and skips never become the 'last' entry")
    void errorsAreNotLast() {
        say("da", "Devil's Advocate", "real objection", TranscriptEntryType.CHALLENGE, "Critique");
        say("da", "Devil's Advocate", "timed out", TranscriptEntryType.ERROR, "Critique");
        blocks(block("Objection", "{group.lastByRole.DEVIL_ADVOCATE.content}"));

        assertEquals("real objection", renderer.render(gc, config, 1, "PHASE").getItems().get(0).getText());
    }

    @Test
    @DisplayName("the transcript is capped at 50 entries and every string at 4,000 characters")
    void caps() {
        for (int i = 0; i < 80; i++) {
            say("writer", "Writer", "entry-" + i, TranscriptEntryType.OPINION, "Draft");
        }
        say("da", "Devil's Advocate", "x".repeat(10_000), TranscriptEntryType.CHALLENGE, "Critique");
        var data = GroupApprovalContextRenderer.group(gc, config, 1, "PHASE", List.of());

        @SuppressWarnings("unchecked")
        var transcript = (List<Map<String, Object>>) data.get("transcript");
        assertEquals(50, transcript.size());
        assertEquals("entry-31", transcript.get(0).get("content"), "the oldest entries fall off");
        assertEquals(4_000, ((String) transcript.get(49).get("content")).length());
    }

    @Test
    @DisplayName("phase, members, question and granularity are exposed")
    void basicShape() {
        blocks(block("Where", "{group.phase.name} ({group.phase.type}) #{group.phase.index} {group.granularity}: {group.question}"),
                block("Who", "{#for m in group.members}{m.displayName}={m.role};{/for}"), block("Reason", "{hitl.reason} {hitl.pauseType}"));

        var ctx = renderer.render(gc, config, 1, "PHASE");

        assertEquals("Synthesis (SYNTHESIS) #1 PHASE: Should we publish the Acme post?", ctx.getItems().get(0).getText());
        assertEquals("Writer=WRITER;Devil's Advocate=DEVIL_ADVOCATE;", ctx.getItems().get(1).getText());
        assertEquals("Requires human approval (PHASE) — phase: Synthesis PHASE", ctx.getItems().get(2).getText());
    }

    @Test
    @DisplayName("tasks awaiting approval are listed with their assignee")
    void tasksAwaitingApproval() {
        var tasks = new SharedTaskList();
        tasks.addTask(new TaskItem("t1", "Publish the post", "to LinkedIn", TaskStatus.AWAITING_APPROVAL, "writer", "Writer", List.of(), null, null,
                false, 1, Instant.now(), null));
        tasks.addTask(new TaskItem("t2", "Other", "unrelated", TaskStatus.PENDING, null, null, List.of(), null, null, false, 1, Instant.now(), null));
        gc.setTaskList(tasks);
        blocks(block("Task", "{#for t in group.tasksAwaitingApproval}{t.subject} -> {t.assignee}\n{/for}"));

        assertEquals("Publish the post -> Writer", renderer.render(gc, config, 1, "TASK").getItems().get(0).getText());
    }

    @Test
    @DisplayName("artifacts: text content, parsed JSON, and an unparseable JSON artifact gives json null")
    void artifacts() throws Exception {
        when(artifactStore.listByGroupConversationId("gc-1")).thenReturn(List.of(artifact("draft", ArtifactType.MARKDOWN, "# Acme post"),
                artifact("claims", ArtifactType.JSON, "[{\"text\":\"3 days\",\"sourceRef\":\"S1\"},{\"text\":\"40%\",\"sourceRef\":\"S2\"}]"),
                artifact("broken", ArtifactType.JSON, "{not json")));
        blocks(block("Draft", "{group.artifacts.draft.content} v{group.artifacts.draft.version} {group.artifacts.draft.status}"),
                block("Claims", "{#for c in group.artifacts.claims.json}• {c.text} [{c.sourceRef}]\n{/for}"),
                block("Broken", "[{group.artifacts.broken.json}]"));

        var ctx = renderer.render(gc, config, 1, "PHASE");

        assertEquals("# Acme post v2 DRAFT", ctx.getItems().get(0).getText());
        assertEquals("• 3 days [S1]\n• 40% [S2]", ctx.getItems().get(1).getText());
        assertEquals("[]", ctx.getItems().get(2).getText());
    }

    @Test
    @DisplayName("a JSON artifact above the parse cap is not parsed: json is null while its capped text is still offered")
    void oversizedJsonArtifactIsNotParsed() throws Exception {
        String big = "[" + "1,".repeat(GroupApprovalContextRenderer.MAX_JSON_PARSE_CHARS / 2) + "1]";
        when(artifactStore.listByGroupConversationId("gc-1")).thenReturn(List.of(artifact("huge", ArtifactType.JSON, big)));
        blocks(block("Huge", "[{group.artifacts.huge.json}]"));

        var ctx = renderer.render(gc, config, 1, "PHASE");

        assertEquals("[]", ctx.getItems().get(0).getText());
    }

    @Test
    @DisplayName("an artifact name with a dash is reachable by key lookup")
    void dashedArtifactName() throws Exception {
        when(artifactStore.listByGroupConversationId("gc-1")).thenReturn(List.of(artifact("post-draft", ArtifactType.TEXT, "dashed content")));
        blocks(block("Bracket", "{group.artifacts.get('post-draft').content}"));

        var ctx = renderer.render(gc, config, 1, "PHASE");

        assertNotNull(ctx);
        assertEquals("dashed content", ctx.getItems().get(0).getText());
    }

    @Test
    @DisplayName("at most 20 artifacts are exposed")
    void artifactCap() throws Exception {
        List<SharedArtifact> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            many.add(artifact("a" + i, ArtifactType.TEXT, "c" + i));
        }
        when(artifactStore.listByGroupConversationId("gc-1")).thenReturn(many);
        var data = GroupApprovalContextRenderer.group(gc, config, 1, "PHASE", many.subList(0, GroupApprovalContextRenderer.MAX_ARTIFACTS));
        assertEquals(20, ((Map<?, ?>) data.get("artifacts")).size());
        blocks(block("Count", "{group.artifacts.size}"));
        assertEquals("20", renderer.render(gc, config, 1, "PHASE").getItems().get(0).getText());
    }

    @Test
    @DisplayName("an unreadable artifact store leaves group.artifacts empty and still renders the rest")
    void artifactStoreFailure() throws Exception {
        when(artifactStore.listByGroupConversationId("gc-1")).thenThrow(new RuntimeException("db down"));
        blocks(block("Q", "{group.question}"), block("A", "{group.artifacts.draft.content}"));

        var ctx = renderer.render(gc, config, 1, "PHASE");

        assertEquals(1, ctx.getItems().size());
        assertEquals("Should we publish the Acme post?", ctx.getItems().get(0).getText());
    }

    @Test
    @DisplayName("secrets in group data are redacted by the shared pipeline")
    void redactedLikeTheRest() {
        say("writer", "Writer", "the key is " + "sk-ant-" + "fixture".repeat(5) + " ok", TranscriptEntryType.OPINION, "Draft");
        blocks(block("Draft", "{group.lastByRole.WRITER.content}"));

        assertFalse(renderer.render(gc, config, 1, "PHASE").getItems().get(0).getText().contains("fixturefixture"));
    }
}
