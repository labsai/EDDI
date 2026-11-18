/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.configs.agents.AgentCascadeService;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.internal.groups.ReviewRefusalException;
import ai.labs.eddi.engine.setup.AgentSetupService;
import ai.labs.eddi.engine.spendcontrol.SpendControlException;
import ai.labs.eddi.engine.tenancy.QuotaRefusal;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.engine.runtime.client.factory.RestInterfaceFactory;
import ai.labs.eddi.engine.schedule.model.ScheduleFireLog;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.core.Response;

/**
 * Shared utility methods for MCP tool implementations.
 *
 * @author ginccc
 */
final class McpToolUtils {

    private McpToolUtils() {
        // utility class
    }

    /**
     * Check that the current caller has the required role. When authorization is
     * disabled ({@code authEnabled=false}), no check is performed.
     *
     * @param identity
     *            the current security identity
     * @param authEnabled
     *            whether authorization is enabled
     * @param role
     *            the required realm role
     * @throws ForbiddenException
     *             if the caller lacks the required role
     */
    static void requireRole(SecurityIdentity identity, boolean authEnabled, String role) {
        if (!authEnabled) {
            return;
        }
        if (identity == null || identity.isAnonymous() || !identity.hasRole(role)) {
            throw new ForbiddenException("MCP operation requires role: " + role);
        }
    }

    /**
     * As {@link #requireRole}, but satisfied by <em>any</em> of the given roles.
     * <p>
     * EDDI has no role hierarchy — {@code hasRole} is a literal check, so a
     * single-role requirement of {@code eddi-viewer} would refuse an
     * {@code eddi-admin}. A tool whose REST counterpart enumerates several roles
     * (the docs endpoints, above all) needs the same enumeration here, or the two
     * surfaces guard the same content differently.
     *
     * @throws ForbiddenException
     *             if the caller holds none of the given roles
     */
    static void requireAnyRole(SecurityIdentity identity, boolean authEnabled, Collection<String> roles) {
        if (!authEnabled) {
            return;
        }
        if (identity != null && !identity.isAnonymous()) {
            for (String role : roles) {
                if (identity.hasRole(role)) {
                    return;
                }
            }
        }
        throw new ForbiddenException("MCP operation requires one of roles: " + String.join(", ", roles));
    }

    /**
     * Get a REST interface proxy via IRestInterfaceFactory. These proxies make HTTP
     * calls that go through the full JAX-RS workflow, including
     * DocumentDescriptorFilter which auto-creates descriptors.
     *
     * @param factory
     *            the REST interface factory
     * @param clazz
     *            the REST interface class to proxy
     * @return the proxy instance
     * @throws RuntimeException
     *             if the proxy cannot be created
     */
    static <T> T getRestStore(IRestInterfaceFactory factory, Class<T> clazz) {
        try {
            return factory.get(clazz);
        } catch (RestInterfaceFactory.RestInterfaceFactoryException e) {
            throw new RuntimeException("Failed to get REST proxy for " + clazz.getSimpleName(), e);
        }
    }

    /**
     * Parse an environment string to the corresponding enum value. Delegates to
     * {@link Environment#parseStrict(String)}, the single place that knows the
     * mapping: only an absent (null/blank) environment defaults to
     * {@link Environment#production} — an environment the platform does not know is
     * rejected with an {@link UnknownEnvironmentException} rather than silently
     * resolving to production. A typo such as {@code "staging"} must never deploy
     * to, undeploy from, or talk to production.
     *
     * @throws UnknownEnvironmentException
     *             if {@code environment} is neither blank nor a known environment
     */
    static Environment parseEnvironment(String environment) {
        try {
            return Environment.parseStrict(environment);
        } catch (IllegalArgumentException e) {
            throw new UnknownEnvironmentException(e.getMessage());
        }
    }

    /**
     * An MCP caller passed an environment EDDI does not know. Distinct type so a
     * tool can tell bad caller input apart from a server-side failure; the message
     * names both the rejected value and the valid ones, so an MCP client (and the
     * model driving it) can self-correct instead of retrying the same call.
     */
    static final class UnknownEnvironmentException extends IllegalArgumentException {

        UnknownEnvironmentException(String message) {
            super(message);
        }
    }

    /**
     * Parse a string to an integer, returning a default value on failure.
     */
    static int parseIntOrDefault(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Parse a string to a boolean: {@code defaultValue} when null or blank, else
     * whether it is {@code "true"} (case-insensitive).
     */
    static boolean parseBooleanOrDefault(String value, boolean defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(value.trim());
    }

    /**
     * The most characters of one tool result that reaches the caller's LLM context
     * ({@value}): about 25,000 tokens. A result over it is cut and says so — see
     * {@link #bounded}. Tools with their own paging arguments keep a smaller
     * default of their own.
     */
    static final int MAX_RESULT_CHARS = 100_000;

    /**
     * The most conversation steps {@code read_conversation_log} returns by default.
     */
    static final int DEFAULT_LOG_STEPS = 50;

    /** The most conversation steps {@code read_conversation_log} will return. */
    static final int MAX_LOG_STEPS = 500;

    /** The largest page a list-style tool returns, whatever limit was asked for. */
    static final int MAX_PAGE_SIZE = 200;

    /**
     * A limit argument as a page size: {@code defaultSize} when absent or not
     * positive, never above {@code maxSize}.
     */
    static int clampLimit(Integer requested, int defaultSize, int maxSize) {
        if (requested == null || requested <= 0) {
            return Math.min(defaultSize, maxSize);
        }
        return Math.min(requested, maxSize);
    }

    private static final ObjectMapper BOUNDS_MAPPER = new ObjectMapper();

    /**
     * A JSON object result within {@code maxChars}. A longer one is returned as
     * valid JSON of its normal shape with the bulky lists shortened to their
     * <em>newest</em> entries (the tail; the lists are oldest-first), never cutting
     * inside a value: each list in {@code listFields} keeps the longest tail that
     * lets the document fit, and the result gains {@code truncated: true},
     * {@code totalChars}, {@code returnedChars}, {@code total<Field>} /
     * {@code returned<Field>} per shortened list and {@code hint}. When no tail
     * fits, the lists are emptied; a document that is still too large (one huge
     * value) becomes just the {@code truncated} envelope with the counts and the
     * hint.
     *
     * @param listFields
     *            top-level array members that may be shortened, e.g.
     *            {@code "conversationSteps"}
     */
    static String bounded(String json, int maxChars, String hint, String... listFields) {
        if (json == null || json.length() <= maxChars) {
            return json;
        }
        if (json.length() > MAX_BOUNDABLE_CHARS) {
            // Too large to re-parse into a tree on a request thread (a tree costs several
            // times the text): only the envelope, which says how to ask for less.
            return envelope(json.length(), hint);
        }
        try {
            JsonNode parsed = BOUNDS_MAPPER.readTree(json);
            if (parsed instanceof ObjectNode root) {
                Map<String, Integer> sizes = new LinkedHashMap<>();
                for (String field : listFields) {
                    if (root.get(field) instanceof ArrayNode list) {
                        sizes.put(field, list.size());
                    }
                }
                int largest = sizes.values().stream().mapToInt(Integer::intValue).max().orElse(0);
                for (int keep = largest / 2;; keep = keep / 2) {
                    // A shallow copy: the members are shared, not copied — only the shortened
                    // lists and the added fields are new, and nothing shared is modified.
                    ObjectNode shortened = root.objectNode();
                    shortened.setAll(root);
                    for (var entry : sizes.entrySet()) {
                        ArrayNode list = (ArrayNode) shortened.get(entry.getKey());
                        int size = list.size();
                        int from = Math.max(0, size - keep);
                        ArrayNode tail = shortened.arrayNode();
                        for (int i = from; i < size; i++) {
                            tail.add(list.get(i));
                        }
                        shortened.set(entry.getKey(), tail);
                        shortened.put("total" + capitalised(entry.getKey()), size);
                        shortened.put("returned" + capitalised(entry.getKey()), tail.size());
                    }
                    shortened.put("truncated", true);
                    shortened.put("totalChars", json.length());
                    shortened.put("hint", hint);
                    String candidate = BOUNDS_MAPPER.writeValueAsString(shortened);
                    if (candidate.length() <= maxChars) {
                        return candidate;
                    }
                    if (keep == 0) {
                        break;
                    }
                }
            }
        } catch (JsonProcessingException e) {
            // not an object we can shorten: the envelope below
        }
        return envelope(json.length(), hint);
    }

    /**
     * The longest result {@link #bounded} shortens entry by entry
     * ({@value} characters); a longer one gets only the envelope.
     */
    static final int MAX_BOUNDABLE_CHARS = 8_000_000;

    private static String envelope(int totalChars, String hint) {
        return "{\"truncated\":true,\"totalChars\":" + totalChars + ",\"hint\":\"" + escapeJsonString(hint) + "\"}";
    }

    private static String capitalised(String field) {
        return Character.toUpperCase(field.charAt(0)) + field.substring(1);
    }

    /**
     * A text result within {@code maxChars}, or its beginning cut at a line end
     * (never inside a surrogate pair) followed by an explicit
     * {@code [truncated: true …]} line with the hint.
     */
    static String boundedText(String text, int maxChars, String hint) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        int cut = maxChars;
        int lineEnd = text.lastIndexOf('\n', maxChars);
        if (lineEnd > maxChars / 2) {
            cut = lineEnd;
        } else if (Character.isHighSurrogate(text.charAt(cut - 1))) {
            cut--;
        }
        return text.substring(0, cut) + "\n[truncated: true — returned " + cut + " of " + text.length() + " characters. " + hint + "]";
    }

    /**
     * Build an error JSON response with proper escaping. Uses manual construction
     * to avoid serialization dependency in error paths.
     */
    static String errorJson(String message) {
        return "{\"error\":\"" + escapeJsonString(message) + "\"}";
    }

    // There is deliberately no overload that takes a message prefix and an
    // exception: it echoed any exception's text — a datastore or driver message —
    // to the caller. A tool that catches an exception answers with toolFailure,
    // which describes only what the caller caused and logs the rest under a
    // reference id. McpToolErrorLeakScanTest keeps the overload from coming back.

    /**
     * The error a tool answers when its body throws, without leaking internals.
     * <p>
     * A failure the caller caused and can fix — a 4xx from an in-process REST
     * store, a validation {@link IllegalArgumentException}, an unknown or stale
     * resource — is described to them, as {@link #describe} does. Anything else is
     * this server's problem: its raw message can name datastore hosts, collection
     * and class names, file paths or a driver's error text, none of which an MCP
     * client should see. That is logged in full (with the stack) under a short
     * reference id, and the client gets the reference to quote instead.
     *
     * @param log
     *            the tool class's logger
     * @param tool
     *            the tool name, as the client called it
     */
    static String toolFailure(Logger log, String tool, Throwable e) {
        if (e instanceof ForbiddenException denied) {
            return accessRefusal(log, tool, tool + " failed", denied);
        }
        if (isCallerFacing(e)) {
            log.debugf("%s rejected: %s", tool, describe(e));
            return errorJson(tool + " failed: " + describe(e));
        }
        return internalFailure(log, tool, tool + " failed", e);
    }

    /**
     * {@link #toolFailure(Logger, String, Throwable)} with a caller-facing
     * description of what failed (e.g. {@code "Failed to read resource"}): a
     * failure the caller can fix is described after it, anything else is logged
     * under a reference and never echoed.
     */
    static String toolFailure(Logger log, String tool, String what, Throwable e) {
        if (e instanceof ForbiddenException denied) {
            return accessRefusal(log, tool, what, denied);
        }
        if (isCallerFacing(e)) {
            log.debugf("%s rejected: %s", tool, describe(e));
            return errorJson(what + ": " + describe(e));
        }
        return internalFailure(log, tool, what, e);
    }

    /**
     * The error for a permission refusal of EDDI's own guards —
     * {@code ResourceAccessGuard}, {@code OwnershipValidator} and the like throw a
     * Quarkus {@link ForbiddenException}, which is not a JAX-RS client error and so
     * not {@link #isCallerFacing}. It is a refusal the caller can act on (ask for
     * access), not a server failure, so it is answered with errorCode
     * {@code FORBIDDEN} rather than {@code INTERNAL}. The guard's message is
     * authored for the caller and is kept; one raised outside EDDI's code is
     * replaced by a plain "Access denied".
     */
    static String accessRefusal(Logger log, String tool, String what, ForbiddenException denied) {
        String message = denied.getMessage();
        String reason = message == null || message.isBlank() || !thrownByEddi(denied) ? "Access denied" : message;
        log.debugf("%s refused: %s", tool, reason);
        return errorJson(what + ": " + reason, "FORBIDDEN", null);
    }

    /**
     * The error for a failure that is this server's problem: the exception is
     * logged in full (with the stack) under a short reference id, and the caller
     * gets {@code what} plus that reference — errorCode {@code INTERNAL}, the
     * reference in {@code details} — never the exception's own text.
     *
     * @param log
     *            the tool class's logger
     * @param tool
     *            the tool name, as the client called it
     * @param what
     *            the caller-facing description of what failed, e.g.
     *            {@code "Failed to read group"}
     */
    static String internalFailure(Logger log, String tool, String what, Throwable e) {
        String reference = UUID.randomUUID().toString().substring(0, 8);
        log.errorf(e, "%s failed [reference %s]", tool, reference);
        return errorJson(what + " with an internal error (reference " + reference + "). The details are in the server log under that reference.",
                "INTERNAL", Map.of("reference", reference));
    }

    /**
     * The error for a failure of an in-process REST resource that answers with
     * EDDI's coded error body ({@code {"error": "<CODE>", "message": "…"}} — the
     * ratings and alerts resources): the code becomes the {@code errorCode}, so an
     * MCP client branches on it exactly as a REST client does. A permission refusal
     * of {@code ResourceAccessGuard} (a Quarkus {@link ForbiddenException}, not a
     * JAX-RS one) is {@code FORBIDDEN} — left to {@link #toolFailure} it is not
     * caller-facing and would surface as {@code INTERNAL}. Anything else goes
     * through {@link #toolFailure}.
     */
    static String codedFailure(Logger log, String tool, Exception e) {
        if (e instanceof ClientErrorException clientError) {
            Response response = clientError.getResponse();
            Object entity = response == null ? null : response.getEntity();
            if (entity instanceof Map<?, ?> body && body.get("error") instanceof String code) {
                Object message = body.get("message");
                return errorJson(message instanceof String text && !text.isBlank() ? text : code, code, null);
            }
        }
        if (e instanceof ForbiddenException denied) {
            String message = denied.getMessage();
            return errorJson(message == null || message.isBlank() ? "Access denied" : message, "FORBIDDEN", null);
        }
        return toolFailure(log, tool, e);
    }

    /** A parse of a JSON argument the caller supplied. */
    @FunctionalInterface
    interface CallerJsonRead<T> {
        T read() throws IOException;
    }

    /**
     * Runs a parse of a JSON tool argument, reporting syntactically malformed JSON
     * as the caller's mistake.
     * <p>
     * A {@link JsonParseException} out of a caller's argument is not a server
     * failure, but left to {@link #toolFailure} it is not caller-facing and would
     * surface as {@code INTERNAL} with a reference id — telling the caller nothing
     * they can fix — and neither is a {@link JsonMappingException} that merely
     * wraps one (input truncated inside a nested value). It becomes an
     * {@link IllegalArgumentException} naming the argument and the position of the
     * error; the parser's own message is not echoed. Every other failure, including
     * a mapping problem that names model classes, propagates unchanged.
     *
     * @param argument
     *            the tool argument the JSON came from, e.g. {@code "config"}
     */
    static <T> T readCallerJson(String argument, CallerJsonRead<T> read) throws IOException {
        try {
            return read.read();
        } catch (JsonParseException e) {
            throw invalidJson(argument, e);
        } catch (JsonMappingException e) {
            // Input that ends inside a nested value ('{"a": [') surfaces from
            // databind as a mapping exception wrapping the parser's end-of-input error.
            if (e.getCause() instanceof JsonParseException syntax) {
                throw invalidJson(argument, syntax);
            }
            throw e;
        }
    }

    private static IllegalArgumentException invalidJson(String argument, JsonParseException e) {
        JsonLocation location = e.getLocation();
        String where = location == null || location.getLineNr() < 1
                ? ""
                : " (line " + location.getLineNr() + ", column " + location.getColumnNr() + ")";
        return new IllegalArgumentException(argument + " is not valid JSON" + where);
    }

    /**
     * What may be said about {@code e} to the caller: its message when it is a
     * failure {@link #isCallerFacing} lets through, else {@code fallback}. The one
     * way a tool puts an exception's text into a result; {@code
     * McpToolErrorLeakScanTest} flags the raw {@code getMessage()} forms.
     */
    static String safeMessage(Throwable e, String fallback) {
        return isCallerFacing(e) ? describe(e) : fallback;
    }

    /**
     * A failure whose message a tool authored for its caller — a missing trigger,
     * an empty reply — and that {@link #isCallerFacing} therefore lets through.
     */
    static final class CallerFacingException extends RuntimeException {

        CallerFacingException(String message) {
            super(message);
        }
    }

    /** Whether a failure is one the caller made and may be told about verbatim. */
    static boolean isCallerFacing(Throwable e) {
        return e instanceof ClientErrorException || (e instanceof IllegalArgumentException && thrownByEddi(e)) || e instanceof CallerFacingException
        // The conversation service's own refusals ("Agent not deployed", the turn's
        // conversation has ended, the input is too large): authored for the caller.
                || e instanceof IConversationService.AgentNotReadyException || e instanceof IConversationService.AgentMismatchException
                || e instanceof IConversationService.ConversationEndedException || e instanceof IConversationService.InputTooLargeException
                || e instanceof IConversationService.ConversationNotFoundException
                // Refusals EDDI raises itself, with text written for the caller: the setup
                // wizard's validation, a cascade request that cannot be applied, a spend
                // control refusal, a review validation, a malformed review argument, a quota
                // refusal. Listed by type on purpose: a new exception is not caller-facing
                // until someone decides it is.
                || e instanceof AgentSetupService.AgentSetupException || e instanceof AgentCascadeService.CascadeException
                || e instanceof SpendControlException || e instanceof ReviewRefusalException || e instanceof McpReviewArguments.BadArgument
                || e instanceof QuotaRefusal
                || e instanceof IResourceStore.ResourceNotFoundException || e instanceof IResourceStore.ResourceModifiedException
                // A discussion refusal the engine authored ("no phases are defined", the
                // depth limit) — but not one that wraps an underlying failure, whose
                // message is that failure's text.
                || (e instanceof IGroupConversationService.GroupDiscussionException && e.getCause() == null);
    }

    /**
     * Whether an exception was raised by EDDI's own code rather than by a library
     * it called. EDDI's validation refusals ("members[1] repeats members[0]…") are
     * written for the caller; an {@link IllegalArgumentException} a library raises
     * is not — Jackson's names model classes and field paths, a
     * {@code java.nio.file.InvalidPathException} quotes a server path, a
     * {@code NumberFormatException} quotes whatever it failed to parse. Decided by
     * the frame that constructed the exception: the top of its stack trace.
     */
    static boolean thrownByEddi(Throwable e) {
        StackTraceElement[] trace = e.getStackTrace();
        return trace != null && trace.length > 0 && trace[0].getClassName().startsWith("ai.labs.eddi.");
    }

    /**
     * A throwable's message, or its class's simple name when it has none.
     * <p>
     * A <strong>client error</strong> ({@link ClientErrorException}, i.e. 4xx) is
     * unwrapped to its response entity first. These tools call EDDI's own REST
     * stores in-process, and a JAX-RS exception built from a {@code Response}
     * carries the useful text in that entity while {@code getMessage()} returns
     * only the status line — so a precise "Unknown field 'setProperties' … Known
     * fields: [setOnActions]" would otherwise reach the MCP client as "HTTP 400 Bad
     * Request", which is exactly the MCP/REST parity this exists to preserve.
     * <p>
     * Only 4xx. A 4xx entity is a message this codebase authored <em>for</em> the
     * caller, describing what the caller got wrong. A 5xx entity is not written to
     * that contract and can carry endpoint, datastore or configuration detail, so
     * it falls through to the message below and is never lifted verbatim. Every MCP
     * tool that reaches this point is already behind {@code requireRole}, but "the
     * caller is an admin" is a reason to answer usefully, not a reason to stop
     * distinguishing the two.
     */
    static String describe(Throwable cause) {
        if (cause == null) {
            return "unknown cause";
        }
        if (cause instanceof IConversationService.AgentMismatchException) {
            // Its message quotes the agent id stored with the conversation — which agent
            // a conversation the caller named belongs to is not theirs to learn.
            return "The conversation does not belong to the given agent";
        }
        if (cause instanceof ClientErrorException clientError) {
            var response = clientError.getResponse();
            if (response != null && response.hasEntity() && response.getEntity() instanceof String entity && !entity.isBlank()) {
                return entity;
            }
        }
        String message = cause.getLocalizedMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    /**
     * A schedule fire log's {@code errorMessage} as a tool may show it. The fire
     * executor writes either a sentence of its own ("Conversation ended in state
     * …") or, for a thrown failure, {@code "<ExceptionSimpleName>: <its message>"}
     * — a driver's or provider's text, which a tool never repeats. That form keeps
     * the exception type and loses the text; the full text stays in the fire log
     * (REST) and the server log.
     */
    static String fireErrorForCaller(String errorMessage) {
        if (errorMessage == null) {
            return null;
        }
        int colon = errorMessage.indexOf(": ");
        if (colon > 0) {
            String head = errorMessage.substring(0, colon);
            if (head.matches("[A-Z][A-Za-z0-9_$]*(Exception|Error|Throwable)")) {
                return head + " (details in the server log)";
            }
        }
        return errorMessage;
    }

    /** A fire log with its {@code errorMessage} as {@link #fireErrorForCaller}. */
    static ScheduleFireLog fireLogForCaller(ScheduleFireLog log) {
        if (log == null || log.errorMessage() == null) {
            return log;
        }
        return new ScheduleFireLog(log.id(), log.scheduleId(), log.fireId(), log.fireTime(), log.startedAt(), log.completedAt(), log.status(),
                log.instanceId(), log.conversationId(), fireErrorForCaller(log.errorMessage()), log.attemptNumber(), log.cost());
    }

    /**
     * The tool result for a {@link Response} an in-process REST bean returned
     * instead of throwing: the serialized entity on a 2xx ({@code {"status": n}}
     * when there is none), otherwise {@link #errorJson(String, String, Map)} with
     * the resource's own text. A body {@code {"error": "<CODE>", "message": "…"}}
     * keeps its code; a body {@code {"error": "<sentence>"}} — the shape of the
     * team-handoff and team-budget resources — or a plain-text body — the shape of
     * the schedule resource's refusals — keeps the sentence, and the code follows
     * the status (400 {@code BAD_REQUEST}, 403 {@code FORBIDDEN}, 404
     * {@code NOT_FOUND}, 409 {@code CONFLICT}, 503 {@code UNAVAILABLE}, 504
     * {@code TIMEOUT}, anything else {@code INTERNAL}). Either body is one the
     * resource built and returned, not an exception's text, so it is kept on a 5xx
     * too. A 5xx without a body says only that the request failed: the resource has
     * logged it.
     */
    static String responseJson(IJsonSerialization jsonSerialization, Response response) throws IOException {
        if (response == null) {
            return errorJson("The request returned no response", "INTERNAL", null);
        }
        int status = response.getStatus();
        Object entity = response.getEntity();
        if (status >= 200 && status < 300) {
            return entity == null ? "{\"status\":" + status + "}" : jsonSerialization.serialize(entity);
        }
        String code = switch (status) {
            case 400 -> "BAD_REQUEST";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 409 -> "CONFLICT";
            case 503 -> "UNAVAILABLE";
            case 504 -> "TIMEOUT";
            default -> "INTERNAL";
        };
        String message = "The request failed (HTTP " + status + ")";
        if (entity instanceof Map<?, ?> body && body.get("error") instanceof String error && !error.isBlank()) {
            if (body.get("message") instanceof String text && !text.isBlank()) {
                code = error;
                message = text;
            } else {
                message = error;
            }
        } else if (entity instanceof String text && !text.isBlank()) {
            message = text;
        }
        return errorJson(message, code, null);
    }

    /**
     * Structured error JSON for tools whose callers need to branch on the failure
     * kind (e.g. NOT_FOUND vs WRONG_STATE vs FORBIDDEN vs DISABLED vs BAD_REQUEST).
     * Manual construction — like {@link #errorJson(String)} it can never throw on
     * the error path. {@code errorCode} and {@code details} may be null/blank/empty
     * (omitted when so).
     */
    static String errorJson(String message, String errorCode, Map<String, String> details) {
        var sb = new StringBuilder();
        sb.append("{\"error\":\"").append(escapeJsonString(message)).append("\"");
        if (errorCode != null && !errorCode.isBlank()) {
            sb.append(",\"errorCode\":\"").append(escapeJsonString(errorCode)).append("\"");
        }
        if (details != null && !details.isEmpty()) {
            sb.append(",\"details\":{");
            boolean first = true;
            for (var entry : details.entrySet()) {
                if (!first) {
                    sb.append(",");
                }
                sb.append("\"").append(escapeJsonString(entry.getKey())).append("\":\"")
                        .append(escapeJsonString(entry.getValue())).append("\"");
                first = false;
            }
            sb.append("}");
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Escape a string for safe inclusion in a JSON string value. Handles all
     * JSON-special characters per RFC 8259.
     */
    static String escapeJsonString(String input) {
        if (input == null) {
            return "";
        }
        var sb = new StringBuilder(input.length() + 16);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * Extract the resource ID from a Location header like
     * "/store/resources/{id}?version=1".
     */
    static String extractIdFromLocation(String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        String path = location.contains("?") ? location.substring(0, location.indexOf('?')) : location;
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 && lastSlash < path.length() - 1 ? path.substring(lastSlash + 1) : null;
    }

    /**
     * Extract the version from a Location header like
     * "/store/resources/{id}?version=1". Returns 1 if not found.
     */
    static int extractVersionFromLocation(String location) {
        if (location == null || !location.contains("version=")) {
            return 1;
        }
        try {
            int idx = location.indexOf("version=") + "version=".length();
            int end = location.indexOf('&', idx);
            String ver = end > 0 ? location.substring(idx, end) : location.substring(idx);
            return Integer.parseInt(ver.trim());
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
