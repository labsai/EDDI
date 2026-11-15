/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.hitl;

import ai.labs.eddi.configs.hitl.model.ApprovalContextBlock;
import ai.labs.eddi.modules.templating.impl.RuntimeTemplateParseCheck;
import io.quarkus.qute.Expression;
import io.quarkus.qute.Template;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Set;

/**
 * Save-time validation for the approval-context blocks of
 * {@code hitlConfig.approvalContext}, {@code toolApprovals.rules[].context} and
 * the group {@code hitlConfig.approvalContext}. Called from
 * {@link HitlConfigValidation}, which every store and the ZIP import already go
 * through, so a block that would never render is refused with an actionable
 * message instead of producing an empty card at the first pause.
 * <ul>
 * <li>limits: at most {@value #MAX_AGENT_BLOCKS} agent-level,
 * {@value #MAX_RULE_BLOCKS} per-rule and {@value #MAX_GROUP_BLOCKS} group
 * blocks; label 1..{@value #MAX_LABEL_LENGTH} characters; template
 * 1..{@value #MAX_TEMPLATE_LENGTH}; {@code maxChars}
 * {@value #MIN_MAX_CHARS}..{@value #MAX_MAX_CHARS};</li>
 * <li>the template must parse on the restricted runtime engine
 * ({@link RuntimeTemplateParseCheck});</li>
 * <li>the {@code vault}, {@code eddivault}, {@code connection} and
 * {@code caller} namespaces are refused: they are never meaningful to an
 * approver and would print the <em>names</em> of secrets onto a card;</li>
 * <li>{@code call.*} exists only in a rule's blocks; {@code group.*} only on a
 * group pause (warning only elsewhere, because the block simply renders
 * empty).</li>
 * </ul>
 */
public final class ApprovalContextValidation {

    private static final Logger LOGGER = Logger.getLogger(ApprovalContextValidation.class);

    public static final int MAX_AGENT_BLOCKS = 8;
    public static final int MAX_RULE_BLOCKS = 4;
    public static final int MAX_GROUP_BLOCKS = 8;
    public static final int MAX_LABEL_LENGTH = 80;
    public static final int MAX_TEMPLATE_LENGTH = 4000;
    public static final int MIN_MAX_CHARS = 50;
    public static final int MAX_MAX_CHARS = 2900;

    /**
     * Namespaces whose expressions pass through verbatim at render time (secret and
     * connection references): refused in an approver-facing block.
     */
    static final Set<String> REFUSED_NAMESPACES = Set.of("vault", "eddivault", "connection", "caller");

    /** Where the blocks are configured; decides the limit and the data roots. */
    public enum Scope {
        /** {@code hitlConfig.approvalContext} of an agent. */
        AGENT(MAX_AGENT_BLOCKS, false, false),
        /** {@code toolApprovals.rules[].context}. */
        RULE(MAX_RULE_BLOCKS, true, false),
        /** The group's {@code hitlConfig.approvalContext}. */
        GROUP(MAX_GROUP_BLOCKS, false, true);

        private final int maxBlocks;
        private final boolean callAvailable;
        private final boolean groupAvailable;

        Scope(int maxBlocks, boolean callAvailable, boolean groupAvailable) {
            this.maxBlocks = maxBlocks;
            this.callAvailable = callAvailable;
            this.groupAvailable = groupAvailable;
        }
    }

    private ApprovalContextValidation() {
    }

    /**
     * Validates one list of blocks. No-op for {@code null} or empty.
     *
     * @param fieldPath
     *            prefix for the messages, e.g. {@code "hitlConfig.approvalContext"}
     * @throws IllegalArgumentException
     *             naming the block index and the rule it broke
     */
    public static void validate(List<ApprovalContextBlock> blocks, String fieldPath, Scope scope) {
        if (blocks == null || blocks.isEmpty()) {
            return;
        }
        if (blocks.size() > scope.maxBlocks) {
            throw new IllegalArgumentException(fieldPath + " has " + blocks.size() + " blocks; at most " + scope.maxBlocks
                    + " are allowed — an approval card is read in seconds, keep only what the decision needs");
        }
        for (int i = 0; i < blocks.size(); i++) {
            validateBlock(blocks.get(i), fieldPath + "[" + i + "]", scope);
        }
    }

    private static void validateBlock(ApprovalContextBlock block, String path, Scope scope) {
        if (block == null) {
            throw new IllegalArgumentException(path + " must not be null");
        }
        String label = block.getLabel();
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException(path + ".label must not be blank — it is the heading the approver reads");
        }
        if (label.length() > MAX_LABEL_LENGTH) {
            throw new IllegalArgumentException(path + ".label exceeds the maximum length of " + MAX_LABEL_LENGTH + " characters");
        }
        String template = block.getTemplate();
        if (template == null || template.isBlank()) {
            throw new IllegalArgumentException(path + ".template must not be blank");
        }
        if (template.length() > MAX_TEMPLATE_LENGTH) {
            throw new IllegalArgumentException(
                    path + ".template exceeds the maximum length of " + MAX_TEMPLATE_LENGTH + " characters");
        }
        Integer maxChars = block.getMaxChars();
        if (maxChars != null && (maxChars < MIN_MAX_CHARS || maxChars > MAX_MAX_CHARS)) {
            throw new IllegalArgumentException(path + ".maxChars must be between " + MIN_MAX_CHARS + " and " + MAX_MAX_CHARS
                    + ", got " + maxChars);
        }
        String parseError = RuntimeTemplateParseCheck.parseError(template);
        if (parseError != null) {
            throw new IllegalArgumentException(path + ".template does not parse: " + parseError);
        }
        Template parsed = RuntimeTemplateParseCheck.parse(template);
        for (Expression expression : parsed.getExpressions()) {
            checkExpression(expression, path, scope);
        }
    }

    private static void checkExpression(Expression expression, String path, Scope scope) {
        String namespace = expression.getNamespace();
        if (namespace != null) {
            if (REFUSED_NAMESPACES.contains(namespace)) {
                throw new IllegalArgumentException(path + ".template uses the '" + namespace + ":' namespace ("
                        + expression.toOriginalString() + "), which is not allowed in approval context — it would put the name of"
                        + " a secret or connection on a card and is never meaningful to an approver");
            }
        } else if (!expression.getParts().isEmpty()) {
            String root = expression.getParts().get(0).getName();
            if ("call".equals(root) && !scope.callAvailable) {
                throw new IllegalArgumentException(path + ".template uses 'call.*' (" + expression.toOriginalString()
                        + "), which exists only in a tool-approval rule's context blocks (toolApprovals.rules[].context),"
                        + " where it is the one gated call the rule matched");
            }
            if ("group".equals(root) && !scope.groupAvailable) {
                LOGGER.warnf("%s.template uses 'group.*' (%s), which exists only on a group pause; the block renders empty here",
                        path, expression.toOriginalString());
            }
        }
        for (Expression.Part part : expression.getParts()) {
            if (part.isVirtualMethod()) {
                for (Expression parameter : part.asVirtualMethod().getParameters()) {
                    checkExpression(parameter, path, scope);
                }
            }
        }
    }
}
