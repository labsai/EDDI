/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.hitl;

import ai.labs.eddi.configs.hitl.model.GroupHitlReviewConfig;
import ai.labs.eddi.configs.hitl.model.HitlReviewConfig;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Save-time validation of {@code hitlConfig.review} (agents and groups) and of
 * {@code toolApprovals.rules[].approvalCategory}. Called from
 * {@link HitlConfigValidation}; every refusal names the field and the rule.
 *
 * @since 6.7.0
 */
public final class HitlReviewConfigValidation {

    /** An approval category: lower-case, starts alphanumeric, at most 128 chars. */
    public static final Pattern CATEGORY = Pattern.compile("^[a-z0-9][a-z0-9._:/\\-]{0,127}$");
    /** A reason code: upper-case identifier, 2..40 chars. */
    public static final Pattern REASON_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");
    /** Upper bound of a configured reason-code vocabulary. */
    public static final int MAX_REASON_CODES = 30;
    /** Upper bound of one reason-code label. */
    public static final int MAX_REASON_LABEL_LENGTH = 120;
    /** Upper bound of a {@code phaseCategories} key (a phase name). */
    public static final int MAX_PHASE_NAME_LENGTH = 200;

    private HitlReviewConfigValidation() {
    }

    /** Validates an agent's {@code hitlConfig.review}; no-op when absent. */
    public static void validate(HitlReviewConfig review, String fieldPath) {
        if (review == null) {
            return;
        }
        validateMaxEditChars(review.getMaxEditChars(), fieldPath + ".maxEditChars");
        validateCategory(review.getApprovalCategory(), fieldPath + ".approvalCategory");
        validateReasonCodes(review.getReasonCodes(), fieldPath + ".reasonCodes");
    }

    /** Validates a group's {@code hitlConfig.review}; no-op when absent. */
    public static void validate(GroupHitlReviewConfig review, String fieldPath) {
        if (review == null) {
            return;
        }
        validateMaxEditChars(review.getMaxEditChars(), fieldPath + ".maxEditChars");
        validateReasonCodes(review.getReasonCodes(), fieldPath + ".reasonCodes");
        Map<String, String> phaseCategories = review.getPhaseCategories();
        if (phaseCategories == null) {
            return;
        }
        for (var entry : phaseCategories.entrySet()) {
            String phase = entry.getKey();
            if (phase == null || phase.isBlank() || phase.length() > MAX_PHASE_NAME_LENGTH) {
                throw new IllegalArgumentException(fieldPath + ".phaseCategories has a key that is blank or longer than "
                        + MAX_PHASE_NAME_LENGTH + " characters — keys are phase names");
            }
            if (entry.getValue() == null) {
                throw new IllegalArgumentException(fieldPath + ".phaseCategories['" + phase + "'] must not be null");
            }
            validateCategory(entry.getValue(), fieldPath + ".phaseCategories['" + phase + "']");
        }
    }

    /** Validates one approval category; no-op when absent. */
    public static void validateCategory(String category, String fieldPath) {
        if (category == null) {
            return;
        }
        if (!CATEGORY.matcher(category).matches()) {
            throw new IllegalArgumentException(fieldPath + " '" + abbreviate(category)
                    + "' is not a valid approval category: lower-case letters, digits and . _ : / -, starting with a letter"
                    + " or digit, at most 128 characters (e.g. \"github.reply\")");
        }
    }

    private static void validateMaxEditChars(Integer maxEditChars, String fieldPath) {
        if (maxEditChars != null && (maxEditChars < 1 || maxEditChars > HitlReviewConfig.MAX_EDIT_CHARS_CEILING)) {
            throw new IllegalArgumentException(
                    fieldPath + " must be between 1 and " + HitlReviewConfig.MAX_EDIT_CHARS_CEILING + ", got " + maxEditChars);
        }
    }

    private static void validateReasonCodes(Map<String, String> reasonCodes, String fieldPath) {
        if (reasonCodes == null) {
            return;
        }
        if (reasonCodes.isEmpty()) {
            throw new IllegalArgumentException(fieldPath + " is empty: name at least one code, or remove it to use the built-in"
                    + " vocabulary");
        }
        if (reasonCodes.size() > MAX_REASON_CODES) {
            throw new IllegalArgumentException(fieldPath + " has " + reasonCodes.size() + " codes; at most " + MAX_REASON_CODES
                    + " are allowed");
        }
        for (var entry : reasonCodes.entrySet()) {
            String code = entry.getKey();
            if (code == null || !REASON_CODE.matcher(code).matches()) {
                throw new IllegalArgumentException(fieldPath + " key '" + abbreviate(code)
                        + "' is not a valid reason code: upper-case letters, digits and _, starting with a letter, 2 to 40"
                        + " characters (e.g. \"FALSE_CLAIM\")");
            }
            String label = entry.getValue();
            if (label == null || label.isBlank() || label.length() > MAX_REASON_LABEL_LENGTH) {
                throw new IllegalArgumentException(fieldPath + "['" + code + "'] needs a label of 1 to " + MAX_REASON_LABEL_LENGTH
                        + " characters");
            }
        }
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "null";
        }
        return value.length() > 40 ? value.substring(0, 40) + "…" : value;
    }
}
