/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import ai.labs.eddi.configs.groups.ArtifactValidators;
import ai.labs.eddi.configs.groups.ISharedArtifactStore;
import ai.labs.eddi.configs.groups.ISharedArtifactStore.ArtifactGoneException;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ArtifactConfig;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.ArtifactChange;
import ai.labs.eddi.configs.groups.model.SharedArtifact;
import ai.labs.eddi.configs.groups.model.SharedArtifact.ArtifactStatus;
import ai.labs.eddi.configs.groups.model.SharedArtifact.ArtifactType;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.internal.groups.LiveDiscussionRegistry;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.inject.Vetoed;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Lets group members create and co-edit shared artifacts (I17, blackboard-lite)
 * — typed documents that live <em>outside</em> the dialogue, so structured work
 * stops being prose the next agent re-parses.
 * <p>
 * <b>Writes go through the artifact store directly.</b> Artifacts have their
 * own collection, so the F1 rule that task-list writes must mutate the live
 * {@link GroupConversation} instance (or be clobbered by the loop's next
 * whole-document persist) does not apply. The live registry is still consulted
 * — a write against a finished or paused discussion is refused, and the
 * creation/edit is announced through the live instance's artifact-change queue
 * so the discussion loop can fire {@code artifact_updated}.
 * <p>
 * <b>Concurrency is deterministic CAS-and-retry, not an LLM merge.</b> Every
 * update presents the version it read; a stale version gets a "re-read and
 * merge" sentence and the model retries against fresh content. Creation
 * (duplicate-name + count cap) is a check-then-act, so it synchronizes on the
 * live discussion instance — PARALLEL phases genuinely run members at once.
 * <p>
 * Constructed per-turn with runtime values — NOT a CDI bean. {@code @Vetoed} is
 * load-bearing for the same deployment-failure reason as
 * {@link GroupTaskTools}.
 *
 * @author ginccc
 */
@Vetoed
public class ArtifactTools {

    private static final Logger LOGGER = Logger.getLogger(ArtifactTools.class);

    static final int MAX_NAME_LENGTH = 200;

    private final LiveDiscussionRegistry registry;
    private final String groupConversationId;
    private final ArtifactConfig config;
    private final String agentId;
    private final ISharedArtifactStore artifactStore;

    public ArtifactTools(LiveDiscussionRegistry registry, String groupConversationId, ArtifactConfig config, String agentId,
            ISharedArtifactStore artifactStore) {
        this.registry = registry;
        this.groupConversationId = groupConversationId;
        this.config = config;
        this.agentId = agentId;
        this.artifactStore = artifactStore;
    }

    @Tool("Create a new shared artifact — a named document the whole team can read and propose changes to. "
            + "Use listArtifacts first to avoid duplicating one that already exists.")
    public String createArtifact(
                                 @P("Short unique name, how the team refers to this artifact") String name,
                                 @P("Content type: TEXT, MARKDOWN or JSON") String type,
                                 @P("The initial content") String content) {

        GroupConversation gc = liveDiscussion();
        if (gc == null) {
            return "This discussion is no longer accepting artifact changes (it has finished or is paused).";
        }
        if (name == null || name.isBlank()) {
            return "An artifact needs a name.";
        }
        String trimmedName = name.trim();
        if (trimmedName.length() > MAX_NAME_LENGTH) {
            return "The name is too long (%d chars, max %d). Put the detail in the content.".formatted(trimmedName.length(), MAX_NAME_LENGTH);
        }
        ArtifactType artifactType = parseType(type);
        if (artifactType == null) {
            return "Unknown artifact type \"%s\". Use TEXT, MARKDOWN or JSON.".formatted(type);
        }
        String rejection = checkContent(content);
        if (rejection != null) {
            return rejection;
        }

        // Check-then-act (duplicate name + count cap) under one monitor: the live
        // instance is the one object every member of this discussion shares in
        // this JVM, so it is the natural lock for creation races. Updates need no
        // lock — the store's version CAS decides those.
        synchronized (gc) {
            List<SharedArtifact> existing;
            try {
                existing = artifactStore.listByGroupConversationId(groupConversationId);
            } catch (IResourceStore.ResourceStoreException e) {
                LOGGER.warnf("Could not list artifacts for %s — refusing the create: %s", groupConversationId, e.getMessage());
                return "The artifact store is unavailable right now; try again next turn.";
            }
            if (existing.stream().anyMatch(a -> trimmedName.equalsIgnoreCase(a.getName()))) {
                return "An artifact named \"%s\" already exists. Read it with readArtifact and use proposeArtifactUpdate to change it."
                        .formatted(trimmedName);
            }
            if (existing.size() >= config.maxArtifactsPerDiscussion()) {
                return "This discussion already has %d artifacts, which is the limit. Update an existing one instead of creating more."
                        .formatted(existing.size());
            }

            var artifact = new SharedArtifact();
            artifact.setGroupConversationId(groupConversationId);
            artifact.setOwnerUserId(gc.getUserId());
            artifact.setName(trimmedName);
            artifact.setType(artifactType);
            artifact.setContent(content);
            artifact.setVersion(1);
            artifact.setLastEditorAgentId(agentId);
            artifact.setStatus(ArtifactStatus.DRAFT);
            artifact.setCreatedAt(Instant.now());
            artifact.setUpdatedAt(artifact.getCreatedAt());
            try {
                artifactStore.create(artifact);
            } catch (IResourceStore.ResourceStoreException e) {
                LOGGER.warnf("Could not create artifact '%s' for %s: %s", trimmedName, groupConversationId, e.getMessage());
                return "The artifact store is unavailable right now; try again next turn.";
            }

            gc.queueArtifactChange(new ArtifactChange(artifact.getId(), trimmedName, artifactType.name(), 1, agentId,
                    ArtifactStatus.DRAFT.name(), true));
            LOGGER.infof("Agent '%s' created artifact '%s' (v1) on group conversation %s", agentId, trimmedName, groupConversationId);
            return "Created artifact \"%s\" (v1). The team can read it with readArtifact and change it with proposeArtifactUpdate."
                    .formatted(trimmedName);
        }
    }

    @Tool("Read a shared artifact's current content and version. You need the version to propose an update.")
    public String readArtifact(@P("The artifact's name (or id)") String nameOrId) {
        SharedArtifact artifact = resolve(nameOrId);
        if (artifact == null) {
            return "No artifact named \"%s\" here. Use listArtifacts to see what exists.".formatted(nameOrId != null ? nameOrId.trim() : "");
        }
        return "Artifact \"%s\" (%s, %s, v%d, last edited by %s):\n%s".formatted(
                artifact.getName(), artifact.getType(), artifact.getStatus(), artifact.getVersion(),
                artifact.getLastEditorAgentId() != null ? artifact.getLastEditorAgentId() : "unknown",
                artifact.getContent() != null ? artifact.getContent() : "");
    }

    @Tool("Propose new content for a shared artifact. You must pass the version you READ — if someone changed the "
            + "artifact since, your update is rejected and you re-read, merge your change into theirs, and retry.")
    public String proposeArtifactUpdate(
                                        @P("The artifact's name (or id)") String nameOrId,
                                        @P("The complete new content (it replaces the old content)") String content,
                                        @P("The version you read, from readArtifact") long expectedVersion,
                                        @P(value = "Pass true to freeze the artifact as FINAL after this update. "
                                                + "A FINAL artifact accepts no further updates.",
                                           required = false) Boolean markFinal) {

        GroupConversation gc = liveDiscussion();
        if (gc == null) {
            return "This discussion is no longer accepting artifact changes (it has finished or is paused).";
        }
        SharedArtifact artifact = resolve(nameOrId);
        if (artifact == null) {
            return "No artifact named \"%s\" here. Use listArtifacts to see what exists.".formatted(nameOrId != null ? nameOrId.trim() : "");
        }
        if (artifact.getStatus() == ArtifactStatus.FINAL) {
            return "Artifact \"%s\" is FINAL and accepts no further updates.".formatted(artifact.getName());
        }
        String rejection = checkContent(content);
        if (rejection != null) {
            return rejection;
        }
        if (artifact.getVersion() != expectedVersion) {
            return staleVersion(artifact.getName(), artifact.getVersion());
        }

        artifact.applyEdit(content, agentId, Instant.now());
        if (Boolean.TRUE.equals(markFinal)) {
            artifact.setStatus(ArtifactStatus.FINAL);
        }
        try {
            artifactStore.updateIfVersion(artifact, expectedVersion);
        } catch (IResourceStore.ResourceModifiedException e) {
            // Lost the race after our read — report the CURRENT version so the
            // retry is usable. A failed re-read still yields the retry instruction.
            long nowVersion = currentVersionOf(artifact.getId());
            return nowVersion > 0
                    ? staleVersion(artifact.getName(), nowVersion)
                    : "Artifact \"%s\" changed since you read it; re-read and merge your change.".formatted(artifact.getName());
        } catch (ArtifactGoneException e) {
            return "Artifact \"%s\" no longer exists.".formatted(artifact.getName());
        } catch (IResourceStore.ResourceStoreException e) {
            LOGGER.warnf("Could not update artifact '%s' for %s: %s", artifact.getName(), groupConversationId, e.getMessage());
            return "The artifact store is unavailable right now; try again next turn.";
        }

        gc.queueArtifactChange(new ArtifactChange(artifact.getId(), artifact.getName(), artifact.getType().name(),
                artifact.getVersion(), agentId, artifact.getStatus().name(), false));
        LOGGER.infof("Agent '%s' updated artifact '%s' to v%d on group conversation %s",
                agentId, artifact.getName(), artifact.getVersion(), groupConversationId);
        return "Updated \"%s\" to v%d.%s".formatted(artifact.getName(), artifact.getVersion(),
                artifact.getStatus() == ArtifactStatus.FINAL ? " It is now FINAL." : "");
    }

    @Tool("List this discussion's shared artifacts with their type, status and current version.")
    public String listArtifacts() {
        List<SharedArtifact> artifacts;
        try {
            artifacts = artifactStore.listByGroupConversationId(groupConversationId);
        } catch (IResourceStore.ResourceStoreException e) {
            return "The artifact store is unavailable right now; try again next turn.";
        }
        if (artifacts.isEmpty()) {
            return "No artifacts yet. Create one with createArtifact.";
        }
        var sb = new StringBuilder("Shared artifacts:\n");
        for (SharedArtifact a : artifacts) {
            sb.append("- \"").append(a.getName()).append("\" (").append(a.getType()).append(", ").append(a.getStatus())
                    .append(", v").append(a.getVersion()).append(')');
            if (a.getLastEditorAgentId() != null) {
                sb.append(" — last edited by ").append(a.getLastEditorAgentId());
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** The plan-specified stale-CAS sentence, verbatim shape. */
    private static String staleVersion(String name, long currentVersion) {
        return "Artifact \"%s\" changed since you read it (now v%d); re-read and merge your change.".formatted(name, currentVersion);
    }

    /**
     * Resolves an artifact by name or id — but only among THIS discussion's
     * artifacts. Never a raw store read of a caller-supplied id: the id is
     * model-controlled text, and resolving it globally would read another
     * discussion's artifact.
     */
    private SharedArtifact resolve(String nameOrId) {
        if (nameOrId == null || nameOrId.isBlank()) {
            return null;
        }
        String wanted = nameOrId.trim();
        List<SharedArtifact> artifacts;
        try {
            artifacts = artifactStore.listByGroupConversationId(groupConversationId);
        } catch (IResourceStore.ResourceStoreException e) {
            LOGGER.warnf("Could not resolve artifact '%s' for %s: %s", wanted, groupConversationId, e.getMessage());
            return null;
        }
        return artifacts.stream()
                .filter(a -> wanted.equalsIgnoreCase(a.getName()) || wanted.equals(a.getId()))
                .findFirst().orElse(null);
    }

    /** Size cap first (cheap), then the config's declarative validator chain. */
    private String checkContent(String content) {
        if (content == null || content.isBlank()) {
            return "An artifact needs content.";
        }
        int bytes = SharedArtifact.contentBytes(content);
        if (bytes > SharedArtifact.MAX_CONTENT_BYTES) {
            // Ceil, not truncate: MAX+1 bytes must not read "256 KB is over the
            // 256 KB limit" — the model retries at the same size on that sentence.
            return "The content is %d KB, over the %d KB limit for a single artifact. Split it or shorten it."
                    .formatted(Math.ceilDiv(bytes, 1024), SharedArtifact.MAX_CONTENT_BYTES / 1024);
        }
        return ArtifactValidators.firstRejection(config.validators(), content);
    }

    private static ArtifactType parseType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        try {
            return ArtifactType.valueOf(type.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Live-instance lookup for writes. Authorization happened at assembly time (the
     * provider resolves membership via {@code getForMember}); this lookup only
     * answers "is the discussion still running", so a paused or finished discussion
     * refuses instead of accepting a write nobody will announce.
     */
    private GroupConversation liveDiscussion() {
        return registry.get(groupConversationId).orElse(null);
    }

    /** The stored artifact's current version, or 0 when unreadable. */
    private long currentVersionOf(String artifactId) {
        try {
            return artifactStore.read(artifactId).getVersion();
        } catch (Exception e) {
            return 0;
        }
    }
}
