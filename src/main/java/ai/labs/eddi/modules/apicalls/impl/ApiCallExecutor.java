/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import ai.labs.eddi.configs.apicalls.model.*;
import ai.labs.eddi.configs.apicalls.model.HttpPostResponse;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.connections.ConnectionException;
import ai.labs.eddi.connections.ConnectionResolver;
import ai.labs.eddi.connections.model.ConnectionReference;
import ai.labs.eddi.engine.security.CallerIdentityResolver;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.httpclient.IHttpClient;
import ai.labs.eddi.engine.httpclient.IRequest;
import ai.labs.eddi.engine.httpclient.IResponse;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.SecretValueScrubber;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.modules.llm.tools.UrlValidationUtils;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import ai.labs.eddi.utils.LogSanitizer;
import ai.labs.eddi.secrets.ConfigReferenceGuard;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.model.SecretReference;
import ai.labs.eddi.secrets.sanitize.SecretRedactionFilter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;

import java.io.IOException;
import static ai.labs.eddi.utils.MatchingUtilities.executeValuePath;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;
import static jakarta.ws.rs.core.MediaType.TEXT_PLAIN;
import static java.lang.String.format;
import static java.lang.System.currentTimeMillis;

/**
 * Reusable HTTP call executor that can be used by different lifecycle tasks.
 * Extracted from ApiCallsTask to enable reuse in AI agent tools.
 */
@ApplicationScoped
public class ApiCallExecutor implements IApiCallExecutor {
    private static final String UTF_8 = "utf-8";
    private static final String CONTENT_TYPE_APPLICATION_JSON = "application/json";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String KEY_HTTP_CALLS = "httpCalls";
    private static final String SLASH_CHAR = "/";

    private static final Logger LOGGER = Logger.getLogger(ApiCallExecutor.class);

    /**
     * Hard ceiling for a single retry backoff delay. A retry sleeps on the
     * conversation thread, so the delay must stay well inside the turn budget — a
     * per-call {@code maxBackoffDelayInMillis} may lower this, never raise it.
     */
    static final int MAX_BACKOFF_MILLIS = 30_000;

    /**
     * Hard transport ceiling for a response body, matching the http client's own
     * historical default. It is deliberately kept <em>above</em> the memory cap
     * ({@code maxResponseSizeInBytes}): the client rejects anything larger outright
     * — there is no way to keep a partial body — while everything between the
     * memory cap and this ceiling is truncated by
     * {@link #truncateResponseBody(String, int, String)} before it reaches
     * conversation memory. Handing the client the memory cap instead would make
     * that truncation unreachable and turn an oversize response into a failed turn.
     */
    static final int MAX_TRANSPORT_RESPONSE_SIZE_BYTES = 8 * 1024 * 1024;

    /**
     * Response headers that are credentials, and are dropped before the header map
     * reaches conversation memory, the template data or an LLM tool result.
     * <p>
     * Choosing which headers to BIND is not the same control as choosing which to
     * STORE, and only the second one closes this. An operation can qualify for
     * header capture on its documented 201 and still answer some other call with a
     * {@code Set-Cookie} — the error path especially — so gating on the declared
     * status alone leaves the live session cookie flowing into persisted memory and
     * the model's context.
     * <p>
     * {@code Set-Cookie} is the case that matters: {@code HttpClientModule} builds
     * a cookie-aware, application-scoped {@code WebClientSession}, so that value is
     * a session credential EDDI is actively replaying, and {@code HttpOnly} exists
     * precisely to keep such values out of scriptable — here, prompt-injectable —
     * context. The authenticate headers carry challenge material with the same
     * property.
     * <p>
     * A deny-list rather than an allow-list, deliberately: the useful header on any
     * given API is not knowable here ({@code Location}, {@code ETag}, a pagination
     * cursor, a rate-limit budget, some vendor {@code X-*}), and an allow-list
     * would silently break every hand-authored config templating one of those. What
     * IS knowable is the small closed set that is never data.
     */
    private static final Set<String> CREDENTIAL_RESPONSE_HEADERS = Set.of(
            "set-cookie", "set-cookie2", "authorization", "proxy-authorization",
            "www-authenticate", "proxy-authenticate",
            // RFC 7615: server-authentication material (rspauth, nextnonce) —
            // challenge-response state, never data.
            "authentication-info", "proxy-authentication-info");

    private final IHttpClient httpClient;
    private final IJsonSerialization jsonSerialization;
    private final IRuntime runtime;
    private final PrePostUtils prePostUtils;
    private final GlobalVariableResolver globalVariableResolver;
    private final SecretResolver secretResolver;
    private final CallerIdentityResolver callerIdentityResolver;
    private final CallerIdentityContext callerIdentityContext;
    private final RequestRedactor requestRedactor;
    private final ConnectionResolver connectionResolver;
    private final boolean ssrfProtectionEnabled;
    private final long defaultTimeoutInMillis;
    private final int defaultMaxResponseSizeInBytes;

    @Inject
    public ApiCallExecutor(IHttpClient httpClient, IJsonSerialization jsonSerialization, IRuntime runtime, PrePostUtils prePostUtils,
            GlobalVariableResolver globalVariableResolver, SecretResolver secretResolver, CallerIdentityResolver callerIdentityResolver,
            CallerIdentityContext callerIdentityContext, RequestRedactor requestRedactor, ConnectionResolver connectionResolver,
            @ConfigProperty(name = "eddi.security.ssrf-protection.enabled", defaultValue = "false") boolean ssrfProtectionEnabled,
            @ConfigProperty(name = "eddi.httpcalls.default-timeout-millis", defaultValue = "30000") long defaultTimeoutInMillis,
            @ConfigProperty(name = "eddi.httpcalls.default-max-response-size-bytes", defaultValue = "2000000") int defaultMaxResponseSizeInBytes) {
        this.httpClient = httpClient;
        this.jsonSerialization = jsonSerialization;
        this.runtime = runtime;
        this.prePostUtils = prePostUtils;
        this.globalVariableResolver = globalVariableResolver;
        this.secretResolver = secretResolver;
        this.callerIdentityResolver = callerIdentityResolver;
        this.callerIdentityContext = callerIdentityContext;
        this.requestRedactor = requestRedactor;
        this.connectionResolver = connectionResolver;
        this.ssrfProtectionEnabled = ssrfProtectionEnabled;
        this.defaultTimeoutInMillis = defaultTimeoutInMillis;
        this.defaultMaxResponseSizeInBytes = defaultMaxResponseSizeInBytes;
    }

    /**
     * The response headers, minus {@link #CREDENTIAL_RESPONSE_HEADERS}.
     * <p>
     * Case-insensitive, because {@code HttpClientWrapper.convertHeaderToMap}
     * preserves whatever casing the wire used and HTTP/2 mandates lowercase — a
     * filter keyed on {@code "Set-Cookie"} would miss {@code set-cookie} and defend
     * nothing over h2.
     */
    static Map<String, String> withoutCredentialHeaders(Map<String, String> headers) {
        var filtered = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        if (headers == null) {
            return filtered;
        }
        headers.forEach((name, value) -> {
            if (name != null && !CREDENTIAL_RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                filtered.put(name, value);
            }
        });
        return filtered;
    }

    @Override
    public Map<String, Object> execute(ApiCall call, IConversationMemory memory, Map<String, Object> templateDataObjects, String targetServerUrl)
            throws LifecycleException {
        if (call == null) {
            throw new IllegalArgumentException("call cannot be null");
        }
        if (memory == null) {
            throw new IllegalArgumentException("memory cannot be null");
        }
        if (templateDataObjects == null) {
            throw new IllegalArgumentException("templateDataObjects cannot be null");
        }
        if (targetServerUrl == null || targetServerUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("targetServerUrl cannot be null or empty");
        }

        // Every plaintext any attempt substituted, so a failure message that quotes the
        // request (a URI, a header) is redacted before it is logged or becomes the
        // task error the turn records.
        Set<String> substitutedSecrets = new HashSet<>();
        try {
            IWritableConversationStep currentStep = memory.getCurrentStep();

            var preRequest = call.getPreRequest();
            templateDataObjects = prePostUtils.executePreRequestPropertyInstructions(memory, templateDataObjects, preRequest);

            if (call.getFireAndForget()) {
                executeFireAndForgetCalls(targetServerUrl, call, templateDataObjects, conversationPropertiesOf(memory));
                return Collections.emptyMap();
            } else {
                IRequest request;
                IResponse response = null;
                boolean retryCall = false;
                int amountOfExecutions = 0;
                // LinkedHashMap, not HashMap: this map is serialized verbatim as the
                // LLM tool result and truncated from the front, so key order decides
                // what survives a cap. See the ordered "headers" insert below.
                Map<String, Object> result = new LinkedHashMap<>();

                do {
                    // Final attempt wins, entirely: a retried failure populated
                    // "body"/"httpCode" on its pass through the loop, and a
                    // succeeding retry that does NOT save its response would
                    // otherwise inherit the failed attempt's error body next to
                    // its own 2xx code — a self-contradictory tool result.
                    result.clear();
                    // Re-read per attempt rather than hoisted: a post-response property
                    // instruction can write a property between attempts, and the guard has to
                    // judge the request it is actually about to send.
                    BuiltRequest built = buildRequest(targetServerUrl, call, templateDataObjects, conversationPropertiesOf(memory));
                    substitutedSecrets.addAll(built.resolvedSecrets());
                    request = built.request();
                    var objectName = call.getName() + "Request";
                    var requestMap = request.toMap();
                    // Scrub resolved secrets — headers, query parameters and body — from
                    // the request map before it is persisted to conversation memory. The
                    // actual request was already built and still carries them; each entry
                    // here is REPLACED with a redacted copy, so this only affects the debug
                    // record. Shares RequestRedactor with the approval preview so the two
                    // cannot disagree about what counts as a credential — including which
                    // headers a connection filled, which no name or value heuristic can
                    // recognise on its own.
                    requestRedactor.redactRequestMap(requestMap, built.connectionOwnedHeaders());
                    // By value as well: a vault secret need not look like one, and the
                    // executor knows exactly which plaintexts it substituted.
                    RequestRedactor.redactResolvedSecrets(requestMap, built.resolvedSecrets());
                    prePostUtils.createMemoryEntry(currentStep, requestMap, objectName, KEY_HTTP_CALLS);
                    response = executeAndMeasureRequest(call, request, built.resolvedSecrets(), retryCall, amountOfExecutions);

                    var isResponseSuccessful = response.getHttpCode() >= 200 && response.getHttpCode() < 300;
                    if (!isResponseSuccessful) {
                        String message = "ApiCall (%s) didn't return http code 2xx, instead %s.";
                        LOGGER.warn(format(message, call.getName(), response.getHttpCode()));
                        String httpCodeMessage = RequestRedactor.redactResolvedSecrets(response.getHttpCodeMessage(), built.resolvedSecrets());
                        LOGGER.warn("Error Msg:" + httpCodeMessage);

                        // A server that rejects a credential routinely echoes it ("invalid
                        // api key sk-…"). The executor knows exactly which plaintexts it
                        // sent, so those are removed by value before the body goes
                        // anywhere — memory, template data, the tool result or a log.
                        String errorBody = RequestRedactor.redactResolvedSecrets(response.getContentAsString(), built.resolvedSecrets());
                        String truncatedError = errorBody != null && errorBody.length() > 2000
                                ? errorBody.substring(0, 2000)
                                : errorBody;

                        // The returned map is what an LLM tool call receives as its result
                        // (HttpCallToolsProvider serializes it verbatim). It used to stay
                        // EMPTY on a non-2xx, so the model was handed "{}" for a failed
                        // call — with no httpCode and no error it could neither report the
                        // failure nor tell it apart from success, and a human-approved
                        // setupAgent that 400'd looked exactly like one that worked. Same
                        // keys as the success path ("body"/"httpCode"), not a new "error"
                        // namespace: ApiCallsTask merges this map into template data,
                        // where that vocabulary is already established.
                        result.put("httpCode", response.getHttpCode());
                        // REDACTED before it reaches the model: an error body is
                        // server-authored text, and a 401/403 routinely echoes the
                        // credential that failed ("invalid api key sk-…"). The
                        // success path stays untouched — response bodies are the
                        // data the call exists to fetch — but an error body's
                        // value to the model is the failure REASON, which survives
                        // redaction. The memory-side {name}Error entry keeps the
                        // text minus the plaintexts this request substituted (removed
                        // above), for operators debugging via the store.
                        // The status-message fallback is server-authored text of the
                        // same trust class as the body — redacted for the same reason.
                        String toolErrorBody = truncatedError != null && !truncatedError.isBlank()
                                ? SecretRedactionFilter.redact(truncatedError)
                                : SecretRedactionFilter.redact(httpCodeMessage);
                        result.put("body", toolErrorBody);

                        // Store error body in memory so downstream templates / rules can inspect it
                        if (call.getSaveResponse()) {
                            String errorObjectName = call.getResponseObjectName() + "Error";
                            if (truncatedError != null && !truncatedError.isBlank()) {
                                prePostUtils.createMemoryEntry(currentStep, truncatedError, errorObjectName, KEY_HTTP_CALLS);
                                templateDataObjects.put(errorObjectName, truncatedError);
                            }
                            prePostUtils.createMemoryEntry(currentStep, response.getHttpCode(),
                                    call.getResponseObjectName() + "HttpCode", KEY_HTTP_CALLS);
                            templateDataObjects.put(call.getResponseObjectName() + "HttpCode", response.getHttpCode());
                        }
                    }

                    var responseHeaderObjectName = call.getResponseHeaderObjectName();
                    Object responseObjectHeader = null;
                    if (!isNullOrEmpty(responseHeaderObjectName)) {
                        responseObjectHeader = redactHeaderValues(withoutCredentialHeaders(response.getHttpHeader()), built.resolvedSecrets());
                        templateDataObjects.put(responseHeaderObjectName, responseObjectHeader);
                        prePostUtils.createMemoryEntry(currentStep, responseObjectHeader, responseHeaderObjectName, KEY_HTTP_CALLS);
                        // NOT put into `result` here — see the ordered insert below.
                    }

                    if (isResponseSuccessful && call.getSaveResponse()) {
                        // Success bodies land in conversation memory, which is persisted as a
                        // single document — cap them just like the error bodies above.
                        // A response echoing a credential this request sent must not carry it
                        // into memory, template data or the tool result — but a success body
                        // is the data the call exists to fetch, so the redaction must not
                        // mangle it. See redactSuccessBody.
                        final String rawBody = response.getContentAsString();
                        String actualContentType = response.getHttpHeader().get(CONTENT_TYPE);
                        if (actualContentType != null) {
                            actualContentType = actualContentType.split(";")[0];
                        } else {
                            actualContentType = "<not-present>";
                        }

                        Object responseObject;
                        if (CONTENT_TYPE_APPLICATION_JSON.equals(actualContentType)) {
                            String responseBody = truncateResponseBody(rawBody, resolveMaxResponseSize(call), call.getName());
                            try {
                                // Parsed from the UNREDACTED body and scrubbed as a tree: a secret
                                // is removed from string values (and replaces a number only when it
                                // IS the number), so the JSON stays valid and no digit run inside an
                                // unrelated number is rewritten.
                                responseObject = redactSuccessTree(jsonSerialization.deserialize(responseBody, Object.class),
                                        built.resolvedSecrets());
                            } catch (IOException jsonEx) {
                                LOGGER.warnf("ApiCall (%s) returned application/json but body is not valid JSON, falling back to raw string: %s",
                                        call.getName(), jsonEx.getMessage());
                                responseObject = redactSuccessText(responseBody, built.resolvedSecrets());
                            }
                        } else {
                            if (!actualContentType.startsWith("<not-present>") && !actualContentType.startsWith("text")) {
                                var message = "ApiCall (%s) didn't return application/json, text/plain nor text/html "
                                        + "as content-type, instead was (%s)";
                                LOGGER.warn(format(message, call.getName(), actualContentType));
                            }
                            // Redacted BEFORE truncation, so a cut cannot leave half a secret
                            // that no longer matches.
                            responseObject = truncateResponseBody(redactSuccessText(rawBody, built.resolvedSecrets()), resolveMaxResponseSize(call),
                                    call.getName());
                        }

                        var responseObjectName = call.getResponseObjectName();
                        templateDataObjects.put(responseObjectName, responseObject);
                        prePostUtils.createMemoryEntry(currentStep, responseObject, responseObjectName, KEY_HTTP_CALLS);
                        result.put("body", responseObject);
                        result.put("httpCode", response.getHttpCode());
                    } else if (isResponseSuccessful) {
                        // saveResponse=false keeps the BODY out of memory and out of the
                        // tool result on purpose, but the status code still travels: a
                        // model whose tool returned "{}" cannot tell a 204 from a crash,
                        // and honestly reporting "it worked" requires knowing that it did.
                        result.put("httpCode", response.getHttpCode());
                    }

                    // Headers go in LAST, on purpose, and `result` is a LinkedHashMap
                    // so that ordering survives serialization.
                    //
                    // The tool result is truncated from the FRONT
                    // (ToolResponseTruncator cuts `result.substring(0, maxChars)`),
                    // so whatever serializes first is what survives. With a plain
                    // HashMap "headers" hashed ahead of "body" on both the success and
                    // the error path regardless of insertion order — so a per-tool
                    // limit, or the always-on tool-context budget, would spend the
                    // allowance on a header block and cut away the response body the
                    // model actually asked for. Headers are the disposable half of
                    // this map; the body is not.
                    if (responseObjectHeader != null) {
                        result.put("headers", responseObjectHeader);
                    }

                    amountOfExecutions++;
                    retryCall = retryCall(call.getPostResponse(), templateDataObjects, amountOfExecutions, response.getHttpCode(),
                            response.getContentAsString());
                } while (retryCall);

                // This executor has no response-validation stage, so a post-response
                // property instruction never sees a validation error. Passing the flag
                // explicitly keeps the (unused) `runOnValidationError` semantics visible
                // should validation ever be added.
                //
                // `response` is non-null here, and the `!= null` ternary that used to guard
                // this call was removed rather than reinforced: the do-while assigns it
                // unconditionally with no continue/break, executeAndMeasureRequest
                // dereferences it before returning (so it cannot hand back null), and the
                // loop condition itself reads response.getHttpCode(). Anything that fails
                // earlier lands in the catch below instead of reaching this line. The dead
                // branch was actively harmful: it was the sole reason static analysis
                // inferred the variable nullable and reported the loop body as an NPE risk,
                // and a fabricated 500 would have masked a real defect instead of surfacing it.
                Set<String> vaulted = prePostUtils.runPostResponse(memory, call.getPostResponse(), templateDataObjects, response.getHttpCode(),
                        false);
                // A token a post-response scope:secret instruction just vaulted was
                // scrubbed from conversation memory, but this map was built from the
                // response before that and is what an LLM tool call hands the model.
                if (vaulted != null && !vaulted.isEmpty()) {
                    result.replaceAll((key, value) -> {
                        Object cleaned = SecretValueScrubber.scrubDeep(value, vaulted, MemoryKeys.SECRET_INPUT_PLACEHOLDER);
                        return cleaned != null ? cleaned : value;
                    });
                }

                return result;
            }
        } catch (Exception e) {
            RuntimeException safe = withoutSecrets(e, substitutedSecrets);
            if (safe != null) {
                LOGGER.error(safe.getMessage(), safe);
                throw new LifecycleException(safe.getMessage(), safe);
            }
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new LifecycleException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public ResolvedRequest resolve(ApiCall call, IConversationMemory memory, Map<String, Object> templateDataObjects, String targetServerUrl)
            throws LifecycleException {
        if (call == null) {
            throw new IllegalArgumentException("call cannot be null");
        }
        if (memory == null) {
            throw new IllegalArgumentException("memory cannot be null");
        }
        if (templateDataObjects == null) {
            throw new IllegalArgumentException("templateDataObjects cannot be null");
        }
        if (targetServerUrl == null || targetServerUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("targetServerUrl cannot be null or empty");
        }

        try {
            // Note the absence of executePreRequestPropertyInstructions: it writes
            // to conversation memory, and previewing a call must not change the
            // conversation. See IApiCallExecutor#resolve for what that costs.
            BuiltRequest built = buildRequest(targetServerUrl, call, templateDataObjects, conversationPropertiesOf(memory));
            var requestMap = built.request().toMap();
            var headers = requestMap.get(IRequest.KEY_HEADERS) instanceof Map<?, ?> h ? (Map<String, ?>) h : Map.<String, Object>of();
            var queryParams = normalizeQueryParams(requestMap.get(IRequest.KEY_QUERY_PARAMS));
            Object body = requestMap.get(IRequest.KEY_BODY);

            // The RAW body goes in: ResolvedRequest redacts it for display itself,
            // while fingerprinting what was actually resolved. Redacting here
            // instead would fingerprint the redacted form and make two different
            // credentials hash identically — see ResolvedRequest#of.
            return ResolvedRequest.of(
                    String.valueOf(requestMap.get(IRequest.KEY_METHOD)),
                    String.valueOf(requestMap.get(IRequest.KEY_URI)),
                    queryParams,
                    requestRedactor.redactHeaders(headers, built.connectionOwnedHeaders()),
                    body == null ? null : body.toString(),
                    !canExecuteDivergeFromResolve(call)).withoutResolvedSecrets(built.resolvedSecrets());
        } catch (Exception e) {
            // Deliberately NOT logged here — throw only. Unlike execute(), the sole
            // caller of resolve() is the gate-time/pre-execution pinning path, which
            // catches this and logs it with the severity the situation actually has:
            // a WARN saying the call will be approved unpinned (a documented, benign
            // degrade) or that execution is refused. An ERROR here would double every
            // one of those lines and label a normal degrade as breakage.
            //
            // The message is generic and the cause is attached rather than unwrapped:
            // a failure here comes out of template rendering or request building,
            // whose messages quote the material being rendered — Jackson appends the
            // offending source verbatim — so putting it in a log or an exception
            // message would leak the credential from the very operation whose job is
            // to show the approver a REDACTED request.
            throw new LifecycleException(
                    "could not resolve the request for ApiCall '" + LogSanitizer.sanitize(call.getName()) + "'", e);
        }
    }

    /**
     * Read the query parameters out of {@link IRequest#toMap()} without assuming
     * their shape.
     * <p>
     * {@code HttpClientWrapper} accumulates repeats, so the values are lists —
     * casting the map to {@code Map<String, String>} compiles, erases cleanly, and
     * then throws a {@link ClassCastException} deep in the fingerprint
     * canonicaliser. The gate-time caller catches that and approves the call
     * <em>unpinned</em>, so the failure is silent and pinning simply stops applying
     * to every endpoint that carries a query parameter. A single-valued map is
     * still accepted, because this interface has other implementations and the
     * contract has been ambiguous.
     */
    private static Map<String, List<String>> normalizeQueryParams(Object rawQueryParams) {
        if (!(rawQueryParams instanceof Map<?, ?> params)) {
            return Map.of();
        }
        var normalized = new LinkedHashMap<String, List<String>>();
        for (var entry : params.entrySet()) {
            String name = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (value instanceof List<?> values) {
                normalized.put(name, values.stream().map(v -> v == null ? "" : v.toString()).toList());
            } else {
                normalized.put(name, List.of(value == null ? "" : value.toString()));
            }
        }
        return normalized;
    }

    /**
     * Whether {@link #execute} can build a request this method's caller did not
     * resolve — the question a fingerprint's soundness actually turns on.
     * <p>
     * It used to ask something narrower ("does this call have pre-request property
     * instructions"), and the gap between the two questions was the fail-open in
     * the pinning design. Each miss below produces a call that is PINNED, whose
     * gate-time and pre-execution resolutions agree with each other — because both
     * skip the divergence — while {@code execute} sends something else entirely.
     * The guard then passes on a comparison that was never sound:
     * <ul>
     * <li><b>An empty-but-present {@code propertyInstructions} list.</b>
     * {@code isNullOrEmpty} treated it as absent, but
     * {@code PrePostUtils#executePreRequestPropertyInstructions} guards on
     * {@code != null} — so it still re-runs {@code memoryItemConverter.convert},
     * discarding the model arguments merged in for this call. Every {@code {arg}}
     * then renders empty at execution and non-empty in the preview. Hence
     * {@code != null}, matching the code that actually runs.</li>
     * <li><b>{@code fireAndForget} with {@code preRequest.batchRequests}.</b>
     * {@code execute} routes to {@code executeFireAndForgetCalls}, which calls
     * {@code buildRequest} once PER iteration object — N distinct requests, none of
     * them the single one {@code resolve} builds (the iteration variable renders
     * empty there). {@code batchRequests} is a different field from
     * {@code propertyInstructions}, so this was pinned, and an approver shown one
     * request authorised N unreviewed ones.</li>
     * </ul>
     * Returning true here means unpinnable, not refused: the call still needs its
     * approval, it is previewed best-effort, and only the fingerprint enforcement
     * is skipped — which is the honest state for a request we genuinely cannot pin,
     * rather than a pin we cannot honour.
     */
    private static boolean canExecuteDivergeFromResolve(ApiCall call) {
        var preRequest = call.getPreRequest();
        if (preRequest != null && preRequest.getPropertyInstructions() != null) {
            return true;
        }
        // One resolved request cannot stand for N. Guarded on fireAndForget too
        // because that is what selects the batching branch in execute().
        if (Boolean.TRUE.equals(call.getFireAndForget()) && preRequest != null && preRequest.getBatchRequests() != null) {
            return true;
        }
        // Same argument, different loop: buildRequest sits INSIDE execute()'s
        // retry do-while, and between attempts the shared templateDataObjects
        // map gains {responseObjectName}, …Error, …HttpCode and the response
        // headers. A call whose path, body or headers template any of those
        // sends attempts 2..N as requests that were never resolved, never
        // previewed and never fingerprinted — while the approver saw only
        // attempt 1. Keyed on the instruction being present and actually able
        // to fire (maxRetries >= 1), which is exactly what retryCall() tests.
        var postResponse = call.getPostResponse();
        if (postResponse instanceof HttpPostResponse httpPostResponse) {
            var retry = httpPostResponse.getRetryApiCallInstruction();
            return retry != null && retry.getMaxRetries() >= 1;
        }
        return false;
    }

    private IResponse executeAndMeasureRequest(ApiCall call, IRequest request, Set<String> resolvedSecrets, boolean retryCall,
                                               int amountOfExecutions)
            throws IRequest.HttpRequestException, ExecutionException, InterruptedException {

        LOGGER.info(call.getName() + " Request: " + (amountOfExecutions > 0 ? amountOfExecutions + ". retry - " : "")
                + RequestRedactor.safeRequestLog(request, resolvedSecrets));
        int delayInMillis = getDelayInMillis(call, retryCall, amountOfExecutions);

        long executionStart = currentTimeMillis();
        IResponse response = executeRequest(request, delayInMillis);
        long executionEnd = currentTimeMillis();
        long duration = executionEnd - executionStart;

        LOGGER.info(call.getName() + " Response: " + RequestRedactor.safeResponseLog(response, resolvedSecrets));
        LOGGER.info(call.getName()
                + format(" Execution time: Duration: %sms Delay: %sms Total: %sms\n", duration, delayInMillis, duration + delayInMillis));

        return response;
    }

    private void executeFireAndForgetCalls(String targetServerUrl, ApiCall call, Map<String, Object> templateDataObjects,
                                           Map<String, Property> conversationProperties)
            throws ITemplatingEngine.TemplateEngineException, IRequest.HttpRequestException {

        var preRequest = call.getPreRequest();
        var callName = call.getName();

        if (preRequest != null && preRequest.getBatchRequests() != null) {
            BatchRequestBuildingInstruction batchRequest = preRequest.getBatchRequests();
            if (batchRequest.getExecuteCallsSequentially() == null) {
                batchRequest.setExecuteCallsSequentially(false);
            }

            // Every request is built here, on the turn's own thread, and only the sending
            // goes to the background. A request that cannot be built — an unsatisfiable
            // ${caller:...} or ${connection:...} reference, an expired secret context
            // value — then fails the turn exactly as a single fire-and-forget call does,
            // instead of being logged by a worker nobody reads while the turn reports
            // success. It also means the request sees the turn's template data as it is
            // now, not as it is when a worker gets to it. Each request gets its own copy of
            // the template data, so the iteration variable never leaks into the calls that
            // run after this one.
            List<Object> batchIterationList = prePostUtils.buildIterationValues(batchRequest.getIterationObjectName(),
                    batchRequest.getPathToTargetArray(), batchRequest.getTemplateFilterExpression(), templateDataObjects);
            // Each request is kept as the BuiltRequest it came back as, not just its
            // IRequest: the plaintexts the build resolved are what the log line below has
            // to be redacted by, and only the build knows them.
            List<BuiltRequest> requests = new ArrayList<>(batchIterationList.size());
            for (Object iterationObject : batchIterationList) {
                Map<String, Object> iterationData = new LinkedHashMap<>(templateDataObjects);
                iterationData.put(batchRequest.getIterationObjectName(), iterationObject);
                requests.add(buildRequest(targetServerUrl, call, iterationData, conversationProperties));
            }

            // The sending runs on a thread of its own; propagate() keeps the turn's
            // bindings available there for anything the send itself resolves.
            runtime.submitCallable(callerIdentityContext.propagate(() -> {
                for (BuiltRequest built : requests) {
                    IRequest request = built.request();
                    if (batchRequest.getExecuteCallsSequentially()) {
                        long executionStart = currentTimeMillis();
                        LOGGER.info(callName + " Batch Request: " + RequestRedactor.safeRequestLog(request, built.resolvedSecrets()));
                        IResponse response = request.send();
                        logExecutionResponse(response, built.resolvedSecrets(), callName, executionStart, currentTimeMillis(), false);
                    } else {
                        executeFireAndForgetCall(built, callName);
                    }
                }
                return null;
            }), null);
        } else {
            executeFireAndForgetCall(buildRequest(targetServerUrl, call, templateDataObjects, conversationProperties), callName);
        }
    }

    private static void executeFireAndForgetCall(BuiltRequest built, String httpCallsName) throws IRequest.HttpRequestException {

        IRequest request = built.request();
        LOGGER.info(httpCallsName + " Request (f'n'f): " + RequestRedactor.safeRequestLog(request, built.resolvedSecrets()));
        long executionStart = currentTimeMillis();
        request.send(res -> logExecutionResponse(res, built.resolvedSecrets(), httpCallsName, executionStart, currentTimeMillis(), true));
    }

    private static void logExecutionResponse(IResponse response, Set<String> resolvedSecrets, String httpCallsName, long executionStart,
                                             long executionEnd, boolean fireAndForget) {

        long duration = executionEnd - executionStart;
        LOGGER.info(
                httpCallsName + " Response " + (fireAndForget ? "(f'n'f)" : "") + ": " + RequestRedactor.safeResponseLog(response, resolvedSecrets));
        // No trailing "\n": the console pattern ends in %n, and a newline in a log
        // MESSAGE is now escaped rather than printed (CWE-117), so this one would
        // render as a literal "\n" at the end of the line.
        LOGGER.info(httpCallsName + format(" Execution time: %sms", duration));
    }

    // Package-private for unit testing of the backoff curve.
    static int getDelayInMillis(ApiCall call, boolean retryCall, int amountOfExecutions) {
        int delayInMillis = 0;

        if (retryCall) {
            var retryInstruction = call.getPostResponse().getRetryApiCallInstruction();
            Integer baseDelay = retryInstruction.getExponentialBackoffDelayInMillis();
            if (baseDelay != null && baseDelay > 0) {
                // True exponential backoff: base * 2^(attempt-1), capped to avoid
                // overflow and unbounded waits. (Previously linear: base * attempt.)
                int exponent = Math.max(0, amountOfExecutions - 1);
                long computed = (long) baseDelay << Math.min(exponent, 20);
                delayInMillis = (int) Math.min(computed, resolveMaxBackoffInMillis(retryInstruction));
            }
        }

        if (delayInMillis == 0) {
            var preRequest = call.getPreRequest();
            delayInMillis = preRequest == null ? 0 : preRequest.getDelayBeforeExecutingInMillis();
        }

        return delayInMillis;
    }

    /**
     * Backoff ceiling for one retry: the configured value if it is lower than
     * {@link #MAX_BACKOFF_MILLIS}, otherwise the hard ceiling. A configuration can
     * only shorten the wait, never push it past the turn budget.
     */
    private static int resolveMaxBackoffInMillis(RetryApiCallInstruction retryInstruction) {
        Integer configuredMaxBackoff = retryInstruction.getMaxBackoffDelayInMillis();
        if (configuredMaxBackoff == null || configuredMaxBackoff <= 0) {
            return MAX_BACKOFF_MILLIS;
        }

        return Math.min(configuredMaxBackoff, MAX_BACKOFF_MILLIS);
    }

    /**
     * Request timeout for this call: the per-call value if configured, otherwise
     * the deployment-wide default.
     */
    private long resolveTimeout(ApiCall call) {
        Integer configuredTimeout = call.getTimeoutInMillis();
        return configuredTimeout != null && configuredTimeout > 0 ? configuredTimeout : defaultTimeoutInMillis;
    }

    /**
     * How much of the response body is kept in conversation memory: the per-call
     * value if configured, otherwise the deployment-wide default. Anything beyond
     * this is truncated, not rejected.
     */
    private int resolveMaxResponseSize(ApiCall call) {
        Integer configuredMaxResponseSize = call.getMaxResponseSizeInBytes();
        return configuredMaxResponseSize != null && configuredMaxResponseSize > 0 ? configuredMaxResponseSize : defaultMaxResponseSizeInBytes;
    }

    /**
     * How much the http client is allowed to buffer before it fails the call
     * outright. Always at least {@link #MAX_TRANSPORT_RESPONSE_SIZE_BYTES}, and
     * never below the memory cap — a call that legitimately configures a very large
     * {@code maxResponseSizeInBytes} must not be rejected by the transport before
     * its own cap applies.
     */
    private int resolveTransportResponseSize(ApiCall call) {
        return Math.max(resolveMaxResponseSize(call), MAX_TRANSPORT_RESPONSE_SIZE_BYTES);
    }

    /**
     * Cut an over-long response body down to the configured cap before it is
     * written to conversation memory. The cap is a byte budget applied to the
     * character count, which is a conservative approximation for multi-byte
     * content.
     */
    // Package-private for unit testing.
    static String truncateResponseBody(String responseBody, int maxResponseSize, String callName) {
        if (responseBody == null || responseBody.length() <= maxResponseSize) {
            return responseBody;
        }

        LOGGER.warnf("ApiCall (%s) response of %s chars exceeds the configured maximum of %s — truncating before storing it in memory.", callName,
                responseBody.length(), maxResponseSize);

        return responseBody.substring(0, maxResponseSize);
    }

    private IResponse executeRequest(IRequest request, int delay) throws IRequest.HttpRequestException, ExecutionException, InterruptedException {

        if (delay > 0) {
            return runtime.getScheduledExecutorService().schedule((Callable<IResponse>) request::send, delay, TimeUnit.MILLISECONDS).get();
        } else {
            return request.send();
        }
    }

    private boolean retryCall(HttpPostResponse postResponse, Map<String, Object> conversationValues, int amountOfExecutions, int httpCode,
                              String contentAsString) {

        if (isNullOrEmpty(postResponse)) {
            return false;
        }

        var retryApiCallInstruction = postResponse.getRetryApiCallInstruction();
        if (isNullOrEmpty(retryApiCallInstruction)) {
            return false;
        }

        int maxRetries = retryApiCallInstruction.getMaxRetries();
        if (maxRetries >= 1 && maxRetries >= amountOfExecutions) {

            var retryOnHttpCodes = retryApiCallInstruction.getRetryOnHttpCodes();
            if (!isNullOrEmpty(retryOnHttpCodes) && retryOnHttpCodes.contains(httpCode)) {
                return true;
            }

            var valuePathMatchers = retryApiCallInstruction.getResponseValuePathMatchers();
            if (!isNullOrEmpty(contentAsString) && !isNullOrEmpty(valuePathMatchers)) {
                for (var valuePathMatcher : valuePathMatchers) {
                    boolean success = executeValuePath(conversationValues, valuePathMatcher.getValuePath(), valuePathMatcher.getEquals(),
                            valuePathMatcher.getContains());

                    if (valuePathMatcher.getTrueIfNoMatch() != success) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * A request ready to send, together with the names of the headers a connection
     * filled.
     * <p>
     * The names travel with the request because the request itself cannot say: by
     * the time {@link IRequest#toMap()} is read back, a connection-supplied
     * {@code X-Amp-Id} is indistinguishable from a plain one, and neither its name
     * nor its value need match any credential heuristic. Both consumers of the
     * built request — the debug record persisted to memory and the HITL approval
     * preview — hand this set to {@link RequestRedactor}, so a credential is
     * redacted because the executor <em>knows</em> it is one rather than because it
     * happens to look like one.
     *
     * @param request
     *            the request, still carrying every live credential
     * @param connectionOwnedHeaders
     *            header names written from a {@code ${connection:…}} reference;
     *            never null, empty when none was
     */
    record BuiltRequest(IRequest request, Set<String> connectionOwnedHeaders, Set<String> resolvedSecrets) {
    }

    /**
     * Build the request, making sure no failure on the way carries a plaintext the
     * build already substituted.
     * <p>
     * {@code URI.create} quotes the whole string it rejects, and the path is
     * resolved before it is parsed — so a secret in a URL path (a webhook token, a
     * Segment write key) used to reach the ERROR log and the task-error digest
     * through "Illegal character in path at index 42: https://…/&lt;secret&gt;/…".
     * The same holds for any other exception whose message quotes the request.
     */
    private BuiltRequest buildRequest(String targetServerUrl, ApiCall call, Map<String, Object> templateDataObjects,
                                      Map<String, Property> conversationProperties)
            throws ITemplatingEngine.TemplateEngineException {
        var resolvedSecrets = new HashSet<String>();
        try {
            return buildRequest(targetServerUrl, call, templateDataObjects, conversationProperties, resolvedSecrets);
        } catch (RuntimeException | ITemplatingEngine.TemplateEngineException e) {
            // A template failure after an earlier part of the request resolved a secret
            // is checked, and would otherwise reach the caller's log before these
            // plaintexts join its redaction set.
            RuntimeException safe = withoutSecrets(e, resolvedSecrets);
            if (safe != null) {
                throw safe;
            }
            throw e;
        }
    }

    /**
     * A copy of {@code failure} with every {@code secrets} plaintext removed from
     * its message, or {@code null} when neither it nor any cause mentions one. The
     * copy deliberately has no cause: the original chain quotes the same text.
     */
    static RuntimeException withoutSecrets(Throwable failure, Set<String> secrets) {
        if (secrets == null || secrets.isEmpty()) {
            return null;
        }
        boolean mentioned = false;
        for (Throwable t = failure; t != null && !mentioned; t = t.getCause() == t ? null : t.getCause()) {
            String message = t.getMessage();
            if (message != null && !RequestRedactor.redactResolvedSecrets(message, secrets).equals(message)) {
                mentioned = true;
            }
        }
        if (!mentioned) {
            return null;
        }
        String redacted = RequestRedactor.redactResolvedSecrets(String.valueOf(failure.getMessage()), secrets);
        return new IllegalArgumentException(failure.getClass().getSimpleName() + ": " + redacted
                + " (a resolved secret was removed from this message; the original exception is not kept because it quotes it)");
    }

    /**
     * Below this length a substituted secret is not searched for inside the text of
     * a SUCCESS body — a vaulted Basic-auth username such as {@code alice} or a
     * short account id would otherwise be rewritten inside ordinary data. It is
     * still replaced where a JSON value IS the secret. Error bodies and logs redact
     * every length: there the value is the failure reason, not data.
     */
    static final int MIN_SUCCESS_BODY_REDACTION_LENGTH = 8;

    /**
     * A success body treated as text: only secrets long enough to be unambiguous.
     */
    static String redactSuccessText(String body, Set<String> resolvedSecrets) {
        return RequestRedactor.redactResolvedSecrets(body, longSecrets(resolvedSecrets));
    }

    /**
     * A parsed JSON success body: long secrets removed from string values, short
     * ones only where a value equals them, numbers only when they are the secret.
     */
    static Object redactSuccessTree(Object tree, Set<String> resolvedSecrets) {
        if (tree == null || resolvedSecrets == null || resolvedSecrets.isEmpty()) {
            return tree;
        }
        Set<String> longOnes = longSecrets(resolvedSecrets);
        Set<String> shortOnes = new HashSet<>(resolvedSecrets);
        shortOnes.removeAll(longOnes);
        Object cleaned = SecretValueScrubber.scrubDeep(tree, longOnes, shortOnes, RequestRedactor.REDACTED);
        return cleaned != null ? cleaned : tree;
    }

    private static Set<String> longSecrets(Set<String> resolvedSecrets) {
        if (resolvedSecrets == null || resolvedSecrets.isEmpty()) {
            return Set.of();
        }
        Set<String> longOnes = new HashSet<>();
        for (String secret : resolvedSecrets) {
            if (secret != null && secret.length() >= MIN_SUCCESS_BODY_REDACTION_LENGTH) {
                longOnes.add(secret);
            }
        }
        return longOnes;
    }

    /**
     * The caller-token reference, whose resolved value is added to the redaction
     * set.
     */
    private static final String CALLER_TOKEN_REFERENCE = "${caller:token}";

    /**
     * Record a credential the request carries so a response echoing it is redacted
     * like a vault plaintext. A header value such as {@code Bearer <token>} is also
     * recorded without its scheme, because an echo quotes the token, not the
     * header.
     */
    private static void addCredential(Set<String> resolvedSecrets, String credential) {
        if (credential == null || credential.isBlank()) {
            return;
        }
        resolvedSecrets.add(credential);
        int space = credential.lastIndexOf(' ');
        if (space > 0 && credential.length() - space - 1 >= MIN_SCHEMELESS_CREDENTIAL_LENGTH) {
            resolvedSecrets.add(credential.substring(space + 1));
        }
    }

    /**
     * Shorter scheme-less remainders are not recorded: they would redact ordinary
     * text.
     */
    private static final int MIN_SCHEMELESS_CREDENTIAL_LENGTH = 8;

    /**
     * Response headers with the plaintexts this request substituted removed from
     * their values — a server may echo a credential back in a header as easily as
     * in a body.
     */
    private static Map<String, String> redactHeaderValues(Map<String, String> headers, Set<String> resolvedSecrets) {
        if (headers == null || resolvedSecrets == null || resolvedSecrets.isEmpty()) {
            return headers;
        }
        var redacted = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        headers.forEach((name, value) -> redacted.put(name, RequestRedactor.redactResolvedSecrets(value, resolvedSecrets)));
        return redacted;
    }

    private BuiltRequest buildRequest(String targetServerUrl, ApiCall call, Map<String, Object> templateDataObjects,
                                      Map<String, Property> conversationProperties, Set<String> resolvedSecrets)
            throws ITemplatingEngine.TemplateEngineException {

        Request requestConfig = call.getRequest();
        String path = requestConfig.getPath().trim();
        if (!path.startsWith(SLASH_CHAR) && !path.isEmpty() && !path.startsWith("http")) {
            path = SLASH_CHAR + path;
        }
        var targetDestination = !path.startsWith("http") ? targetServerUrl + path : path;
        var targetUriStr = prePostUtils.templateValues(targetDestination, pathSafeView(templateDataObjects));
        // Resolve global variable references, then vault references in URL
        targetUriStr = resolveGuardedVariables(targetDestination, targetUriStr, "the request path", templateDataObjects, conversationProperties);
        targetUriStr = resolveSecrets(targetUriStr, resolvedSecrets, "the request path");
        // The path is not caller-resolved either, and a surviving reference would
        // reach URI.create() to fail as "Illegal character in path" — an error that
        // names the symptom and not the cause.
        callerIdentityResolver.rejectAnyReference(targetUriStr, "the request path");
        rejectConnectionReference(targetUriStr, "the request path");
        rejectExpiredSecretContext(targetUriStr, "the request path");
        var targetUri = URI.create(targetUriStr);
        var requestBody = prePostUtils.templateValues(requestConfig.getBody(), templateDataObjects);
        // Resolve global variable references, then vault references in request body
        requestBody = resolveGuardedVariables(requestConfig.getBody(), requestBody, "a request body", templateDataObjects, conversationProperties);
        requestBody = resolveSecrets(requestBody, resolvedSecrets, "a request body");

        // SSRF protection (opt-in): validate the fully-resolved target and disable
        // redirect-following so a 3xx cannot bounce the request to an internal host.
        // Off by default to preserve calls to internal/private APIs.
        if (ssrfProtectionEnabled) {
            UrlValidationUtils.validateUrl(targetUri.toString());
        } else {
            // Opting out of SSRF protection keeps private and loopback targets
            // reachable (configured internal APIs), but never the cloud instance-
            // metadata service: that is a credential endpoint, not an API anyone
            // configures on purpose. Redirects stay followed here; the client itself
            // refuses any hop onto the metadata service (HttpClientModule).
            UrlValidationUtils.rejectCloudMetadataTarget(targetUri.toString());
        }

        // Locale.ROOT is defensive rather than a live fix: no current Method
        // constant contains an 'i', so no locale changes the result today. It would
        // the moment one did — "options" uppercases to "OPTİONS" under tr-TR.
        var method = IHttpClient.Method.valueOf(requestConfig.getMethod().toUpperCase(Locale.ROOT));
        IRequest request = httpClient.newRequest(targetUri, method);
        // Bound the call in time and in size. Without these an httpcall can occupy the
        // conversation thread until the client's own fallback expires, and can pull an
        // arbitrarily large body into conversation memory. The transport ceiling is
        // deliberately above the memory cap so an over-long body is truncated on the
        // way into memory rather than failing the whole turn.
        request.setTimeout(resolveTimeout(call), TimeUnit.MILLISECONDS);
        request.setMaxResponseSize(resolveTransportResponseSize(call));
        if (ssrfProtectionEnabled) {
            request.setFollowRedirects(false);
        }
        if (!isNullOrEmpty(requestBody)) {
            String contentType = requestConfig.getContentType();
            request.setBodyEntity(requestBody, UTF_8, !isNullOrEmpty(contentType) ? contentType : TEXT_PLAIN);
        }

        // The body is never caller-resolved, so ANY reference there would be sent as
        // a literal placeholder — not just a token one. Reject the lot instead of
        // shipping nonsense to the API.
        callerIdentityResolver.rejectAnyReference(requestBody, "a request body");
        rejectConnectionReference(requestBody, "a request body");
        rejectExpiredSecretContext(requestBody, "a request body");

        Map<String, String> headers = requestConfig.getHeaders();
        // Header names already written to this request, lower-cased because HTTP
        // header names are case-insensitive and setHttpHeader REPLACES rather than
        // appends — two entries differing only in case displace each other, and
        // whichever iterates last wins with no signal. The flag records whether a
        // CONNECTION owns the name: that is the collision iteration order must
        // never decide, because one side of it is a credential.
        var claimedHeaders = new HashMap<String, Boolean>();
        var connectionOwnedHeaders = new HashSet<String>();
        for (String headerName : headers.keySet()) {
            String headerValue = prePostUtils.templateValues(headers.get(headerName), templateDataObjects);
            // Resolve global variable references, then vault references in headers
            headerValue = resolveGuardedVariables(headers.get(headerName), headerValue, "header '" + headerName + "'", templateDataObjects,
                    conversationProperties);
            headerValue = resolveSecrets(headerValue, resolvedSecrets, "header '" + headerName + "'");
            // Caller identity resolves last and needs the target URI: the token is
            // only released when the call goes back to the caller's own origin.
            boolean callerTokenReferenced = headerValue != null && headerValue.contains(CALLER_TOKEN_REFERENCE);
            headerValue = callerIdentityResolver.resolveValue(headerValue, targetUri);
            if (callerTokenReferenced) {
                // Echo redaction covers the caller's token too, not only vault plaintexts.
                addCredential(resolvedSecrets, callerIdentityResolver.currentCallerToken());
            }
            // Connections resolve last, and only in a header. A ${connection:name}
            // resolves to a credential bound to THIS caller and THIS moment, so
            // unlike a vault reference it cannot be substituted into a cached
            // string — which is also why it replaces the whole header rather than
            // being interpolated into one.
            if (ConnectionResolver.containsReference(headerValue)) {
                ConnectionReference.requireSole(headerValue, "Header '" + headerName + "'");
                var credential = connectionResolver.resolve(headerValue, targetUri, principalFrom(templateDataObjects));
                // The connection owns the header NAME — that is the point of storing
                // one, since Authorization and X-Api-Key are the same connection model
                // — but a silent displacement is not acceptable in either direction.
                // Two references resolving to one header name used to overwrite each
                // other with no signal, and the request went out carrying whichever
                // won.
                if (!headerName.equalsIgnoreCase(credential.headerName())) {
                    throw new IllegalArgumentException("Header '" + headerName + "' references a connection whose header is '"
                            + credential.headerName() + "'. Name the header the same as the connection's, so what the config says and what "
                            + "is sent cannot disagree.");
                }
                Boolean claimedByConnection = claimedHeaders.putIfAbsent(credential.headerName().toLowerCase(Locale.ROOT), Boolean.TRUE);
                if (Boolean.TRUE.equals(claimedByConnection)) {
                    throw new IllegalArgumentException("More than one header resolves to '" + credential.headerName()
                            + "' through a connection. Only one credential can occupy a header; the others would be silently dropped.");
                }
                if (claimedByConnection != null) {
                    throw connectionHeaderCollision(credential.headerName());
                }
                request.setHttpHeader(credential.headerName(), credential.headerValue());
                addCredential(resolvedSecrets, credential.headerValue());
                connectionOwnedHeaders.add(credential.headerName());
                continue;
            }
            // The same map, read from the other side. A plain header sharing a name
            // with a connection-owned one used to slip past every guard here, because
            // the collision set was only ever consulted inside the branch above — and
            // then the two silently overwrote each other by iteration order. Two
            // genuinely different names, and even two plain headers differing only in
            // case, keep behaving exactly as before.
            if (Boolean.TRUE.equals(claimedHeaders.putIfAbsent(headerName.toLowerCase(Locale.ROOT), Boolean.FALSE))) {
                throw connectionHeaderCollision(headerName);
            }
            rejectExpiredSecretContext(headerValue, "header '" + headerName + "'");
            request.setHttpHeader(headerName, headerValue);
        }

        Map<String, String> queryParams = requestConfig.getQueryParams();
        for (String queryParam : queryParams.keySet()) {
            var qpValue = prePostUtils.templateValues(queryParams.get(queryParam), templateDataObjects);
            // Resolve global variable references, then vault references in query params
            qpValue = resolveGuardedVariables(queryParams.get(queryParam), qpValue, "query parameter '" + queryParam + "'",
                    templateDataObjects, conversationProperties);
            qpValue = resolveSecrets(qpValue, resolvedSecrets, "query parameter '" + queryParam + "'");
            // A token in a query string leaks via access logs and proxies.
            callerIdentityResolver.rejectTokenReference(qpValue, "a query parameter");
            rejectConnectionReference(qpValue, "a query parameter");
            qpValue = callerIdentityResolver.resolveValue(qpValue, targetUri);
            rejectExpiredSecretContext(qpValue, "query parameter '" + queryParam + "'");
            request.setQueryParam(queryParam, qpValue);
        }
        return new BuiltRequest(request, Set.copyOf(connectionOwnedHeaders), Set.copyOf(resolvedSecrets));
    }

    /**
     * Guard the rendered value, resolve its {@code ${vars:…}} references, and guard
     * it again.
     * <p>
     * Twice, because a global variable is an indirection to a credential reference:
     * {@code ${vars:credential}} is allowed to hold {@code ${vault:…}} or
     * {@code ${connection:…}}. The first pass stops a credential reference
     * conversation data wrote directly. It cannot stop one data wrote as
     * {@code ${vars:…}} — that is not a credential reference yet, and looks exactly
     * like a configured one until the variable expands. So the second pass runs
     * after expansion, against the configured template expanded the same way: a
     * reference a CONFIGURED variable produced is in the allowed set, and one a
     * data-supplied variable produced is not.
     *
     * @param template
     *            the configured value of the field, before templating
     * @param rendered
     *            the same value after templating
     * @param location
     *            human-readable field name for the error, e.g. "a request body"
     * @param templateData
     *            the data the template was rendered with
     * @param conversationProperties
     *            the live properties, which carry the auto-vault provenance marker
     *            {@code templateData} has flattened away
     * @return {@code rendered} with its global variable references resolved
     */
    private String resolveGuardedVariables(String template, String rendered, String location, Map<String, Object> templateData,
                                           Map<String, Property> conversationProperties) {
        ConfigReferenceGuard.requireConfiguredReferences(template, rendered, location, templateData, conversationProperties);
        String resolved = globalVariableResolver.resolveValue(rendered);
        if (resolved != null && !resolved.equals(rendered)) {
            ConfigReferenceGuard.requireConfiguredReferences(globalVariableResolver.resolveValue(template), resolved, location, templateData,
                    conversationProperties);
        }
        return resolved;
    }

    /**
     * The conversation's live properties, for the one question
     * {@code templateDataObjects} cannot answer.
     * <p>
     * {@code ConversationProperties.toMap()} flattens each {@link Property} to its
     * raw value, which is all a template needs and exactly what loses the
     * auto-vault provenance marker {@link ConfigReferenceGuard} depends on. So the
     * guard gets the {@code Property} objects themselves, read from memory at build
     * time rather than captured earlier — a pre-request property instruction runs
     * between {@code execute} being called and the request being built, and writes
     * through to this same map.
     * <p>
     * Empty rather than null when memory carries no properties: the guard then has
     * no auto-vault allowance to grant, which is the correct answer and not a
     * reason to skip the guard.
     */
    private static Map<String, Property> conversationPropertiesOf(IConversationMemory memory) {
        var properties = memory.getConversationProperties();
        return properties != null ? properties : Map.of();
    }

    /**
     * Resolve the vault references in {@code value}, recording each plaintext that
     * was substituted so the request can be redacted by value wherever it is
     * recorded — a vault secret need not look like a credential, so name and shape
     * heuristics alone would miss it.
     * <p>
     * Every reference is resolved exactly ONCE, and the string this returns is
     * built from those same resolutions. Resolving a second time to produce the
     * value would open a window: a secret rotated (and the resolver cache
     * invalidated) between the two passes puts the NEW plaintext in the request
     * while only the old one is in the redaction set, so the value actually sent is
     * the one that survives into memory, previews and logs.
     * <p>
     * A reference that cannot be resolved is left in place by
     * {@link SecretResolver#resolveValue} — the vault is disabled, the provider
     * failed, or the secret does not exist. This method then refuses the call
     * instead of sending the literal {@code ${vault:name}} as the credential, the
     * same fail-closed rule {@link SecretResolver#requireResolved} applies to the
     * parameters of an outbound LLM client. The message names the reference, which
     * is a name and not a secret.
     *
     * @param location
     *            human-readable field name for the error, e.g. "a request body"
     */
    private String resolveSecrets(String value, Set<String> resolvedSecrets, String location) {
        if (value == null) {
            return null;
        }
        Matcher references = SecretReference.compiledPattern().matcher(value);
        StringBuilder resolved = new StringBuilder();
        String unresolved = null;
        while (references.find()) {
            String reference = references.group();
            String plaintext = secretResolver.resolveValue(reference);
            if (plaintext == null || plaintext.equals(reference)) {
                unresolved = reference;
            } else if (!plaintext.isEmpty()) {
                // An empty plaintext resolved fine, but redacting "" would replace
                // every character boundary in the request with the marker.
                resolvedSecrets.add(plaintext);
            }
            references.appendReplacement(resolved, Matcher.quoteReplacement(plaintext == null ? reference : plaintext));
        }
        references.appendTail(resolved);
        if (unresolved != null) {
            throw new IllegalArgumentException(location + " references " + unresolved
                    + ", which could not be resolved — the secret does not exist, the vault failed, or the vault is not configured"
                    + " (EDDI_VAULT_MASTER_KEY). Refusing to send the unresolved reference.");
        }
        return resolved.toString();
    }

    /**
     * Refuse a request that would carry
     * {@link MemoryKeys#SECRET_CONTEXT_PLACEHOLDER}.
     * <p>
     * A context value the client marked {@code "secret": true} is replaced by the
     * placeholder when its turn stops, so a template that reads it afterwards — a
     * call that runs after a HITL approval resumed the turn, or one in a later turn
     * — resolves to the placeholder. Sending that would authenticate as nobody and
     * surface as a 401 from the API with nothing naming the cause; failing here
     * names it.
     */
    static void rejectExpiredSecretContext(String value, String location) {
        if (value == null) {
            return;
        }
        if (value.contains(MemoryKeys.SECRET_CONTEXT_PLACEHOLDER) || urlDecodedContainsSecretContextPlaceholder(value)) {
            throw new IllegalArgumentException("This API call would send " + MemoryKeys.SECRET_CONTEXT_PLACEHOLDER + " in " + location
                    + ": it references a context value marked secret, and those only exist during the request that carried "
                    + "them. This turn no longer has it — it resumed after a human approval, or a later turn referenced it. "
                    + "Send the value again in the context of the request that makes this call.");
        }
    }

    /**
     * The path is templated URL-encoded, so the placeholder arrives encoded there.
     */
    private static boolean urlDecodedContainsSecretContextPlaceholder(String value) {
        if (value.indexOf('%') < 0) {
            return false;
        }
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8).contains(MemoryKeys.SECRET_CONTEXT_PLACEHOLDER);
        } catch (IllegalArgumentException malformedEscape) {
            return false;
        }
    }

    /**
     * The conversation's user id, from the same template data every other value in
     * this method is built from.
     * <p>
     * A cross-check, not a source. {@code ConnectionResolver} takes its authority
     * from the {@code ResolutionPrincipal} bound to the turn, which carries a
     * provenance a bare id cannot — whether anything actually authenticated that
     * user. Passing the id here only lets the resolver refuse when the call was
     * built for one user while the turn is running as another; it can never grant
     * anything on its own.
     */
    private static String principalFrom(Map<String, Object> templateDataObjects) {
        if (templateDataObjects != null && templateDataObjects.get("userInfo") instanceof Map<?, ?> userInfo) {
            Object userId = userInfo.get("userId");
            return userId == null ? null : userId.toString();
        }
        return null;
    }

    /**
     * Refuses a {@code ${connection:…}} anywhere other than a header.
     * <p>
     * Same restriction set as {@code ${caller:token}}, for the same reasons. A
     * credential in a URL or a query string is written to ingress logs, proxy logs
     * and browser history before it reaches the provider; a credential in a body is
     * not a credential the provider will read. Rejecting at build time turns all
     * three into an actionable configuration error rather than a placeholder sent
     * as literal text and a 401 with no explanation.
     */
    private static void rejectConnectionReference(String value, String where) {
        if (ConnectionResolver.containsReference(value)) {
            // A ConnectionException, not an IllegalArgumentException: UNSUPPORTED_PLACEMENT
            // is the reason ConnectionExceptionMapper maps to 400, and it was the one
            // reason nothing ever threw. execute() and resolve() wrap it in a
            // LifecycleException either way, so the pipeline reports it as the same
            // configuration error; only the type — and with it the REST status when it
            // escapes a resource — changes.
            throw new ConnectionException(ConnectionException.Reason.UNSUPPORTED_PLACEMENT,
                    "A ${connection:…} reference may only appear in a header, not in " + where
                            + ". A credential in a URL or query string is recorded by every hop before the provider sees it.");
        }
    }

    /**
     * The refusal for a header that a connection owns and a plain entry also sets.
     */
    private static IllegalArgumentException connectionHeaderCollision(String headerName) {
        return new IllegalArgumentException("Header '" + headerName + "' is set both directly and by a connection. HTTP header names are "
                + "case-insensitive and the last write wins, so which of the two is sent would depend on config order alone. Remove the "
                + "plain header, or give it a name the connection does not claim.");
    }

    /**
     * Bytes that may appear un-encoded in a substituted path value: RFC 3986
     * "unreserved", minus {@code .} — everything else is percent-encoded, including
     * {@code /}, {@code ?} and {@code #}, which is the point.
     * <p>
     * The dot is excluded deliberately: a substituted value of exactly {@code ..}
     * would otherwise survive as a dot-segment and normalize one level up even with
     * every slash encoded. Dot-segment removal (RFC 3986 §5.2.4) runs on the raw
     * path BEFORE percent-decoding, so {@code %2E%2E} is not a dot-segment; the
     * server then decodes it back to the literal value. Identifiers like
     * {@code 6.2.0} round-trip unchanged.
     */
    private static final String PATH_SEGMENT_UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_~";

    /**
     * A view of the template data whose top-level String values are percent-encoded
     * as single path segments. Used for rendering the request PATH only — never the
     * body or query.
     * <p>
     * <b>Why:</b> LLM tool arguments are merged into the template data as top-level
     * entries ({@code HttpCallToolsProvider#safeTemplateMerge}) and are substituted
     * into path templates like {@code /agentstore/agents/{id}} as raw text. A
     * model- or prompt-injection-supplied value of
     * {@code ../../secretstore/secrets/default/key} therefore rewrites which
     * endpoint the call hits — and because read patterns are commonly exempt from
     * the HITL gate (gate classification uses the CONFIGURED endpoint, not the
     * resolved one), a GET tool could be steered to any same-host GET endpoint with
     * no human in the loop, carrying whatever Authorization the config resolves.
     * {@code ?} and {@code #} similarly let a value rewrite the query or truncate
     * the URL. Percent-encoding the substituted value makes it one literal path
     * segment, whatever it contains.
     * <p>
     * <b>Nested values are encoded too.</b> An earlier version of this stopped at
     * the top level, reasoning that tool arguments are the model-controlled surface
     * while conversation state under {@code properties} / {@code memory} /
     * {@code context} belongs to the agent author. That was wrong: a property is
     * routinely captured FROM user input — {@code PropertySetterTask} with
     * {@code valueString: "{memory.current.input}"} is the documented slot-filling
     * pattern — so {@code /agentstore/agents/{properties.agentId}} substitutes
     * whatever the user typed. The same injection, one indirection further out. Who
     * <em>authored</em> the template is not who <em>controls</em> the value.
     * <p>
     * Recursion is depth-bounded ({@link #MAX_PATH_VIEW_DEPTH}). Template data is
     * rebuilt per turn from conversation memory and is not expected to nest deeply;
     * the bound exists so a pathological or self-referential structure cannot turn
     * a request into a stack overflow. Past it the value is passed through
     * unencoded rather than dropped: losing data would silently change what a
     * legitimate template renders, and nothing that deep is reachable by a path
     * expression in practice.
     * <p>
     * Non-String scalars (numbers, booleans) are left alone — Qute renders them via
     * {@code toString()} and none can yield a {@code /}, {@code ?} or {@code #}.
     * Lists are walked because {@code {items.0}} is a valid expression.
     * <p>
     * Applied inside {@code buildRequest}, which serves both {@code resolve()} and
     * {@code execute()} — the gate-time fingerprint and the executed request see
     * identical encoding, so request pinning is unaffected.
     */
    private static Map<String, Object> pathSafeView(Map<String, Object> templateDataObjects) {
        var view = new HashMap<String, Object>(templateDataObjects.size());
        templateDataObjects.forEach((key, value) -> view.put(key, encodePathValue(value, 0)));
        return view;
    }

    /**
     * Depth ceiling for {@link #pathSafeView}'s recursion — see its javadoc for why
     * the bound exists and why exceeding it passes the value through.
     */
    static final int MAX_PATH_VIEW_DEPTH = 10;

    static Object encodePathValue(Object value, int depth) {
        if (value instanceof String stringValue) {
            return encodePathSegment(stringValue);
        }
        if (depth >= MAX_PATH_VIEW_DEPTH) {
            return value;
        }
        if (value instanceof Map<?, ?> nested) {
            var copy = new LinkedHashMap<Object, Object>(nested.size());
            nested.forEach((nestedKey, nestedValue) -> copy.put(nestedKey, encodePathValue(nestedValue, depth + 1)));
            return copy;
        }
        if (value instanceof List<?> items) {
            var copy = new ArrayList<>(items.size());
            for (Object item : items) {
                copy.add(encodePathValue(item, depth + 1));
            }
            return copy;
        }
        return value;
    }

    /** Percent-encodes every byte outside RFC 3986 unreserved, UTF-8. */
    static String encodePathSegment(String value) {
        var out = new StringBuilder(value.length());
        for (byte b : value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if (PATH_SEGMENT_UNRESERVED.indexOf(c) >= 0) {
                out.append(c);
            } else {
                out.append('%').append(String.format("%02X", b & 0xFF));
            }
        }
        return out.toString();
    }

}
