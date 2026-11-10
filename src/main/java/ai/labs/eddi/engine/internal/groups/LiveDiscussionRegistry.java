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

    /**
     * One entry per running discussion: its live instance and the guard it reads
     * its transcript through, kept together so a single lookup sees both or
     * neither. Two maps let a reader that found the discussion find no guard once a
     * cleanup removed it in between, and an absent guard reads as "show
     * everything".
     */
    private final ConcurrentHashMap<String, Registration> live = new ConcurrentHashMap<>();

    /**
     * A running discussion as one lookup sees it.
     *
     * @param groupConversation
     *            the instance the loop is running with
     * @param signingGuard
     *            the guard it reads its transcript through, or {@code null} when it
     *            was registered without one (then nothing is filtered, as for the
     *            loop itself)
     */
    public record Registration(GroupConversation groupConversation, GroupSigningGuard signingGuard) {

        /** {@link #signingGuard}, empty when there is none. */
        public Optional<GroupSigningGuard> guard() {
            return Optional.ofNullable(signingGuard);
        }
    }

    /**
     * Registers the live instance a discussion loop is about to run with. Called
     * once at the top of {@code executeDiscussion} — which covers both a fresh
     * start and a resume, since {@code GroupHitlCoordinator#resumeDiscussion}
     * re-enters through that same method. A second {@code register} for an id
     * already present simply replaces the entry (the resume path always supplies a
     * freshly-loaded {@code GroupConversation}, never the stale pre-pause one).
     */
    public void register(GroupConversation gc) {
        register(gc, null);
    }

    /**
     * As {@link #register(GroupConversation)}, with the guard the loop reads the
     * transcript through, so a member's tool shows that member what the engine's
     * own prompts would (see {@link #signingGuard}).
     */
    public void register(GroupConversation gc, GroupSigningGuard signingGuard) {
        live.put(gc.getId(), new Registration(gc, signingGuard));
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
     * The guard a running discussion reads its transcript through, or empty when it
     * is not running or was registered without one (then nothing is filtered, as
     * for the loop itself). A caller that also needs the discussion takes both from
     * one {@link #registrationForMember} or {@link #registration} instead: two
     * lookups can straddle an {@link #unregister} and see the discussion but no
     * guard.
     */
    public Optional<GroupSigningGuard> signingGuard(String groupConversationId) {
        return registration(groupConversationId).flatMap(Registration::guard);
    }

    /**
     * The running discussion and its guard, read together; empty when it is not
     * running. Like {@link #get}, never an authorization.
     */
    public Optional<Registration> registration(String groupConversationId) {
        return groupConversationId == null ? Optional.empty() : Optional.ofNullable(live.get(groupConversationId));
    }

    /**
     * As {@link #getForMember}, with the guard of the same registration, so a tool
     * provider gating on the member's verification requirement reads the guard that
     * belongs to the discussion it found.
     */
    public Optional<Registration> registrationForMember(String groupConversationId, String conversationId) {
        if (conversationId == null) {
            return Optional.empty();
        }
        return registration(groupConversationId).filter(registration -> {
            var gc = registration.groupConversation();
            return gc.getMemberConversationIds() != null && gc.getMemberConversationIds().containsValue(conversationId);
        });
    }

    /**
     * The live discussion ONLY if {@code conversationId} is one of its member
     * conversations.
     * <p>
     * {@link #get} must not be used to authorize a tool. The
     * {@code groupConversationId} that reaches a tool provider arrives as a
     * <em>caller-supplied context variable</em> — {@code Conversation}'s context
     * map is stored verbatim as {@code context:*} step data with no reserved-key
     * filter — so any principal who can start a conversation can name any
     * discussion id they like. Gating a write tool on {@code get(id).isPresent()}
     * therefore authorizes nothing: it only asks "does this discussion exist?",
     * which for an enumerable id is always yes.
     * <p>
     * The conversation id, by contrast, is not forgeable in the same way: it is the
     * conversation the caller is demonstrably talking in, and it appears in
     * {@code memberConversationIds} only because the discussion itself put it there
     * when it created that member's turn. Requiring the pair to match is what makes
     * "I am a member of this discussion" a checked claim rather than an asserted
     * one.
     *
     * @param conversationId
     *            the caller's own conversation, from conversation memory
     */
    public Optional<GroupConversation> getForMember(String groupConversationId, String conversationId) {
        return registrationForMember(groupConversationId, conversationId).map(Registration::groupConversation);
    }

    /**
     * Whether {@code conversationId} is a member of the running discussion
     * {@code groupConversationId} and that discussion belongs to {@code groupId} —
     * the check {@code ConversationGroups} applies before it trusts a
     * {@code groupId} found only on an earlier step of a conversation.
     */
    public boolean isLiveMember(String groupConversationId, String conversationId, String groupId) {
        return groupId != null && getForMember(groupConversationId, conversationId)
                .filter(gc -> groupId.equals(gc.getGroupId()))
                .isPresent();
    }

    /**
     * The live instance for a running discussion, or empty if it is not currently
     * running (paused, finished, or never started on this node — group control is
     * per-node, like {@code discussionControls}). Callers must turn an empty result
     * into an actionable error string for the LLM, never an exception.
     * <p>
     * <b>Never use this to authorize a tool</b> — see
     * {@link #getForMember(String, String)}, which explains why existence is not
     * membership.
     */
    public Optional<GroupConversation> get(String groupConversationId) {
        return registration(groupConversationId).map(Registration::groupConversation);
    }
}
