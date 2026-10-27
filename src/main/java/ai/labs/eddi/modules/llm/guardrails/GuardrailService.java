/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.guardrails;

import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.ConversationLogGenerator;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.GuardrailConfig;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Runs a task's input and output guardrails.
 * <p>
 * Stateless and shared by every conversation: the type → bean index is built
 * once in the constructor and never changes, and everything about one turn
 * arrives as arguments.
 * <h3>It never throws for a finding</h3> A terminal {@code block} is a returned
 * value, not an exception: a guardrail message can quote attacker-influenced
 * text, and an exception carrying it would reach the retry classifier of the
 * tool loop, where words like "timeout" make a failure look transient. Only a
 * failing model call during a reprompt propagates ({@link LifecycleException}
 * from the {@link Reprompter}), so the task's {@code onError} policy applies to
 * it as to any model failure.
 * <h3>Why EDDI-native and not langchain4j's guardrail types</h3> The
 * {@code dev.langchain4j.guardrail} SPI is built for {@code AiServices}: its
 * output request demands a {@code ChatExecutor} that a guardrail may call
 * itself — a second model-call path outside the turn's budget, deadline and
 * cost accounting — and its executors throw on failure. Reprompting here is
 * EDDI's loop through a {@link Reprompter} supplied by the execution branch
 * that produced the answer, so that path does not exist.
 */
@ApplicationScoped
public class GuardrailService {

    private static final Logger LOGGER = Logger.getLogger(GuardrailService.class);

    /** The metric every outcome increments; tags {@code phase, type, action}. */
    public static final String METRIC_TRIGGERED = "eddi.guardrail.triggered";

    /** Reprompt corrective message used when the guardrail gives no hint. */
    static final String DEFAULT_CORRECTIVE = "Your previous reply did not meet the response policy ({reason}). "
            + "Write a new reply that does. Reply with the answer only.";

    /**
     * What replaces an earlier user message of the history window that a guardrail
     * would block — the model sees that something was withheld, not what. The same
     * text the history builder puts in place of a turn whose input was refused
     * ({@link ConversationLogGenerator#WITHHELD_INPUT}).
     */
    static final String HISTORY_PLACEHOLDER = ConversationLogGenerator.WITHHELD_INPUT;

    /** The audit reason of a guardrail that threw or returned no verdict. */
    public static final String REASON_ERROR = "guardrail_error";

    /**
     * Parameter overriding what a failing guardrail does: {@code closed} or
     * {@code open}.
     */
    public static final String PARAM_FAIL_MODE = "failMode";

    /**
     * Suffix of the {@code warn} reason recorded when a {@code deliver-best}
     * guardrail's retries are spent and its best attempt is delivered.
     */
    public static final String DELIVERED_BEST_SUFFIX = ":delivered_best";

    /** The list name {@link #validate} is given for a task's input guardrails. */
    public static final String LIST_INPUT = "inputGuardrails";

    /**
     * The safety classifier's type, for the {@code deliver-best} deploy warning.
     */
    private static final String TYPE_CLASSIFIER = "classifier";

    /**
     * Bound on the distinct unknown types warned about, so a typo cannot grow
     * memory.
     */
    private static final int MAX_WARNED_TYPES = 100;

    private final Map<String, ContentGuardrail> byType;
    private final MeterRegistry meterRegistry;
    private final Set<String> warnedTypes = ConcurrentHashMap.newKeySet();

    @Inject
    public GuardrailService(@Any Instance<ContentGuardrail> guardrails, MeterRegistry meterRegistry) {
        this(guardrails.stream().toList(), meterRegistry);
    }

    /** For tests and for callers that assemble the guardrails themselves. */
    public GuardrailService(Collection<? extends ContentGuardrail> guardrails, MeterRegistry meterRegistry) {
        Map<String, ContentGuardrail> index = new LinkedHashMap<>();
        for (ContentGuardrail guardrail : guardrails) {
            String type = guardrail.type();
            if (type == null || type.isBlank()) {
                LOGGER.errorf("Guardrail bean %s declares no type — ignored", guardrail.getClass().getName());
                continue;
            }
            ContentGuardrail previous = index.putIfAbsent(type, guardrail);
            if (previous != null) {
                LOGGER.errorf("Two guardrail beans declare type '%s' (%s, %s) — the first one wins", type, previous.getClass().getName(),
                        guardrail.getClass().getName());
            }
        }
        this.byType = Collections.unmodifiableMap(index);
        this.meterRegistry = meterRegistry;
    }

    /** The registered guardrail types. */
    public Set<String> types() {
        return byType.keySet();
    }

    /**
     * The configuration problems of these guardrails, as reported by each type's
     * {@link ContentGuardrail#validate}, prefixed with where the guardrail sits
     * ({@code inputGuardrails[0]}). Unknown types are not problems here — they are
     * skipped with a WARN at run time, so a config written for a newer EDDI loads.
     */
    public List<String> validate(String listName, List<GuardrailConfig> configs) {
        List<String> problems = new ArrayList<>();
        if (configs == null) {
            return problems;
        }
        for (int i = 0; i < configs.size(); i++) {
            GuardrailConfig config = configs.get(i);
            String exhaustion = config != null ? onExhaustedProblem(listName, config) : null;
            if (exhaustion != null) {
                problems.add(listName + "[" + i + "]: " + exhaustion);
            }
            ContentGuardrail guardrail = config != null && config.getType() != null ? byType.get(config.getType()) : null;
            if (guardrail == null) {
                continue;
            }
            String problem = guardrail.validate(config.getParameters());
            if (problem != null) {
                problems.add(listName + "[" + i + "]: " + problem);
            }
        }
        return problems;
    }

    /**
     * {@code onExhausted}: {@code block} (the default, harmless anywhere) or
     * {@code deliver-best}, which only means something for an output
     * {@code reprompt} guardrail — anywhere else it would silently do nothing, so
     * it is refused rather than ignored.
     */
    private static String onExhaustedProblem(String listName, GuardrailConfig config) {
        String value = config.getOnExhausted();
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (GuardrailConfig.ON_EXHAUSTED_BLOCK.equalsIgnoreCase(trimmed)) {
            return null;
        }
        if (!GuardrailConfig.ON_EXHAUSTED_DELIVER_BEST.equalsIgnoreCase(trimmed)) {
            return "onExhausted must be 'block' or 'deliver-best'";
        }
        if (GuardrailAction.parse(config.getAction()) != GuardrailAction.REPROMPT) {
            return "onExhausted 'deliver-best' needs action 'reprompt' — there are no retries to exhaust otherwise";
        }
        if (LIST_INPUT.equals(listName)) {
            return "onExhausted 'deliver-best' applies to output guardrails only — an input reprompt blocks";
        }
        return null;
    }

    /**
     * Configurations that work but deserve a WARN at deploy time, prefixed like
     * {@link #validate}: each type's {@link ContentGuardrail#warnings}, and a
     * {@code classifier} set to {@code deliver-best} — a safety check that delivers
     * what it flagged defeats its purpose.
     */
    public List<String> warnings(String listName, List<GuardrailConfig> configs) {
        List<String> warnings = new ArrayList<>();
        if (configs == null) {
            return warnings;
        }
        for (int i = 0; i < configs.size(); i++) {
            GuardrailConfig config = configs.get(i);
            ContentGuardrail guardrail = config != null && config.getType() != null ? byType.get(config.getType()) : null;
            if (guardrail == null) {
                continue;
            }
            if (config.deliversBest() && TYPE_CLASSIFIER.equals(config.getType())) {
                warnings.add(listName + "[" + i + "]: onExhausted 'deliver-best' on a classifier delivers an answer the safety classifier flagged");
            }
            for (String warning : guardrail.warnings(config.getParameters())) {
                warnings.add(listName + "[" + i + "]: " + warning);
            }
        }
        return warnings;
    }

    /**
     * Whether any of these input guardrails is a known type that does not re-screen
     * the history window ({@link ContentGuardrail#screensHistory()} false, e.g. the
     * classifier): a message such a guardrail never saw on its own turn is never
     * checked by it later.
     */
    public boolean anySkipsHistory(List<GuardrailConfig> configs) {
        if (configs == null) {
            return false;
        }
        for (GuardrailConfig config : configs) {
            ContentGuardrail guardrail = config != null && config.getType() != null ? byType.get(config.getType()) : null;
            if (guardrail != null && guardrail.phases().contains(GuardrailPhase.INPUT) && !guardrail.screensHistory()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether these output guardrails can change the answer — then the task must
     * buffer it: tokens already streamed to a client cannot be taken back.
     * {@code warn}-only guardrails do not force buffering.
     */
    public static boolean requiresBuffering(List<GuardrailConfig> outputGuardrails) {
        if (outputGuardrails == null) {
            return false;
        }
        for (GuardrailConfig config : outputGuardrails) {
            if (config != null && GuardrailAction.parse(config.getAction()).mayChangeText()) {
                return true;
            }
        }
        return false;
    }

    // === Input phase ===

    /**
     * The result of the input phase.
     *
     * @param messages
     *            the messages to send: the input list with the current user message
     *            (and earlier ones, see {@link ContentGuardrail#screensHistory()})
     *            rewritten where a {@code redact} applied; the input list itself
     *            when nothing changed
     * @param blocked
     *            the outcome that blocked the turn, or null
     * @param blockMessage
     *            the text to answer with when blocked, otherwise null
     * @param costUsd
     *            what evaluating the guardrails cost
     * @param withholdFromHistory
     *            whether a {@code redact} was applied by a guardrail that does not
     *            re-screen history ({@link ContentGuardrail#screensHistory()}): the
     *            turn's message must then be withheld from later turns' history,
     *            where it would otherwise reappear as the user sent it
     * @param currentTextIndex
     *            the content index of the user's typed text in the last user
     *            message; the parts before it are earlier turns, the text parts
     *            after it this turn's attachment text. -1 when it has no text
     */
    public record InputResult(List<ChatMessage> messages, GuardrailOutcome blocked, String blockMessage, double costUsd,
            boolean withholdFromHistory, int currentTextIndex) {
        public boolean isBlocked() {
            return blocked != null;
        }
    }

    /**
     * Screens the last user message in {@code messages} with every configured input
     * guardrail, in order, then the earlier user messages of the window (silently —
     * they were screened and recorded on their own turn).
     */
    public InputResult runInput(List<GuardrailConfig> configs, List<ChatMessage> messages, GuardrailContext context,
                                Consumer<GuardrailOutcome> onOutcome) {
        return runInput(configs, messages, context, onOutcome, null);
    }

    /**
     * As {@link #runInput(List, List, GuardrailContext, Consumer, TextResult, int)}
     * with the typed text taken to be the last text part of the last user message.
     */
    public InputResult runInput(List<GuardrailConfig> configs, List<ChatMessage> messages, GuardrailContext context,
                                Consumer<GuardrailOutcome> onOutcome, TextResult prescreened) {
        return runInput(configs, messages, context, onOutcome, prescreened, -1);
    }

    /**
     * As {@link #runInput(List, List, GuardrailContext, Consumer)}, reusing an
     * earlier {@link #screenInput} of the user's raw message: when the typed text
     * of the last user message is still exactly that text, its screened form is
     * applied without evaluating (or recording) the guardrails a second time. When
     * it differs (a {@code prompt} template) it is screened as usual.
     * <p>
     * The last user message has three kinds of text part. Those before
     * {@code typedTextIndex} are earlier turns the history builder merged in (a
     * turn answered by a fallback); they are history. The part at it is what the
     * user typed this turn. The text parts after it were appended for this turn's
     * attachments (extracts, notes); each is screened as this turn's input too —
     * recorded, and able to block — so that neither of them is mistaken for the
     * other and a redaction lands on the text the model sees.
     *
     * @param typedTextIndex
     *            the content index of the typed text in the last user message, as
     *            it was before attachments were forwarded; -1 (or an index that is
     *            not a text part) means its last text part
     */
    public InputResult runInput(List<GuardrailConfig> configs, List<ChatMessage> messages, GuardrailContext context,
                                Consumer<GuardrailOutcome> onOutcome, TextResult prescreened, int typedTextIndex) {
        int last = lastUserIndex(messages);
        if (configs == null || configs.isEmpty() || last < 0) {
            return new InputResult(messages, null, null, 0.0, false, -1);
        }
        GuardrailContext inputContext = context.with(GuardrailPhase.INPUT, null, messages);
        UserMessage current = (UserMessage) messages.get(last);
        List<Content> contents = new ArrayList<>(current.contents());
        int typed = typedTextIndex >= 0 && typedTextIndex < contents.size() && contents.get(typedTextIndex) instanceof TextContent
                ? typedTextIndex
                : lastTextIndex(contents);
        String original = typed >= 0 ? ((TextContent) contents.get(typed)).text() : "";
        String text;
        double cost = 0.0;
        boolean withhold = false;
        if (prescreened != null && !prescreened.isBlocked() && original.equals(prescreened.original())) {
            text = prescreened.text();
            withhold = prescreened.withholdFromHistory();
        } else {
            TextResult screenedCurrent = screenInput(configs, original, inputContext, onOutcome);
            cost += screenedCurrent.costUsd();
            if (screenedCurrent.isBlocked()) {
                return new InputResult(messages, screenedCurrent.blocked(), screenedCurrent.blockMessage(), cost, true, typed);
            }
            text = screenedCurrent.text();
            withhold = screenedCurrent.withholdFromHistory();
        }
        boolean changed = false;
        if (!text.equals(original)) {
            if (typed >= 0) {
                contents.set(typed, TextContent.from(text));
            } else {
                contents.add(TextContent.from(text));
                typed = contents.size() - 1;
            }
            changed = true;
        }
        // This turn's attachment text: screened like the typed text, never as history.
        for (int c = typed + 1; c < contents.size(); c++) {
            if (!(contents.get(c) instanceof TextContent part)) {
                continue;
            }
            TextResult screenedPart = screenInput(configs, part.text(), inputContext, onOutcome);
            cost += screenedPart.costUsd();
            if (screenedPart.isBlocked()) {
                return new InputResult(messages, screenedPart.blocked(), screenedPart.blockMessage(), cost, true, typed);
            }
            withhold |= screenedPart.withholdFromHistory();
            if (!screenedPart.text().equals(part.text())) {
                contents.set(c, TextContent.from(screenedPart.text()));
                changed = true;
            }
        }

        List<ChatMessage> screened = new ArrayList<>(messages);
        if (changed) {
            screened.set(last, current.toBuilder().contents(contents).build());
        }
        HistoryResult history = screenHistory(configs, screened, last, typed, inputContext);
        cost += history.costUsd();
        changed |= history.changed();
        return new InputResult(changed ? screened : messages, null, null, cost, withhold, typed);
    }

    /**
     * One text screened by the input guardrails.
     *
     * @param original
     *            the text as given
     * @param text
     *            the text after every {@code redact}
     * @param blocked
     *            the outcome that blocked it, or null
     * @param blockMessage
     *            the answer when blocked, otherwise null
     * @param costUsd
     *            what evaluating the guardrails cost
     * @param withholdFromHistory
     *            whether a {@code redact} was applied by a guardrail that does not
     *            re-screen history (see {@link InputResult#withholdFromHistory()})
     */
    public record TextResult(String original, String text, GuardrailOutcome blocked, String blockMessage, double costUsd,
            boolean withholdFromHistory) {
        public boolean isBlocked() {
            return blocked != null;
        }
    }

    /**
     * Screens one user text with every configured input guardrail, in order,
     * recording each finding. Used for the user's raw message before retrieval, and
     * for the model-facing last user message.
     */
    public TextResult screenInput(List<GuardrailConfig> configs, String original, GuardrailContext context, Consumer<GuardrailOutcome> onOutcome) {
        String text = original != null ? original : "";
        double cost = 0.0;
        boolean withhold = false;
        if (configs == null) {
            return new TextResult(original, text, null, null, cost, false);
        }
        for (GuardrailConfig config : configs) {
            ContentGuardrail guardrail = resolve(config, GuardrailPhase.INPUT);
            if (guardrail == null) {
                continue;
            }
            GuardrailAction action = GuardrailAction.parse(config.getAction());
            Evaluation evaluation = evaluate(guardrail, text, context.with(GuardrailPhase.INPUT, config.getParameters(), context.messages()));
            GuardrailVerdict verdict;
            if (evaluation.failed()) {
                cost += evaluation.failureCostUsd();
                // A block guardrail that cannot check the message refuses it (fail closed,
                // Pillar 4) unless its author chose failMode=open.
                if (failsClosed(config, action)) {
                    var outcome = new GuardrailOutcome(config.getType(), GuardrailPhase.INPUT, GuardrailAction.BLOCK, evaluation.failureReason(), 0);
                    emit(onOutcome, outcome);
                    return new TextResult(original, text, outcome, blockMessage(config, GuardrailPhase.INPUT), cost, true);
                }
                if (evaluation.partial() == null) {
                    emit(onOutcome,
                            new GuardrailOutcome(config.getType(), GuardrailPhase.INPUT, GuardrailAction.WARN, evaluation.failureReason(), 0));
                    continue;
                }
                // Failing open, but it did find something before giving up: act on that.
                verdict = evaluation.partial();
            } else {
                verdict = evaluation.verdict();
            }
            cost += verdict.costUsd();
            if (!verdict.triggered()) {
                continue;
            }
            switch (action) {
                case WARN -> emit(onOutcome, new GuardrailOutcome(config.getType(), GuardrailPhase.INPUT, GuardrailAction.WARN, verdict.reason(), 0));
                case REDACT -> {
                    text = verdict.kind() == GuardrailVerdict.Kind.REWRITE ? verdict.text() : blockMessage(config, GuardrailPhase.INPUT);
                    // Later turns re-screen their history with this guardrail only when it
                    // screens history; otherwise the turn's message must be withheld there.
                    withhold |= !guardrail.screensHistory();
                    emit(onOutcome, new GuardrailOutcome(config.getType(), GuardrailPhase.INPUT, GuardrailAction.REDACT, verdict.reason(), 0));
                }
                default -> {
                    // BLOCK, and REPROMPT: there is nothing to re-ask on the input side.
                    var outcome = new GuardrailOutcome(config.getType(), GuardrailPhase.INPUT, GuardrailAction.BLOCK, verdict.reason(), 0);
                    emit(onOutcome, outcome);
                    return new TextResult(original, text, outcome, blockMessage(config, GuardrailPhase.INPUT), cost, true);
                }
            }
        }
        return new TextResult(original, text, null, null, cost, withhold);
    }

    /**
     * Re-applies a finished input phase to a rebuilt message list (the smaller
     * window of an {@code onContextTooLong} re-ask): the earlier user messages are
     * screened again, and the current one is replaced by its already-screened form.
     * Emits nothing — the turn's outcomes were recorded by {@link #runInput}.
     */
    public List<ChatMessage> reapply(List<GuardrailConfig> configs, List<ChatMessage> rebuilt, InputResult screened, GuardrailContext context) {
        if (rebuilt == null || screened == null || configs == null || configs.isEmpty()) {
            return rebuilt;
        }
        int last = lastUserIndex(rebuilt);
        int screenedLast = lastUserIndex(screened.messages());
        if (last < 0) {
            return rebuilt;
        }
        List<ChatMessage> copy = new ArrayList<>(rebuilt);
        int typed = -1;
        if (screenedLast >= 0) {
            copy.set(last, screened.messages().get(screenedLast));
            typed = screened.currentTextIndex();
        }
        screenHistory(configs, copy, last, typed, context.with(GuardrailPhase.INPUT, null, copy));
        return copy;
    }

    private record HistoryResult(boolean changed, double costUsd) {
    }

    /**
     * Screens (and rewrites in place) every earlier text of the window: all text
     * parts of the user messages before {@code last}, and the text parts of the
     * last user message before {@code currentTextIndex} (the typed text; -1: its
     * last text part). The history builder merges the question of a turn answered
     * by a fallback into the next user message, so that message can carry an
     * earlier turn's text ahead of the current one; it is history, not the current
     * turn. (A turn whose input a guardrail refused never gets here: the history
     * builder has already replaced it with {@link #HISTORY_PLACEHOLDER}.)
     */
    private HistoryResult screenHistory(List<GuardrailConfig> configs, List<ChatMessage> messages, int last, int currentTextIndex,
                                        GuardrailContext context) {
        boolean changed = false;
        double cost = 0.0;
        for (int i = 0; i <= last; i++) {
            if (!(messages.get(i) instanceof UserMessage user)) {
                continue;
            }
            List<Content> contents = new ArrayList<>(user.contents());
            int currentFrom = i == last ? (currentTextIndex >= 0 ? currentTextIndex : lastTextIndex(contents)) : Integer.MAX_VALUE;
            boolean messageChanged = false;
            for (int c = 0; c < contents.size(); c++) {
                if (!(contents.get(c) instanceof TextContent part) || (currentFrom >= 0 && c >= currentFrom)) {
                    continue;
                }
                String screened = part.text();
                for (GuardrailConfig config : configs) {
                    GuardrailAction action = GuardrailAction.parse(config.getAction());
                    if (!action.mayChangeText()) {
                        continue;
                    }
                    ContentGuardrail guardrail = resolve(config, GuardrailPhase.INPUT);
                    if (guardrail == null || !guardrail.screensHistory()) {
                        continue;
                    }
                    Evaluation evaluation = evaluate(guardrail, screened, context.with(GuardrailPhase.INPUT, config.getParameters(), messages));
                    GuardrailVerdict verdict = evaluation.verdict();
                    if (evaluation.failed()) {
                        cost += evaluation.failureCostUsd();
                        // Fail closed: an earlier message that cannot be checked is withheld.
                        if (failsClosed(config, action)) {
                            screened = HISTORY_PLACEHOLDER;
                            break;
                        }
                        if (evaluation.partial() == null) {
                            continue;
                        }
                        verdict = evaluation.partial();
                    }
                    // Paid for whether or not it fires.
                    cost += verdict.costUsd();
                    if (!verdict.triggered()) {
                        continue;
                    }
                    if (action == GuardrailAction.REDACT && verdict.kind() == GuardrailVerdict.Kind.REWRITE) {
                        screened = verdict.text();
                    } else {
                        screened = HISTORY_PLACEHOLDER;
                        break;
                    }
                }
                if (!screened.equals(part.text())) {
                    contents.set(c, TextContent.from(screened));
                    messageChanged = true;
                }
            }
            if (messageChanged) {
                messages.set(i, user.toBuilder().contents(contents).build());
                changed = true;
            }
        }
        if (changed) {
            LOGGER.debugf("Input guardrails rewrote earlier user messages of the history window for task '%s'", context.taskId());
        }
        return new HistoryResult(changed, cost);
    }

    // === Output phase ===

    /**
     * The result of the output phase.
     *
     * @param text
     *            the answer to deliver (possibly redacted or reprompted); the block
     *            message when blocked
     * @param blocked
     *            the outcome that blocked the answer, or null
     * @param reprompts
     *            the reprompt answers' response metadata, one per model call made —
     *            the caller folds their token usage into the turn's
     * @param costUsd
     *            what evaluating the guardrails cost (not the reprompts)
     */
    public record OutputResult(String text, GuardrailOutcome blocked, List<Map<String, Object>> reprompts, double costUsd) {
        public boolean isBlocked() {
            return blocked != null;
        }
    }

    /**
     * Screens the model's answer with every configured output guardrail, in order.
     * A {@code reprompt} finding asks the model again (through {@code reprompter})
     * and restarts the screening of the new answer from the first guardrail; once
     * the triggering guardrail's {@code maxRetries} are spent — or when no
     * reprompter is available, the turn is out of time, or the context's
     * {@link GuardrailContext#spend() spend checks} refuse the call — the answer is
     * blocked, unless that guardrail's {@code onExhausted} is {@code deliver-best}:
     * then the attempt it rated best is delivered with a {@code warn}, after one
     * more pass through the guardrails after it (see {@link #deliverBest}).
     *
     * @param reprompter
     *            the branch's way to ask the same model again, or null when this
     *            branch cannot (then {@code reprompt} degrades to {@code block})
     */
    public OutputResult runOutput(List<GuardrailConfig> configs, String answer, Reprompter reprompter, GuardrailContext context,
                                  Consumer<GuardrailOutcome> onOutcome)
            throws LifecycleException {
        String text = answer != null ? answer : "";
        List<Map<String, Object>> reprompts = new ArrayList<>();
        if (configs == null || configs.isEmpty()) {
            return new OutputResult(answer, null, reprompts, 0.0);
        }
        GuardrailContext outputContext = context.with(GuardrailPhase.OUTPUT, null, context.messages());
        double cost = 0.0;
        int attempts = 0;
        boolean changed = false;
        // What the previous reprompt cost: the expected cost of the next one.
        double lastRepromptCost = 0.0;
        // deliver-best: per guardrail (by list index), the attempt it rated best so
        // far.
        Map<Integer, Rated> best = new LinkedHashMap<>();

        screening : while (true) {
            for (int index = 0; index < configs.size(); index++) {
                GuardrailConfig config = configs.get(index);
                ContentGuardrail guardrail = resolve(config, GuardrailPhase.OUTPUT);
                if (guardrail == null) {
                    continue;
                }
                GuardrailAction action = GuardrailAction.parse(config.getAction());
                Evaluation evaluation = evaluate(guardrail, text, outputContext.with(GuardrailPhase.OUTPUT, config.getParameters(),
                        context.messages()));
                GuardrailVerdict verdict;
                if (evaluation.failed()) {
                    cost += evaluation.failureCostUsd();
                    // A block or reprompt guardrail that cannot verify the answer withholds it
                    // (fail closed, Pillar 4) unless its author chose failMode=open.
                    if (failsClosed(config, action)) {
                        return blocked(config, evaluation.failureReason(), attempts, reprompts, cost, onOutcome);
                    }
                    if (evaluation.partial() == null) {
                        emit(onOutcome,
                                new GuardrailOutcome(config.getType(), GuardrailPhase.OUTPUT, GuardrailAction.WARN, evaluation.failureReason(),
                                        attempts));
                        continue;
                    }
                    // Failing open, but it did find something before giving up: act on that.
                    verdict = evaluation.partial();
                } else {
                    verdict = evaluation.verdict();
                }
                cost += verdict.costUsd();
                if (action == GuardrailAction.REPROMPT && config.deliversBest()) {
                    // Ties go to the later attempt: it was written with more feedback.
                    best.merge(index, new Rated(text, verdict), (previous, next) -> next.severity() <= previous.severity() ? next : previous);
                }
                if (!verdict.triggered()) {
                    continue;
                }
                switch (action) {
                    case WARN -> emit(onOutcome, new GuardrailOutcome(config.getType(), GuardrailPhase.OUTPUT, GuardrailAction.WARN,
                            verdict.reason(), attempts));
                    case REDACT -> {
                        text = verdict.kind() == GuardrailVerdict.Kind.REWRITE ? verdict.text() : blockMessage(config, GuardrailPhase.OUTPUT);
                        changed = true;
                        emit(onOutcome, new GuardrailOutcome(config.getType(), GuardrailPhase.OUTPUT, GuardrailAction.REDACT, verdict.reason(),
                                attempts));
                    }
                    case BLOCK -> {
                        return blocked(config, verdict.reason(), attempts, reprompts, cost, onOutcome);
                    }
                    case REPROMPT -> {
                        if (reprompter == null || attempts >= config.effectiveMaxRetries()
                                || attempts >= GuardrailConfig.MAX_RETRIES_CEILING) {
                            return exhausted(configs, index, verdict, best, attempts, reprompts, cost, outputContext, onOutcome);
                        }
                        String corrective = correctiveMessage(config, verdict);
                        Reprompter.Attempt next;
                        // A reprompt is another paid model call: checked against the
                        // conversation's cost ceiling and the tenant budget before it starts,
                        // like the task's own call. Refused, it cannot start — block.
                        try (PaidCallGate.PaidCall paid = outputContext.spend().begin(lastRepromptCost)) {
                            if (!paid.admitted()) {
                                LOGGER.infof("Output guardrail '%s' wanted a reprompt for task '%s' but the spend checks refused it (%s) — blocking",
                                        config.getType(), context.taskId(), paid.refusalReason());
                                return blocked(config, verdict.reason(), attempts, reprompts, cost, onOutcome);
                            }
                            next = reprompter.reprompt(text, corrective);
                            if (next != null) {
                                lastRepromptCost = next.costUsd();
                                paid.spent(next.costUsd());
                            }
                        }
                        if (next == null) {
                            LOGGER.infof(
                                    "Output guardrail '%s' wanted a reprompt for task '%s' but none could start (turn out of time or the model's circuit is open)",
                                    config.getType(), context.taskId());
                            return exhausted(configs, index, verdict, best, attempts, reprompts, cost, outputContext, onOutcome);
                        }
                        attempts++;
                        reprompts.add(next.metadata() != null ? next.metadata() : Map.of());
                        emit(onOutcome, new GuardrailOutcome(config.getType(), GuardrailPhase.OUTPUT, GuardrailAction.REPROMPT, verdict.reason(),
                                attempts));
                        text = next.text() != null ? next.text() : "";
                        changed = true;
                        continue screening;
                    }
                    default -> throw new IllegalStateException("unhandled guardrail action " + action);
                }
            }
            break;
        }
        return new OutputResult(changed ? text : answer, null, reprompts, cost);
    }

    /** One attempt as a {@code deliver-best} guardrail saw and rated it. */
    private record Rated(String text, GuardrailVerdict verdict) {
        int severity() {
            return verdict.severity();
        }
    }

    /**
     * A {@code reprompt} guardrail that may not reprompt again: blocked, or — with
     * {@code onExhausted: deliver-best} — its best attempt delivered.
     */
    private OutputResult exhausted(List<GuardrailConfig> configs, int index, GuardrailVerdict verdict, Map<Integer, Rated> best, int attempts,
                                   List<Map<String, Object>> reprompts, double cost, GuardrailContext outputContext,
                                   Consumer<GuardrailOutcome> onOutcome) {
        GuardrailConfig config = configs.get(index);
        Rated chosen = best.get(index);
        if (!config.deliversBest() || chosen == null) {
            return blocked(config, verdict.reason(), attempts, reprompts, cost, onOutcome);
        }
        return deliverBest(configs, index, chosen, attempts, reprompts, cost, outputContext, onOutcome);
    }

    /**
     * Delivers the attempt the exhausted guardrail rated best (fewest issue codes;
     * for a guardrail without codes, the last attempt), recorded as a {@code warn}
     * with reason {@code <reason>:delivered_best} and the reprompts sent as its
     * retry count. A quality check giving up must never let an answer past a PII
     * redaction or a safety check, so the guardrails after the exhausted one — the
     * ones that have not seen this attempt — screen it, with no further reprompts:
     * a {@code redact} still rewrites it, a {@code warn} is still recorded, and a
     * {@code block} — or a {@code reprompt}, which may not re-ask now — blocks it.
     * <p>
     * The re-screen's outcomes are held back until it ends, so the record matches
     * what happened: a delivered attempt is recorded as the {@code :delivered_best}
     * warn followed by the re-screen's findings; a blocked one as the re-screen's
     * findings and the block — with no {@code :delivered_best} warn for an attempt
     * that was never delivered.
     */
    private OutputResult deliverBest(List<GuardrailConfig> configs, int exhaustedIndex, Rated chosen, int attempts,
                                     List<Map<String, Object>> reprompts, double cost, GuardrailContext outputContext,
                                     Consumer<GuardrailOutcome> onOutcome) {
        GuardrailConfig exhausted = configs.get(exhaustedIndex);
        String reason = chosen.verdict().reason() != null && !chosen.verdict().reason().isBlank() ? chosen.verdict().reason() : exhausted.getType();
        LOGGER.infof("Output guardrail '%s' spent its retries for task '%s' — re-screening its best attempt for delivery", exhausted.getType(),
                outputContext.taskId());
        List<GuardrailOutcome> pending = new ArrayList<>();
        OutputResult result = rescreen(configs, exhaustedIndex, chosen.text(), attempts, reprompts, cost, outputContext, pending);
        if (!result.isBlocked()) {
            emit(onOutcome, new GuardrailOutcome(exhausted.getType(), GuardrailPhase.OUTPUT, GuardrailAction.WARN, reason + DELIVERED_BEST_SUFFIX,
                    attempts));
        }
        pending.forEach(outcome -> emit(onOutcome, outcome));
        return result;
    }

    /**
     * The re-screen of {@link #deliverBest}: the guardrails <em>after</em> the
     * exhausted one, no reprompts. Its outcomes are collected into {@code pending},
     * not emitted — the block outcome included, as the last entry.
     * <p>
     * A {@code deliver-best} guardrail rates an attempt only once every guardrail
     * before it has screened that same attempt in the same pass: their redactions
     * are already in the chosen text and their findings already recorded, and an
     * earlier {@code block} or {@code reprompt} finding would have ended the pass
     * before the attempt was rated. Running them again would record each finding
     * twice and call (and bill) a model-backed guardrail twice on the same text.
     */
    private OutputResult rescreen(List<GuardrailConfig> configs, int exhaustedIndex, String chosenText, int attempts,
                                  List<Map<String, Object>> reprompts, double cost, GuardrailContext outputContext, List<GuardrailOutcome> pending) {
        String text = chosenText;
        for (int index = exhaustedIndex + 1; index < configs.size(); index++) {
            GuardrailConfig config = configs.get(index);
            ContentGuardrail guardrail = resolve(config, GuardrailPhase.OUTPUT);
            if (guardrail == null) {
                continue;
            }
            GuardrailAction action = GuardrailAction.parse(config.getAction());
            Evaluation evaluation = evaluate(guardrail, text, outputContext.with(GuardrailPhase.OUTPUT, config.getParameters(),
                    outputContext.messages()));
            GuardrailVerdict verdict;
            if (evaluation.failed()) {
                cost += evaluation.failureCostUsd();
                if (failsClosed(config, action)) {
                    OutputResult refused = blockedResult(config, evaluation.failureReason(), attempts, reprompts, cost);
                    pending.add(refused.blocked());
                    return refused;
                }
                if (evaluation.partial() == null) {
                    pending.add(new GuardrailOutcome(config.getType(), GuardrailPhase.OUTPUT, GuardrailAction.WARN, evaluation.failureReason(),
                            attempts));
                    continue;
                }
                // Failing open, but it did find something before giving up: act on that,
                // as the main screening pass does — a partial redaction is still applied.
                verdict = evaluation.partial();
            } else {
                verdict = evaluation.verdict();
            }
            cost += verdict.costUsd();
            if (!verdict.triggered()) {
                continue;
            }
            switch (action) {
                case WARN ->
                    pending.add(new GuardrailOutcome(config.getType(), GuardrailPhase.OUTPUT, GuardrailAction.WARN, verdict.reason(), attempts));
                case REDACT -> {
                    text = verdict.kind() == GuardrailVerdict.Kind.REWRITE ? verdict.text() : blockMessage(config, GuardrailPhase.OUTPUT);
                    pending.add(new GuardrailOutcome(config.getType(), GuardrailPhase.OUTPUT, GuardrailAction.REDACT, verdict.reason(), attempts));
                }
                // BLOCK, and REPROMPT: no further reprompts once a best attempt is chosen.
                default -> {
                    OutputResult refused = blockedResult(config, verdict.reason(), attempts, reprompts, cost);
                    pending.add(refused.blocked());
                    return refused;
                }
            }
        }
        return new OutputResult(text, null, reprompts, cost);
    }

    private OutputResult blocked(GuardrailConfig config, String reason, int attempts, List<Map<String, Object>> reprompts, double cost,
                                 Consumer<GuardrailOutcome> onOutcome) {
        OutputResult result = blockedResult(config, reason, attempts, reprompts, cost);
        emit(onOutcome, result.blocked());
        return result;
    }

    /** A blocked output result, without emitting its outcome. */
    private static OutputResult blockedResult(GuardrailConfig config, String reason, int attempts, List<Map<String, Object>> reprompts,
                                              double cost) {
        var outcome = new GuardrailOutcome(config.getType(), GuardrailPhase.OUTPUT, GuardrailAction.BLOCK, reason, attempts);
        return new OutputResult(blockMessage(config, GuardrailPhase.OUTPUT), outcome, reprompts, cost);
    }

    /**
     * The corrective user message of a reprompt: the configured
     * {@code repromptMessage} parameter, else the guardrail's hint, else a generic
     * one. {@code {reason}} is replaced literally with the guardrail's reason — no
     * template engine, and never the user's or the model's text.
     */
    static String correctiveMessage(GuardrailConfig config, GuardrailVerdict verdict) {
        String configured = config.getParameters() != null ? config.getParameters().get("repromptMessage") : null;
        String template = configured != null && !configured.isBlank()
                ? configured
                : verdict.repromptHint() != null && !verdict.repromptHint().isBlank() ? verdict.repromptHint() : DEFAULT_CORRECTIVE;
        String reason = verdict.reason() != null && !verdict.reason().isBlank() ? verdict.reason() : config.getType();
        return template.replace("{reason}", reason);
    }

    // === Shared ===

    /**
     * What a guardrail that throws or returns no verdict does: fail closed (treat
     * it as a finding that blocks) or open (record a warning, let the text pass).
     * The {@code failMode} parameter decides; without it a {@code block} or
     * {@code reprompt} guardrail fails closed (it exists to keep something out, and
     * "could not check" must not let it through, Pillar 4) and a {@code redact} or
     * {@code warn} guardrail fails open.
     */
    static boolean failsClosed(GuardrailConfig config, GuardrailAction action) {
        String mode = config.getParameters() != null ? config.getParameters().get(PARAM_FAIL_MODE) : null;
        if (mode != null && !mode.isBlank()) {
            if ("open".equalsIgnoreCase(mode.trim())) {
                return false;
            }
            if ("closed".equalsIgnoreCase(mode.trim())) {
                return true;
            }
        }
        return action == GuardrailAction.BLOCK || action == GuardrailAction.REPROMPT;
    }

    /** The configured block message, or a default naming the guardrail type. */
    static String blockMessage(GuardrailConfig config, GuardrailPhase phase) {
        if (config.getBlockMessage() != null && !config.getBlockMessage().isBlank()) {
            return config.getBlockMessage();
        }
        return phase == GuardrailPhase.INPUT
                ? "Request blocked by guardrail '" + config.getType() + "'."
                : "Response withheld by guardrail '" + config.getType() + "'.";
    }

    /**
     * The bean for this config in this phase, or null (with a one-time WARN) when
     * the type is unknown or does not run in the phase.
     */
    private ContentGuardrail resolve(GuardrailConfig config, GuardrailPhase phase) {
        if (config == null || config.getType() == null) {
            return null;
        }
        ContentGuardrail guardrail = byType.get(config.getType());
        if (guardrail == null) {
            warnOnce("unknown:" + config.getType(), "Unknown guardrail type '%s' — skipped. Known types: %s", config.getType(), byType.keySet());
            return null;
        }
        if (!guardrail.phases().contains(phase)) {
            warnOnce(phase.label() + ":" + config.getType(), "Guardrail type '%s' does not run on %s — skipped", config.getType(),
                    phase.label());
            return null;
        }
        return guardrail;
    }

    private void warnOnce(String key, String format, Object first, Object second) {
        if (warnedTypes.size() < MAX_WARNED_TYPES && warnedTypes.add(key)) {
            LOGGER.warnf(format, sanitizeType(String.valueOf(first)), second);
        }
    }

    private static String sanitizeType(String type) {
        String shown = type.length() > 60 ? type.substring(0, 60) : type;
        return shown.replaceAll("[\\r\\n\\t]", "_");
    }

    /**
     * One evaluation: a verdict, or — when the guardrail threw, returned none, or
     * reported that it could not decide — the reason to record and what the failed
     * attempt cost.
     */
    private record Evaluation(GuardrailVerdict verdict, String failureReason, double failureCostUsd, GuardrailVerdict partial) {
        boolean failed() {
            return verdict == null;
        }
    }

    /**
     * Evaluates one guardrail without ever propagating its failure (logged); what a
     * failure does is {@link #failsClosed}'s decision.
     */
    private Evaluation evaluate(ContentGuardrail guardrail, String text, GuardrailContext context) {
        try {
            GuardrailVerdict verdict = guardrail.evaluate(text, context);
            if (verdict == null) {
                LOGGER.warnf("Guardrail '%s' returned no verdict on %s for task '%s' — treated as a guardrail error", guardrail.type(),
                        context.phase().label(), context.taskId());
                return new Evaluation(null, REASON_ERROR, 0.0, null);
            }
            return new Evaluation(verdict, null, 0.0, null);
        } catch (GuardrailUndecidedException e) {
            String reason = e.reason() != null && !e.reason().isBlank() ? e.reason() : REASON_ERROR;
            LOGGER.infof("Guardrail '%s' could not decide on %s for task '%s' (%s) — its failMode applies", guardrail.type(),
                    context.phase().label(), context.taskId(), reason);
            double cost = Double.isFinite(e.costUsd()) && e.costUsd() > 0.0 ? e.costUsd() : 0.0;
            return new Evaluation(null, reason, cost, e.partialFinding());
        } catch (RuntimeException e) {
            LOGGER.warnf("Guardrail '%s' failed on %s for task '%s' (%s) — its failMode applies", guardrail.type(),
                    context.phase().label(), context.taskId(), e.getClass().getSimpleName());
            return new Evaluation(null, REASON_ERROR, 0.0, null);
        }
    }

    private void emit(Consumer<GuardrailOutcome> onOutcome, GuardrailOutcome outcome) {
        if (meterRegistry != null) {
            meterRegistry.counter(METRIC_TRIGGERED, "phase", outcome.phase().label(), "type", outcome.type(), "action", outcome.action().label())
                    .increment();
        }
        if (onOutcome != null) {
            onOutcome.accept(outcome);
        }
    }

    private static int lastUserIndex(List<ChatMessage> messages) {
        if (messages == null) {
            return -1;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The content index of the current turn's typed text in the last user message
     * of a freshly built history: its last text part (earlier text parts are
     * earlier turns merged in by the history builder, other parts images and
     * documents); -1 when there is none. Taken before attachments are forwarded,
     * which append their own text parts after it — the value to pass to
     * {@link #runInput(List, List, GuardrailContext, Consumer, TextResult, int)}.
     */
    public static int currentTextIndex(List<ChatMessage> messages) {
        int last = lastUserIndex(messages);
        return last >= 0 ? lastTextIndex(((UserMessage) messages.get(last)).contents()) : -1;
    }

    private static int lastTextIndex(List<Content> contents) {
        for (int i = contents.size() - 1; i >= 0; i--) {
            if (contents.get(i) instanceof TextContent) {
                return i;
            }
        }
        return -1;
    }
}
