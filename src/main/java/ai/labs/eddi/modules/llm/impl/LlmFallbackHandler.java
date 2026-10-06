/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalRequiredException;
import ai.labs.eddi.engine.internal.GroupConversationService;
import ai.labs.eddi.engine.lifecycle.exceptions.ConversationPauseException;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ResponseValidation;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.Task;
import ai.labs.eddi.modules.output.model.QuickReply;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import ai.labs.eddi.secrets.sanitize.SecretRedactionFilter;
import io.micrometer.core.instrument.MeterRegistry;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * Builds and records the LLM task's configurable fallback answer (R6/R7).
 * <p>
 * Two things lead here: a {@code responseValidation} action of
 * {@code "fallback"}, and a task whose {@code onError} is {@code "fallback"}
 * absorbing a failed model phase. Both serve the same thing: the rendered
 * {@code fallbackMessage} (a Qute template — author-written config — rendered
 * with the task's template data; the default sentence when none is configured
 * or it fails to render), wrapped as {@code {"<fallbackField>": "<message>"}}
 * under {@code convertToObject}, plus the optional
 * {@code fallbackQuickReplies}.
 * <p>
 * The step is marked {@code llm:fallback:<taskId>=true}; the history builder
 * reads that flag so the model never sees the apology. A failure additionally
 * lands under {@code llm:error:<taskId>} as {@code {class, message}} —
 * redacted, URL-free and truncated.
 * <p>
 * Stateless: one instance is shared by every conversation, like the task that
 * owns it.
 */
final class LlmFallbackHandler {

    private static final Logger LOGGER = Logger.getLogger(LlmFallbackHandler.class);

    /** Longest error message kept on the step. */
    static final int MAX_MESSAGE_LENGTH = 200;

    private static final Pattern URL = Pattern.compile("(?i)\\b[a-z][a-z0-9+.-]*://\\S+");
    private static final int MAX_CAUSE_DEPTH = 16;

    /**
     * What was served.
     *
     * @param text
     *            the user-visible message
     * @param content
     *            what is stored as the task's raw model output: the message, or the
     *            JSON object wrapping it under {@code fallbackField}
     * @param object
     *            what the task's response object name resolves to in templates: the
     *            message, or the Map wrapping it under {@code fallbackField}
     */
    record Fallback(String text, String content, Object object) {
    }

    private final ITemplatingEngine templatingEngine;
    private final IJsonSerialization jsonSerialization;
    private final IDataFactory dataFactory;
    private final MeterRegistry meterRegistry;

    LlmFallbackHandler(ITemplatingEngine templatingEngine, IJsonSerialization jsonSerialization, IDataFactory dataFactory,
            MeterRegistry meterRegistry) {
        this.templatingEngine = templatingEngine;
        this.jsonSerialization = jsonSerialization;
        this.dataFactory = dataFactory;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Builds the fallback, flags the step, stores the quick replies, records the
     * failure (if any) and counts it. Never throws: whatever goes wrong while
     * rendering degrades to the default sentence, because a fallback that can fail
     * defeats its purpose.
     *
     * @param trigger
     *            {@code "onError"} or {@code "validation"} — the metric's
     *            {@code trigger} tag
     * @param failure
     *            the absorbed failure, or null for a validation fallback
     * @param convertToObject
     *            whether the task parses its reply into an object, which is what
     *            {@code fallbackField} applies to
     */
    Fallback serve(Task task, Map<String, Object> templateData, IWritableConversationStep step, String trigger, Throwable failure,
                   boolean convertToObject, String conversationId) {
        String taskId = task.getId() != null ? task.getId() : "default";
        ResponseValidation config = task.getResponseValidation();

        String text = renderMessage(config, templateData);
        Fallback fallback = wrap(text, config, convertToObject);

        step.storeData(dataFactory.createData(MemoryKeys.LLM_FALLBACK_PREFIX + taskId, Boolean.TRUE));
        storeQuickReplies(config, templateData, step, taskId);

        if (failure != null) {
            Map<String, Object> described = describe(failure);
            step.storeData(dataFactory.createData(MemoryKeys.LLM_ERROR_PREFIX + taskId, described));
            LOGGER.warnf("LLM task '%s' failed in conversation '%s' (%s: %s) — serving the configured fallback (onError=fallback)", taskId,
                    sanitizeForLog(conversationId), described.get("class"), described.get("message"));
        }
        if (meterRegistry != null) {
            meterRegistry.counter("eddi.llm.recovery", "action", "fallback", "outcome", "served", "trigger", trigger).increment();
        }
        return fallback;
    }

    private String renderMessage(ResponseValidation config, Map<String, Object> templateData) {
        String template = config != null ? config.getFallbackMessage() : null;
        if (isNullOrEmpty(template) || template.isBlank()) {
            return ResponseValidation.DEFAULT_FALLBACK_MESSAGE;
        }
        try {
            String rendered = templatingEngine.processTemplate(template, templateData);
            if (rendered == null || rendered.isBlank()) {
                LOGGER.warnf("fallbackMessage rendered blank — serving the default sentence instead");
                return ResponseValidation.DEFAULT_FALLBACK_MESSAGE;
            }
            return rendered;
        } catch (Exception e) {
            LOGGER.warnf("fallbackMessage failed to render (%s) — serving the default sentence instead", e.getClass().getSimpleName());
            return ResponseValidation.DEFAULT_FALLBACK_MESSAGE;
        }
    }

    private Fallback wrap(String text, ResponseValidation config, boolean convertToObject) {
        String field = config != null ? config.getFallbackField() : null;
        if (!convertToObject || isNullOrEmpty(field) || field.isBlank()) {
            return new Fallback(text, text, text);
        }
        Map<String, Object> object = new LinkedHashMap<>();
        object.put(field, text);
        try {
            return new Fallback(text, jsonSerialization.serialize(object), object);
        } catch (IOException e) {
            // A one-field map always serializes; if it somehow does not, the object
            // is what templates read, and the raw string is only for debugging.
            return new Fallback(text, text, object);
        }
    }

    private void storeQuickReplies(ResponseValidation config, Map<String, Object> templateData, IWritableConversationStep step,
                                   String taskId) {
        List<QuickReply> configured = config != null ? config.getFallbackQuickReplies() : null;
        if (configured == null || configured.isEmpty()) {
            return;
        }
        List<QuickReply> rendered = new ArrayList<>();
        for (QuickReply quickReply : configured) {
            if (quickReply == null) {
                continue;
            }
            rendered.add(new QuickReply(renderOrKeep(quickReply.getValue(), templateData), renderOrKeep(quickReply.getExpressions(), templateData),
                    quickReply.getIsDefault()));
        }
        if (rendered.isEmpty()) {
            return;
        }
        // Same shape OutputGenerationTask writes: a public "quickReplies:<action>"
        // entry
        // plus the conversation-output list clients read. Verbatim — already rendered
        // here, and a second pass by the templating step must not evaluate it again.
        var data = dataFactory.createData(MemoryKeys.QUICK_REPLIES_PREFIX + ":llm:fallback:" + taskId, rendered);
        data.setPublic(true);
        data.setVerbatim(true);
        step.storeData(data);
        step.addConversationOutputList(MemoryKeys.QUICK_REPLIES_PREFIX, rendered);
    }

    private String renderOrKeep(String template, Map<String, Object> templateData) {
        if (isNullOrEmpty(template)) {
            return template;
        }
        try {
            String rendered = templatingEngine.processTemplate(template, templateData);
            return rendered != null ? rendered : template;
        } catch (Exception e) {
            return template;
        }
    }

    /**
     * {@code {class, message}} for {@code llm:error:<taskId>}: the simple class
     * name of the root cause and its message — redacted with the log secret filter,
     * stripped of URLs (a URL can carry a key in its query string) and truncated.
     * Never a stack trace and never a request body. R2 will classify failures
     * properly; this is the one place to swap that in.
     */
    static Map<String, Object> describe(Throwable failure) {
        Throwable root = failure;
        for (int depth = 0; root.getCause() != null && root.getCause() != root && depth < MAX_CAUSE_DEPTH; depth++) {
            root = root.getCause();
        }
        String message = root.getMessage();
        if (isNullOrEmpty(message) || message.isBlank()) {
            message = failure.getMessage();
        }
        Map<String, Object> described = new LinkedHashMap<>();
        described.put("class", root.getClass().getSimpleName());
        described.put("message", sanitizeMessage(message));
        return described;
    }

    static String sanitizeMessage(String message) {
        if (message == null) {
            return "";
        }
        // Strip URLs and redact BEFORE truncating — cutting first can split a secret so
        // the pattern no longer matches and leaves a fragment behind.
        String cleaned = URL.matcher(message).replaceAll("[url]");
        cleaned = SecretRedactionFilter.redact(cleaned);
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        return cleaned.length() > MAX_MESSAGE_LENGTH ? cleaned.substring(0, MAX_MESSAGE_LENGTH) + "..." : cleaned;
    }

    private static String sanitizeForLog(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n\\t]", "_");
    }

    /**
     * Whether {@code failure} is control flow that must propagate, never a model
     * failure to absorb: a HITL tool pause, a conversation pause, a cancelled group
     * member turn, a graceful-shutdown interrupt, a cancelled future, or a thread
     * interrupt — looked for through the whole cause chain, because the executors
     * wrap what they catch.
     */
    static boolean isControlFlow(Throwable failure) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof ToolApprovalRequiredException || current instanceof ConversationPauseException
                    || current instanceof LifecycleException.LifecycleInterruptedException
                    || current instanceof GroupConversationService.MemberTurnCancelledException || current instanceof InterruptedException
                    || current instanceof CancellationException) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }
}
