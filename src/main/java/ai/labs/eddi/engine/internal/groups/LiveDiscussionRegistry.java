/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The in-memory {@link GroupConversation} instances the discussion loop
 * currently holds, keyed by group-conversation id (Wave 0, F1).
 * <p>
 * <b>Why this exists.</b> The loop persists a discussion via
 * <em>whole-document</em> updates after each phase boundary
 * ({@code conversationStore.update(gc)}). A tool that mutated the document
 * through a separate store read/write would race that: the loop's next
 * phase-boundary write started from the snapshot it loaded at the top of the
 * phase, so it would silently clobber whatever the tool wrote in between. I5's
 * agent-writable shared task list, I7's runtime recruitment, and I17's shared
 * artifacts all need a member's tool call to change the discussion the loop is
 * currently running — the only race-free way to do that is for the tool to
 * mutate <em>the exact same {@code GroupConversation} instance</em> the loop
 * holds, so the loop's next whole-document write picks up the mutation as part
 * of its own snapshot rather than overwriting it.
 * <p>
 * This depends on V5 (confirmed during Wave 0's verify pass, see
 * {@code docs/changelog.md}): {@code MemberTurnExecutor#executeAgentTurn}
 * always runs a member's turn in-process, through
 * {@code IConversationService#say} directly — never across a process boundary —
 * so a tool invoked during that turn runs on the same JVM as the discussion
 * loop and can hold a live reference into this registry.
 * <p>
 * <b>What this is not.</b> Not a cache — {@link #get} returns nothing for a
 * paused or finished discussion (removed at the end of
 * {@code GroupConversationService#executeDiscussion}'s {@code finally} block,
 * which runs on every exit: normal completion, pause, cancel, or failure), and
 * callers must treat absence as "not currently running" and produce an
 * actionable error string back to the LLM, never throw. Not a substitute for
 * persistence — mutations here are visible to the loop's next persist, but a
 * crash between a tool's mutation and the loop's next
 * {@code conversationStore.update(gc)} loses that mutation exactly like a crash
 * mid-phase already loses an in-flight transcript entry today; this is a
 * documented pre-existing window, not a new one.
 * <p>
 * {@code @ApplicationScoped} rather than a plain constructor-built collaborator
 * — the deliberate exception rule 3.0-4 carves out for new bean dependencies
 * introduced by a feature (this and F5's {@code GroupCostLedger}), as opposed
 * to the R1 extraction collaborators, which are plain classes precisely because
 * ~34 test classes construct {@code GroupConversationService} directly and a
 * constructor-signature change would break every one of them for no functional
 * gain. This bean is field-injected instead
 * ({@code @Inject LiveDiscussionRegistry liveDiscussionRegistry;}, same pattern
 * as {@code attachmentStore}), so those tests are unaffected and simply see
 * {@code null} — every call site here is null-checked accordingly.
 */
@ApplicationScoped
public class LiveDiscussionRegistry {

    private final ConcurrentHashMap<String, GroupConversation> live = new ConcurrentHashMap<>();

    /**
     * Registers the live instance a discussion loop is about to run with. Called
     * once at the top of {@code executeDiscussion} — which covers both a fresh
     * start and a resume, since {@code GroupHitlCoordinator#resumeDiscussion}
     * re-enters through that same method. A second {@code register} for an id
     * already present simply replaces the entry (the resume path always supplies a
     * freshly-loaded {@code GroupConversation}, never the stale pre-pause one).
     */
    public void register(GroupConversation gc) {
        live.put(gc.getId(), gc);
    }

    /**
     * Removes a discussion from the registry. Called from
     * {@code executeDiscussion}'s {@code finally} block, which runs on every exit
     * path — normal completion, pause, cancel, or failure — so a paused or finished
     * discussion is never mistaken for a running one.
     */
    public void unregister(String groupConversationId) {
        live.remove(groupConversationId);
    }

    /**
     * The live instance for a running discussion, or empty if it is not currently
     * running (paused, finished, or never started on this node — group control is
     * per-node, like {@code activeTokens}). Callers resolve this via the
     * {@code groupConversationId} context var and must turn an empty result into an
     * actionable error string for the LLM, never an exception.
     */
    public Optional<GroupConversation> get(String groupConversationId) {
        return Optional.ofNullable(live.get(groupConversationId));
    }
}
