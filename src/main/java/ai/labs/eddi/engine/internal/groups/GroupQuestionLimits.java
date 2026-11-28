/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.engine.api.IRestGroupConversation;

/**
 * The ceilings on what a caller may ask a group, checked by hand on every
 * surface that starts discussion work: the REST resource
 * ({@code RestGroupConversation}) and the MCP tools ({@code McpGroupTools}).
 * <p>
 * The ceilings themselves are declared once, as Bean Validation constraints on
 * {@link IRestGroupConversation}. Quarkus is expected to honour those on the
 * implementing resource, but this deployment has no test that proves it (and
 * one cannot be written without booting the HTTP layer), so a wiring regression
 * would leave every ceiling silently inert while looking enforced. An MCP tool
 * calls the service in-process and never passes a validator at all. A group
 * question is fanned out to every member agent in every phase, at the group
 * owner's cost, which makes an unbounded one an amplification surface: cheap to
 * re-check here, independent of the interceptor being wired, and the same
 * answer whichever surface it arrived on.
 */
public final class GroupQuestionLimits {

    private GroupQuestionLimits() {
    }

    /**
     * A question for the whole group: a discussion, a streamed discussion or a
     * continuation round.
     *
     * @param userId
     *            the caller-named owner, or {@code null} when none was named
     * @return a client-safe rejection reason, or {@code null} when the input is
     *         acceptable
     */
    public static String rejectionReason(String question, String userId) {
        if (blank(question)) {
            return "'question' is required.";
        }
        if (question.length() > IRestGroupConversation.MAX_QUESTION_CHARS) {
            return "'question' must be at most " + IRestGroupConversation.MAX_QUESTION_CHARS + " characters.";
        }
        if (userId != null && userId.length() > IRestGroupConversation.MAX_IDENTIFIER_CHARS) {
            return "'userId' must be at most " + IRestGroupConversation.MAX_IDENTIFIER_CHARS + " characters.";
        }
        return null;
    }

    /**
     * A follow-up question to one member: both fields are required (a missing
     * target otherwise fails during member resolution, as an internal error rather
     * than a refusal), then the {@link #rejectionReason(String, String) group
     * question's} ceilings, then the identifier ceiling on the target.
     *
     * @return a client-safe rejection reason, or {@code null} when the input is
     *         acceptable
     */
    public static String followUpRejectionReason(String question, String targetAgentId, String userId) {
        if (blank(question) || blank(targetAgentId)) {
            return "Both 'question' and 'targetAgentId' are required.";
        }
        String rejection = rejectionReason(question, userId);
        if (rejection == null && targetAgentId.length() > IRestGroupConversation.MAX_IDENTIFIER_CHARS) {
            rejection = "'targetAgentId' must be at most " + IRestGroupConversation.MAX_IDENTIFIER_CHARS + " characters.";
        }
        return rejection;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
