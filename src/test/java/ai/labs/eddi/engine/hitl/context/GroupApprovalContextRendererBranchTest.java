/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.context;

import ai.labs.eddi.configs.groups.ISharedArtifactStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.configs.groups.model.SharedArtifact;
import ai.labs.eddi.configs.groups.model.SharedArtifact.ArtifactType;
import ai.labs.eddi.configs.hitl.model.ApprovalContextBlock;
import ai.labs.eddi.modules.templating.impl.RuntimeTemplateEngineFactory.Settings;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.qute.Engine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The template data of a group approval card when the discussion is sparse —
 * unknown phases, members without roles, unnamed artifacts — and when its
 * artifacts cannot be read.
 */
class GroupApprovalContextRendererBranchTest {

    private ISharedArtifactStore artifactStore;
    private ApprovalContextRenderer base;
    private GroupConversation gc;
    private AgentGroupConfiguration config;

    @BeforeEach
    void setUp() {
        artifactStore = mock(ISharedArtifactStore.class);
        base = new ApprovalContextRenderer(
                new TemplatingEngine(Engine.builder().addDefaults().build(),
                        new Settings(16_000, 2_000, false, true, Settings.DEFAULT_ITERATION_METADATA_PREFIX, 500)),
                m -> new LinkedHashMap<>(), new SimpleMeterRegistry(), 8_000);
        gc = new GroupConversation();
        gc.setId("gc-1");
        gc.setGroupId("group-1");
        config = new AgentGroupConfiguration();
        config.setHitlConfig(new AgentGroupConfiguration.HitlConfig());
    }

    private static SharedArtifact artifact(String name, ArtifactType type, String content) {
        var a = new SharedArtifact();
        a.setName(name);
        a.setType(type);
        a.setContent(content);
        a.setVersion(1);
        return a;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @Test
    void aPhaseIndexOutsideTheConfigFallsBackToThePausedPhaseName() {
        gc.setPausedPhaseName("Review");
        var phase = map(GroupApprovalContextRenderer.group(gc, config, 3, "PHASE", List.of()).get("phase"));
        assertEquals("Review", phase.get("name"));
        assertNull(phase.get("type"));
        assertEquals(3, phase.get("index"));

        config.setPhases(List.of(new DiscussionPhase("Draft", null)));
        var untyped = map(GroupApprovalContextRenderer.group(gc, config, 0, "PHASE", List.of()).get("phase"));
        assertEquals("Draft", untyped.get("name"));
        assertNull(untyped.get("type"));
        assertEquals("Review", map(GroupApprovalContextRenderer.group(gc, config, -1, "PHASE", List.of()).get("phase")).get("name"));
    }

    @Test
    void entriesWithoutSpeakerRoleOrTypeAreListedButNotIndexed() {
        config.setMembers(List.of(new GroupMember("plain", "Plain", 1, null), new GroupMember(null, "Ghost", 2, "GHOST")));
        gc.getTranscript().add(new TranscriptEntry("plain", "Plain", "no role", 0, "P", TranscriptEntryType.OPINION, Instant.now(), null, null));
        gc.getTranscript().add(new TranscriptEntry(null, "Moderator", "no speaker", 0, "P", null, Instant.now(), null, null));
        gc.getTranscript().add(new TranscriptEntry("plain", "Plain", "skipped", 0, "P", TranscriptEntryType.SKIPPED, Instant.now(), null, null));

        var group = GroupApprovalContextRenderer.group(gc, config, 0, "PHASE", List.of());
        assertEquals(3, ((List<?>) group.get("transcript")).size());
        assertTrue(map(group.get("lastByRole")).isEmpty(), "no member has both an id and a role");
        assertEquals(List.of("OPINION"), new ArrayList<>(map(group.get("lastByType")).keySet()));
        assertEquals("no role", map(map(group.get("lastBySpeaker")).get("plain")).get("content"), "a skipped entry is never the last");
        assertEquals(2, ((List<?>) group.get("members")).size());
    }

    @Test
    void withoutMembersOrTranscriptTheListsAreEmpty() {
        gc.setTranscript(null);
        var group = GroupApprovalContextRenderer.group(gc, config, 0, "PHASE", List.of());
        assertTrue(((List<?>) group.get("members")).isEmpty());
        assertTrue(((List<?>) group.get("transcript")).isEmpty());
        assertTrue(((List<?>) group.get("tasksAwaitingApproval")).isEmpty());
    }

    @Test
    void artifactsAreKeyedByNameWithJsonParsedWhenSmallAndValid() {
        var unnamed = artifact(null, ArtifactType.TEXT, "x");
        var untyped = artifact("notes", null, "plain");
        var json = artifact("plan", ArtifactType.JSON, "{\"steps\":2}");
        var broken = artifact("broken", ArtifactType.JSON, "{nope");
        var blank = artifact("blank", ArtifactType.JSON, " ");
        var huge = artifact("huge", ArtifactType.JSON, "[" + "1,".repeat(GroupApprovalContextRenderer.MAX_JSON_PARSE_CHARS / 2) + "1]");

        var artifacts = map(GroupApprovalContextRenderer.group(gc, config, 0, "PHASE", List.of(unnamed, untyped, json, broken, blank, huge))
                .get("artifacts"));
        assertEquals(List.of("notes", "plan", "broken", "blank", "huge"), new ArrayList<>(artifacts.keySet()));
        assertNull(map(artifacts.get("notes")).get("type"));
        assertNull(map(artifacts.get("notes")).get("json"), "only JSON artifacts are parsed");
        assertEquals(Map.of("steps", 2), map(artifacts.get("plan")).get("json"));
        assertNull(map(artifacts.get("broken")).get("json"));
        assertNull(map(artifacts.get("blank")).get("json"));
        assertNull(map(artifacts.get("huge")).get("json"), "too large to parse on the pause commit path");
    }

    private ApprovalContextBlock block(String template) {
        return new ApprovalContextBlock("Artifacts", template, null);
    }

    @Test
    void unreadableArtifactsRenderTheCardWithoutThem() throws Exception {
        config.getHitlConfig().setApprovalContext(List.of(block("{group.artifacts.size} artifacts")));
        when(artifactStore.listByGroupConversationId("gc-1")).thenThrow(new IllegalStateException("db down"));
        var ctx = new GroupApprovalContextRenderer(base, artifactStore).render(gc, config, 0, "PHASE");
        assertEquals("0 artifacts", ctx.getItems().getFirst().getText());

        doReturn(null).when(artifactStore).listByGroupConversationId("gc-1");
        assertEquals("0 artifacts", new GroupApprovalContextRenderer(base, artifactStore).render(gc, config, 0, "PHASE").getItems().getFirst()
                .getText());
        assertEquals("0 artifacts", new GroupApprovalContextRenderer(base, null).render(gc, config, 0, "PHASE").getItems().getFirst().getText());
    }

    @Test
    void atMostTheArtifactCapIsExposed() throws Exception {
        config.getHitlConfig().setApprovalContext(List.of(block("{group.artifacts.size}")));
        List<SharedArtifact> many = new ArrayList<>();
        for (int i = 0; i < GroupApprovalContextRenderer.MAX_ARTIFACTS + 5; i++) {
            many.add(artifact("a" + i, ArtifactType.TEXT, "x"));
        }
        when(artifactStore.listByGroupConversationId("gc-1")).thenReturn(many);
        assertEquals(String.valueOf(GroupApprovalContextRenderer.MAX_ARTIFACTS),
                new GroupApprovalContextRenderer(base, artifactStore).render(gc, config, 0, "PHASE").getItems().getFirst().getText());
    }

    @Test
    void aDiscussionWithoutAnIdDoesNotReadArtifacts() throws Exception {
        gc.setId(null);
        config.getHitlConfig().setApprovalContext(List.of(block("{group.artifacts.size}")));
        assertEquals("0", new GroupApprovalContextRenderer(base, artifactStore).render(gc, config, 0, "PHASE").getItems().getFirst().getText());
    }

    @Test
    void anEmptyBlockListAndAFailingRenderGiveNoContext() {
        config.getHitlConfig().setApprovalContext(List.of());
        assertNull(new GroupApprovalContextRenderer(base, artifactStore).render(gc, config, 0, "PHASE"));
        assertNull(new GroupApprovalContextRenderer(base, artifactStore).render(gc, null, 0, "PHASE"));
        config.getHitlConfig().setApprovalContext(List.of(block("{group.question}")));
        assertNull(new GroupApprovalContextRenderer(base, artifactStore).render(null, config, 0, "PHASE"), "a failure never escapes");
        assertFalse(GroupApprovalContextRenderer.hitl(gc, "TASK").isEmpty());
    }
}
