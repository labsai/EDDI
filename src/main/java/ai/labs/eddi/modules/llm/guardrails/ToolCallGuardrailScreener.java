/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.guardrails;

import ai.labs.eddi.modules.llm.model.LlmConfiguration.GuardrailConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Screens one text — one field of a tool call's arguments — with one
 * {@code toolCallGuardrails} entry, on the {@link GuardrailPhase#TOOL_CALL}
 * phase. The tool-call counterpart of {@link GuardrailService#screenInput}: the
 * same type resolution, evaluation, {@code failMode} and outcome recording
 * (shared with the service, not copied), with two differences that come from
 * what a tool call is.
 * <ul>
 * <li>{@code reprompt} is an outcome of its own, not a block: the call does not
 * run, and the model is told, through the call's result, what to correct — the
 * author's fix text, never the screened text.</li>
 * <li>{@link Mode#BLOCK_ONLY} screens a call a human has already approved:
 * nothing is rewritten after approval, so any finding that would change or stop
 * the call refuses it.</li>
 * </ul>
 * Stateless; built per {@link GuardrailService}.
 *
 * @since 6.7.0
 */
public final class ToolCallGuardrailScreener {

    /**
     * The audit reason of a model-backed check skipped because the run's budget of
     * them is spent.
     */
    public static final String REASON_MODEL_CAP = "tool_guardrail_model_cap";

    /** The audit reason of arguments that are not JSON. */
    public static final String REASON_UNPARSEABLE = "arguments_unparseable";

    /** The audit reason of arguments too large to screen. */
    public static final String REASON_TOO_LARGE = "arguments_too_large";

    /**
     * The reason of a rewrite that could not be applied (its target is not a
     * string).
     */
    public static final String REASON_REWRITE_NOT_APPLICABLE = "rewrite_not_applicable";

    /** Default corrective text of a tool-call {@code reprompt}. */
    static final String DEFAULT_CORRECTIVE = "This tool call was not run: its arguments did not meet the policy ({reason}). "
            + "Correct the arguments and call the tool again.";

    /**
     * What a reason, an issue code or a type must look like to be repeated to the
     * model or recorded — EDDI and author vocabulary. Anything else is replaced, so
     * a guardrail bean that breaks the "never echo the screened text" contract
     * cannot carry that text out through a finding.
     */
    private static final Pattern SAFE_TOKEN = Pattern.compile("[a-z0-9_:,.\\-]{1,120}");

    /** How a text is screened. */
    public enum Mode {
        /** The live path: block, redact, reprompt and warn as configured. */
        LIVE,
        /**
         * After approval: a {@code warn} is recorded, every other finding refuses the
         * call.
         */
        BLOCK_ONLY
    }

    /** What the screen decided. */
    public enum Decision {
        /** Nothing found, or a {@code warn} recorded. */
        ALLOW,
        /** {@code redact}: {@link Result#text()} replaces the field. */
        REWRITE,
        /** The call does not run. */
        BLOCK,
        /** The call does not run, and the model is told what to correct. */
        REPROMPT
    }

    /**
     * The outcome of one screen.
     *
     * @param decision
     *            what happens to the call
     * @param text
     *            REWRITE: the new field text; BLOCK and REPROMPT: the guardrail's
     *            rewrite when it offered one (applied to the transcript, so a
     *            blocked call does not keep what was found in it), else null
     * @param reason
     *            the finding's reason, safe to show and record
     * @param issues
     *            the issue codes found (author-declared), safe to show and record
     * @param message
     *            BLOCK: the block message; REPROMPT: the corrective text; else null
     * @param costUsd
     *            what evaluating the guardrail cost
     * @param undecided
     *            the decision came from {@code failMode}, not from a finding
     */
    public record Result(Decision decision, String text, String reason, List<String> issues, String message, double costUsd,
            boolean undecided) {

        static Result allow(double cost) {
            return new Result(Decision.ALLOW, null, "", List.of(), null, cost, false);
        }

        /** True when the call must not run. */
        public boolean refuses() {
            return decision == Decision.BLOCK || decision == Decision.REPROMPT;
        }
    }

    /** Admits the model-backed checks of one loop run. */
    @FunctionalInterface
    public interface ModelCheckBudget {
        /** Takes one model-backed check; false when none is left. */
        boolean tryAcquire();
    }

    private final GuardrailService service;

    public ToolCallGuardrailScreener(GuardrailService service) {
        this.service = service;
    }

    /**
     * Whether this entry's type exists and runs on tool-call arguments. An entry
     * that does not is refused at deploy time.
     */
    public boolean supports(GuardrailConfig config) {
        return service.resolve(config, GuardrailPhase.TOOL_CALL) != null;
    }

    /**
     * Whether this entry is a costly or non-deterministic check (a model call): the
     * guardrails that declare {@link ContentGuardrail#callsModel()}. An unknown
     * type is not.
     */
    public boolean isModelBacked(GuardrailConfig config) {
        ContentGuardrail guardrail = service.resolve(config, GuardrailPhase.TOOL_CALL);
        return guardrail != null && guardrail.callsModel();
    }

    /**
     * Records a finding that no guardrail evaluation produced — arguments that
     * could not be parsed or are too large to screen — exactly as an evaluation
     * that could not decide: {@code failMode} decides between refusing and a
     * {@code warn}.
     */
    public Result undecided(GuardrailConfig config, String reason, Consumer<GuardrailOutcome> onOutcome) {
        GuardrailAction action = GuardrailAction.parse(config.getAction());
        if (GuardrailService.failsClosed(config, action)) {
            service.emit(onOutcome, new GuardrailOutcome(config.getType(), GuardrailPhase.TOOL_CALL, GuardrailAction.BLOCK, reason, 0));
            return new Result(Decision.BLOCK, null, reason, List.of(), blockMessage(config), 0.0, true);
        }
        service.emit(onOutcome, new GuardrailOutcome(config.getType(), GuardrailPhase.TOOL_CALL, GuardrailAction.WARN, reason, 0));
        return Result.allow(0.0);
    }

    /**
     * Refuses a call for a reason found outside any evaluation — a rewrite that
     * could not be applied — recorded as a {@code block}.
     */
    public Result refuse(GuardrailConfig config, String reason, Consumer<GuardrailOutcome> onOutcome) {
        service.emit(onOutcome, new GuardrailOutcome(config.getType(), GuardrailPhase.TOOL_CALL, GuardrailAction.BLOCK, reason, 0));
        return new Result(Decision.BLOCK, null, reason, List.of(), blockMessage(config), 0.0, false);
    }

    /**
     * Screens {@code text} with one entry.
     *
     * @param repromptsSoFar
     *            how many reprompts this entry has already sent in the turn
     * @param budget
     *            asked before a model-backed check; null admits every check
     */
    public Result screen(GuardrailConfig config, String text, GuardrailContext context, int repromptsSoFar, Mode mode, ModelCheckBudget budget,
                         Consumer<GuardrailOutcome> onOutcome) {
        ContentGuardrail guardrail = service.resolve(config, GuardrailPhase.TOOL_CALL);
        if (guardrail == null) {
            return Result.allow(0.0);
        }
        GuardrailAction action = GuardrailAction.parse(config.getAction());
        GuardrailService.Evaluation evaluation;
        if (guardrail.callsModel() && budget != null && !budget.tryAcquire()) {
            evaluation = new GuardrailService.Evaluation(null, REASON_MODEL_CAP, 0.0, null);
        } else {
            evaluation = service.evaluate(guardrail, text != null ? text : "",
                    context.with(GuardrailPhase.TOOL_CALL, config.getParameters(), context.messages()));
        }
        double cost = 0.0;
        GuardrailVerdict verdict;
        if (evaluation.failed()) {
            cost += evaluation.failureCostUsd();
            String reason = safe(evaluation.failureReason(), GuardrailService.REASON_ERROR);
            if (GuardrailService.failsClosed(config, action)) {
                service.emit(onOutcome,
                        new GuardrailOutcome(config.getType(), GuardrailPhase.TOOL_CALL, GuardrailAction.BLOCK, reason, repromptsSoFar));
                return new Result(Decision.BLOCK, null, reason, List.of(), blockMessage(config), cost, true);
            }
            if (evaluation.partial() == null) {
                service.emit(onOutcome,
                        new GuardrailOutcome(config.getType(), GuardrailPhase.TOOL_CALL, GuardrailAction.WARN, reason, repromptsSoFar));
                return Result.allow(cost);
            }
            // Failing open, but it found something before giving up: act on that.
            verdict = evaluation.partial();
        } else {
            verdict = evaluation.verdict();
        }
        cost += verdict.costUsd();
        if (!verdict.triggered()) {
            return Result.allow(cost);
        }
        String reason = safe(verdict.reason(), safe(config.getType(), "guardrail"));
        List<String> issues = safeIssues(verdict.issues());
        String rewrite = verdict.kind() == GuardrailVerdict.Kind.REWRITE ? verdict.text() : null;
        if (action == GuardrailAction.WARN) {
            service.emit(onOutcome, new GuardrailOutcome(config.getType(), GuardrailPhase.TOOL_CALL, GuardrailAction.WARN, reason, repromptsSoFar));
            return Result.allow(cost);
        }
        if (mode == Mode.BLOCK_ONLY) {
            // Approved: nothing is rewritten after approval, and there is no one to
            // reprompt — any finding that would change or stop the call refuses it.
            service.emit(onOutcome, new GuardrailOutcome(config.getType(), GuardrailPhase.TOOL_CALL, GuardrailAction.BLOCK, reason, repromptsSoFar));
            return new Result(Decision.BLOCK, null, reason, issues, blockMessage(config), cost, false);
        }
        switch (action) {
            case REDACT -> {
                service.emit(onOutcome,
                        new GuardrailOutcome(config.getType(), GuardrailPhase.TOOL_CALL, GuardrailAction.REDACT, reason, repromptsSoFar));
                return new Result(Decision.REWRITE, rewrite != null ? rewrite : blockMessage(config), reason, issues, null, cost, false);
            }
            case REPROMPT -> {
                if (repromptsSoFar >= config.effectiveMaxRetries() || repromptsSoFar >= GuardrailConfig.MAX_RETRIES_CEILING) {
                    service.emit(onOutcome,
                            new GuardrailOutcome(config.getType(), GuardrailPhase.TOOL_CALL, GuardrailAction.BLOCK, reason, repromptsSoFar));
                    return new Result(Decision.BLOCK, rewrite, reason, issues, blockMessage(config), cost, false);
                }
                service.emit(onOutcome,
                        new GuardrailOutcome(config.getType(), GuardrailPhase.TOOL_CALL, GuardrailAction.REPROMPT, reason, repromptsSoFar + 1));
                return new Result(Decision.REPROMPT, rewrite, reason, issues, correctiveMessage(config, verdict, reason), cost, false);
            }
            default -> {
                service.emit(onOutcome,
                        new GuardrailOutcome(config.getType(), GuardrailPhase.TOOL_CALL, GuardrailAction.BLOCK, reason, repromptsSoFar));
                return new Result(Decision.BLOCK, rewrite, reason, issues, blockMessage(config), cost, false);
            }
        }
    }

    /**
     * The configured block message, or a default naming the guardrail type — author
     * text and EDDI vocabulary, never the screened text.
     */
    public static String blockMessage(GuardrailConfig config) {
        if (config.getBlockMessage() != null && !config.getBlockMessage().isBlank()) {
            return config.getBlockMessage();
        }
        return "This tool call was refused by guardrail '" + safe(config.getType(), "guardrail") + "'. Do not repeat it unchanged.";
    }

    /**
     * The corrective text of a tool-call reprompt: the configured
     * {@code repromptMessage}, else the guardrail's hint (the judge builds it from
     * the author's fix texts), else a generic one; {@code {reason}} is replaced
     * literally with the safe reason.
     */
    static String correctiveMessage(GuardrailConfig config, GuardrailVerdict verdict, String safeReason) {
        String configured = config.getParameters() != null ? config.getParameters().get("repromptMessage") : null;
        String template = configured != null && !configured.isBlank()
                ? configured
                : verdict.repromptHint() != null && !verdict.repromptHint().isBlank() ? verdict.repromptHint() : DEFAULT_CORRECTIVE;
        return template.replace("{reason}", safeReason);
    }

    /**
     * {@code value} when it is a safe token (lower-cased first), else
     * {@code fallback}.
     */
    public static String safe(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String lower = value.trim().toLowerCase(Locale.ROOT);
        return SAFE_TOKEN.matcher(lower).matches() ? lower : fallback;
    }

    private static List<String> safeIssues(List<String> issues) {
        List<String> safe = new ArrayList<>();
        for (String issue : issues) {
            // Issue codes are upper-case by convention; checked case-insensitively and
            // kept as written.
            if (issue != null && SAFE_TOKEN.matcher(issue.toLowerCase(Locale.ROOT)).matches()) {
                safe.add(issue);
            }
        }
        return List.copyOf(safe);
    }
}
