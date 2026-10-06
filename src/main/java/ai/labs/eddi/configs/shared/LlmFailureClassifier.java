/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.shared;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.ContentFilteredException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.RetriableException;
import jakarta.ws.rs.WebApplicationException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a {@link Throwable} from an LLM or MCP call into an {@link LlmFailure}:
 * a {@link FailureClass}, the provider's suggested retry delay (when it gave
 * one) and a short reason.
 *
 * <h2>Where the signal comes from</h2>
 * <p>
 * langchain4j 1.20 surfaces a provider failure as a typed exception
 * ({@code RateLimitException}, {@code InvalidRequestException}, ...) whose
 * <em>cause</em> is a {@link HttpException} carrying exactly two things: the
 * numeric {@code statusCode()} and the response <b>body as the message</b>. The
 * response headers are not retained, so an HTTP {@code Retry-After} header is
 * not reachable; the provider's own hint is read from the body instead (Gemini
 * {@code RetryInfo.retryDelay}, or the "retry in 13s" sentence OpenAI and
 * Gemini put in the message). The typed exception alone cannot tell a rate
 * limit from a spent quota — both are a 429 — which is why the body is parsed.
 * </p>
 *
 * <h2>Order of evidence</h2>
 * <ol>
 * <li>HTTP status + provider error body, anywhere in the cause chain;</li>
 * <li>a provider error JSON embedded in some exception message;</li>
 * <li>langchain4j's typed exceptions;</li>
 * <li>untyped transport exceptions (socket timeout, connect, DNS);</li>
 * <li>transient wording in a message — the weakest signal, last.</li>
 * </ol>
 * <p>
 * Each step walks the <em>whole</em> chain before the next gets a say, so a
 * retryable-sounding message on an outer wrapper never outvotes a typed
 * authentication failure underneath it.
 * </p>
 */
public final class LlmFailureClassifier {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_CHAIN_DEPTH = 20;
    private static final long MAX_RETRY_AFTER_MS = 24L * 60 * 60 * 1000;

    /** Provider wording for "the prompt does not fit the model". */
    private static final Pattern CONTEXT_TOO_LONG = Pattern.compile(
            "input token count exceeds|context[_ ]length[_ ]exceeded|prompt is too long|maximum context length"
                    + "|exceeds the maximum number of tokens|reduce the length of the messages|context window",
            Pattern.CASE_INSENSITIVE);

    /**
     * Transient wording — the last-resort signal for providers that surface a
     * failure only as prose. Word boundaries keep {@code 429} from matching inside
     * an id or a token count.
     */
    private static final Pattern TRANSIENT_MESSAGE = Pattern.compile(
            "timeout|timed out|rate limit|too many requests|connection refused|connection reset"
                    + "|service unavailable|bad gateway|gateway timeout|internal server error|overloaded"
                    + "|\\b429\\b|\\b50[234]\\b",
            Pattern.CASE_INSENSITIVE);

    /** "Please retry in 13.04s." (Gemini), "Please try again in 20ms." (OpenAI). */
    private static final Pattern RETRY_IN_MESSAGE = Pattern.compile("(?:retry|try again) in (\\d+(?:\\.\\d+)?)\\s*(ms|s|m)(?![a-z])",
            Pattern.CASE_INSENSITIVE);

    /** A protobuf JSON duration such as {@code "13s"} or {@code "1.5s"}. */
    private static final Pattern DURATION = Pattern.compile("^\\s*(\\d+(?:\\.\\d+)?)\\s*(ms|s|m|h)?\\s*$", Pattern.CASE_INSENSITIVE);

    private LlmFailureClassifier() {
    }

    /**
     * Classifies a failure. Never throws; a {@code null} input is
     * {@link FailureClass#UNKNOWN}.
     */
    public static LlmFailure classify(Throwable failure) {
        if (failure == null) {
            return new LlmFailure(FailureClass.UNKNOWN, null, "no exception");
        }

        // 1. HTTP status + body (outermost carrier wins among carriers).
        for (Throwable current : chain(failure)) {
            Integer status = null;
            if (current instanceof HttpException http) {
                status = http.statusCode();
            } else if (current instanceof WebApplicationException wae && wae.getResponse() != null) {
                status = wae.getResponse().getStatus();
            }
            if (status != null) {
                LlmFailure verdict = classifyHttp(status, current.getMessage());
                if (verdict != null) {
                    return verdict;
                }
            }
        }

        // 2. A provider error JSON in some message, without an HTTP status carrier.
        for (Throwable current : chain(failure)) {
            JsonNode body = parseJson(current.getMessage());
            if (hasErrorObject(body)) {
                LlmFailure verdict = classifyHttp(-1, current.getMessage());
                if (verdict != null) {
                    return verdict;
                }
            }
        }

        // 3. langchain4j's typed verdict.
        for (Throwable current : chain(failure)) {
            LlmFailure typed = classifyTyped(current, failure);
            if (typed != null) {
                return typed;
            }
        }

        // 4. Untyped transport failures.
        for (Throwable current : chain(failure)) {
            if (current instanceof SocketTimeoutException || current instanceof HttpTimeoutException
                    || current instanceof TimeoutException) {
                return new LlmFailure(FailureClass.TIMEOUT, null, "timeout (" + current.getClass().getSimpleName() + ")");
            }
            if (current instanceof ConnectException || current instanceof UnknownHostException) {
                return new LlmFailure(FailureClass.TRANSIENT, null, "connection failure (" + current.getClass().getSimpleName() + ")");
            }
        }

        // 5. Transient wording in a message.
        for (Throwable current : chain(failure)) {
            String message = current.getMessage();
            if (message != null) {
                Matcher matcher = TRANSIENT_MESSAGE.matcher(message);
                if (matcher.find()) {
                    String hit = matcher.group().toLowerCase(Locale.ROOT);
                    FailureClass cls = hit.contains("timeout") || hit.contains("timed out") || hit.contains("504")
                            ? FailureClass.TIMEOUT
                            : hit.contains("rate") || hit.contains("many") || hit.equals("429")
                                    ? FailureClass.RATE_LIMITED
                                    : FailureClass.TRANSIENT;
                    return new LlmFailure(cls, retryInHint(message), "message mentions \"" + hit + "\"");
                }
            }
        }

        return new LlmFailure(FailureClass.UNKNOWN, null, "unrecognised failure (" + failure.getClass().getSimpleName() + ")");
    }

    // ------------------------------------------------------------------ HTTP

    /**
     * Classifies from a numeric status (or {@code -1} when none is known) and the
     * provider's response body, or returns {@code null} when neither says anything
     * the classifier recognises.
     */
    private static LlmFailure classifyHttp(int status, String body) {
        JsonNode root = parseJson(body);
        JsonNode error = errorNode(root);

        String errStatus = text(error, "status");
        String errType = text(error, "type");
        String errCode = text(error, "code");
        String errMessage = text(error, "message");
        String signals = (errStatus + " " + errType + " " + errCode).toLowerCase(Locale.ROOT);
        String lowerBody = body == null ? "" : body.toLowerCase(Locale.ROOT);

        int effectiveStatus = status;
        if (effectiveStatus <= 0 && error.path("code").isInt()) {
            effectiveStatus = error.path("code").asInt();
        }

        // Quota first: it is a 429 (or a 403) that must NOT be retried.
        if (signals.contains("insufficient_quota") || lowerBody.contains("insufficient_quota")
                || signals.contains("billing_hard_limit_reached")) {
            return new LlmFailure(FailureClass.QUOTA_EXHAUSTED, null, "insufficient_quota");
        }
        String perDayQuota = perDayQuotaViolation(error);
        if (perDayQuota != null) {
            return new LlmFailure(FailureClass.QUOTA_EXHAUSTED, null, "per-day quota exhausted (" + perDayQuota + ")");
        }

        // Body signals decide only when the HTTP status is unknown; a known non-429
        // status (e.g. a 503 whose body mentions rate limits) takes its own branch
        // below.
        if (effectiveStatus == 429 || (effectiveStatus <= 0 && (signals.contains("resource_exhausted") || signals.contains("rate_limit")))) {
            Long retryAfter = retryDelayMs(error);
            if (retryAfter == null) {
                retryAfter = retryInHint(errMessage.isEmpty() ? body : errMessage);
            }
            return new LlmFailure(FailureClass.RATE_LIMITED, retryAfter, "rate limited (" + describe(effectiveStatus, signals) + ")");
        }

        boolean contextCandidate = effectiveStatus <= 0 || effectiveStatus == 400 || effectiveStatus == 413 || effectiveStatus == 422;
        if (contextCandidate && (signals.contains("context_length_exceeded")
                || CONTEXT_TOO_LONG.matcher(errMessage.isEmpty() ? lowerBody : errMessage).find())) {
            return new LlmFailure(FailureClass.CONTEXT_TOO_LONG, null, "context too long");
        }

        if (signals.contains("deadline_exceeded") || effectiveStatus == 408 || effectiveStatus == 504) {
            return new LlmFailure(FailureClass.TIMEOUT, null, "timeout (" + describe(effectiveStatus, signals) + ")");
        }
        // Substring signals count only when the status is unknown or 5xx: an explicit
        // 4xx
        // outranks them ("internal_error" on a 400, "model_unavailable" on a 404 are
        // not transient).
        boolean transientSignal = (effectiveStatus <= 0 || effectiveStatus >= 500) && (signals.contains("overloaded")
                || signals.contains("unavailable") || signals.contains("internal") || signals.contains("api_error"));
        if (transientSignal
                || effectiveStatus == 500 || effectiveStatus == 502 || effectiveStatus == 503 || effectiveStatus == 529) {
            return new LlmFailure(FailureClass.TRANSIENT, null, "provider error (" + describe(effectiveStatus, signals) + ")");
        }
        if (effectiveStatus == 401 || effectiveStatus == 403 || signals.contains("unauthenticated") || signals.contains("permission_denied")
                || signals.contains("authentication_error") || signals.contains("permission_error") || signals.contains("invalid_api_key")
                || lowerBody.contains("api_key_invalid") || lowerBody.contains("api key not valid")) {
            return new LlmFailure(FailureClass.AUTH, null, "authentication/authorisation (" + describe(effectiveStatus, signals) + ")");
        }
        if (effectiveStatus == 404 || signals.contains("not_found") || signals.contains("model_not_found")) {
            return new LlmFailure(FailureClass.MODEL_NOT_FOUND, null, "model not found (" + describe(effectiveStatus, signals) + ")");
        }
        if (effectiveStatus == 400 || effectiveStatus == 413 || effectiveStatus == 422 || signals.contains("invalid_argument")
                || signals.contains("invalid_request") || signals.contains("failed_precondition")) {
            return new LlmFailure(FailureClass.BAD_REQUEST, null, "bad request (" + describe(effectiveStatus, signals) + ")");
        }
        if (effectiveStatus >= 500 && effectiveStatus < 600) {
            return new LlmFailure(FailureClass.TRANSIENT, null, "provider error (" + effectiveStatus + ")");
        }
        if (effectiveStatus >= 400 && effectiveStatus < 500) {
            return new LlmFailure(FailureClass.BAD_REQUEST, null, "client error (" + effectiveStatus + ")");
        }
        return null;
    }

    private static String describe(int status, String signals) {
        String trimmed = signals.trim().replaceAll("\\s+", " ");
        return status > 0 ? (trimmed.isEmpty() ? String.valueOf(status) : status + " " + trimmed) : trimmed;
    }

    /**
     * Gemini reports the violated quota in {@code error.details[]} as a
     * {@code QuotaFailure}; a quota whose id or metric names a per-day limit does
     * not refill within a retry window. Returns the offending quota id, or
     * {@code null} when none is per-day.
     */
    private static String perDayQuotaViolation(JsonNode error) {
        for (JsonNode detail : error.path("details")) {
            if (!text(detail, "@type").endsWith("QuotaFailure")) {
                continue;
            }
            for (JsonNode violation : detail.path("violations")) {
                String quotaId = text(violation, "quotaId");
                String descriptor = (quotaId + " " + text(violation, "quotaMetric") + " " + text(violation, "description"))
                        .toLowerCase(Locale.ROOT);
                if (descriptor.contains("perday") || descriptor.contains("per_day") || descriptor.contains("per day")
                        || descriptor.contains("daily")) {
                    return quotaId.isEmpty() ? "per-day" : quotaId;
                }
            }
        }
        return null;
    }

    /** {@code RetryInfo.retryDelay} from {@code error.details[]}, in ms. */
    private static Long retryDelayMs(JsonNode error) {
        for (JsonNode detail : error.path("details")) {
            if (!text(detail, "@type").endsWith("RetryInfo")) {
                continue;
            }
            JsonNode delay = detail.path("retryDelay");
            if (delay.isTextual()) {
                Matcher matcher = DURATION.matcher(delay.asText());
                if (matcher.matches()) {
                    return toMillis(matcher.group(1), matcher.group(2));
                }
            } else if (delay.isObject()) {
                double seconds = delay.path("seconds").asDouble(0) + delay.path("nanos").asDouble(0) / 1_000_000_000d;
                return toMillis(seconds, "s");
            }
        }
        return null;
    }

    private static Long retryInHint(String message) {
        if (message == null) {
            return null;
        }
        Matcher matcher = RETRY_IN_MESSAGE.matcher(message);
        return matcher.find() ? toMillis(matcher.group(1), matcher.group(2)) : null;
    }

    /**
     * As {@link #toMillis(double, String)} for a number still in text form: a value
     * that does not parse yields {@code null} (no delay inferred) rather than an
     * exception on the classification path.
     */
    private static Long toMillis(String number, String unit) {
        try {
            return toMillis(Double.parseDouble(number), unit);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Converts to milliseconds, capped at {@link #MAX_RETRY_AFTER_MS}. A value that
     * overflows to infinity (hundreds of digits) or is not a number is treated as
     * the cap, never as a short delay.
     */
    private static long toMillis(double value, String unit) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return MAX_RETRY_AFTER_MS;
        }
        String u = unit == null ? "s" : unit.toLowerCase(Locale.ROOT);
        double factor = switch (u) {
            case "ms" -> 1d;
            case "m" -> 60_000d;
            case "h" -> 3_600_000d;
            default -> 1000d;
        };
        return Math.min(MAX_RETRY_AFTER_MS, (long) Math.ceil(value * factor));
    }

    // ----------------------------------------------------------------- typed

    private static LlmFailure classifyTyped(Throwable current, Throwable outermost) {
        if (current instanceof AuthenticationException) {
            return new LlmFailure(FailureClass.AUTH, null, "authentication failure");
        }
        if (current instanceof ModelNotFoundException) {
            return new LlmFailure(FailureClass.MODEL_NOT_FOUND, null, "model not found");
        }
        // ContentFilteredException is an InvalidRequestException: test it first, or it
        // is unreachable.
        if (current instanceof ContentFilteredException) {
            return new LlmFailure(FailureClass.UNKNOWN, null, "content filtered");
        }
        if (current instanceof InvalidRequestException) {
            for (Throwable t : chain(outermost)) {
                String message = t.getMessage();
                if (message != null && CONTEXT_TOO_LONG.matcher(message).find()) {
                    return new LlmFailure(FailureClass.CONTEXT_TOO_LONG, null, "context too long");
                }
            }
            return new LlmFailure(FailureClass.BAD_REQUEST, null, "invalid request");
        }
        if (current instanceof RateLimitException) {
            return new LlmFailure(FailureClass.RATE_LIMITED, retryInHint(current.getMessage()), "rate limited");
        }
        // langchain4j's own TimeoutException shares its simple name with the JDK's
        // (matched in the transport step below), so it is recognised by name here.
        if (current instanceof RetriableException && "TimeoutException".equals(current.getClass().getSimpleName())) {
            return new LlmFailure(FailureClass.TIMEOUT, null, "provider timeout");
        }
        if (current instanceof InternalServerException) {
            return new LlmFailure(FailureClass.TRANSIENT, null, "provider internal error");
        }
        if (current instanceof RetriableException) {
            return new LlmFailure(FailureClass.TRANSIENT, null, "retriable (" + current.getClass().getSimpleName() + ")");
        }
        if (current instanceof NonRetriableException) {
            return new LlmFailure(FailureClass.UNKNOWN, null, "non-retriable (" + current.getClass().getSimpleName() + ")");
        }
        return null;
    }

    // --------------------------------------------------------------- helpers

    /** Outermost-first cause chain, bounded and cycle-safe. */
    private static List<Throwable> chain(Throwable root) {
        List<Throwable> out = new ArrayList<>();
        for (Throwable t = root; t != null && out.size() < MAX_CHAIN_DEPTH; t = t.getCause()) {
            out.add(t);
            if (t.getCause() == t) {
                break;
            }
        }
        return out;
    }

    /** Parses the JSON object (else array) embedded in a message, or null. */
    private static JsonNode parseJson(String text) {
        if (text == null) {
            return null;
        }
        JsonNode parsed = parseSpan(text, '{', '}');
        return parsed != null ? parsed : parseSpan(text, '[', ']');
    }

    private static JsonNode parseSpan(String text, char open, char close) {
        int start = text.indexOf(open);
        int end = text.lastIndexOf(close);
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return MAPPER.readTree(text.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Whether a parsed body is a provider error envelope ({@code {"error": ...}}).
     */
    private static boolean hasErrorObject(JsonNode body) {
        if (body == null) {
            return false;
        }
        JsonNode node = body.isArray() && !body.isEmpty() ? body.get(0) : body;
        return node.path("error").isObject();
    }

    /**
     * The object holding {@code status/type/code/message/details}: {@code error}
     * for all three providers (Gemini streaming wraps it in an array), else the
     * body itself.
     */
    private static JsonNode errorNode(JsonNode root) {
        if (root == null) {
            return MAPPER.missingNode();
        }
        JsonNode node = root.isArray() && !root.isEmpty() ? root.get(0) : root;
        JsonNode error = node.path("error");
        return error.isObject() ? error : node;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }
}
