/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import ai.labs.eddi.configs.apicalls.model.*;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.CallerIdentityResolver;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.httpclient.IHttpClient;
import ai.labs.eddi.engine.httpclient.IRequest;
import ai.labs.eddi.engine.httpclient.IResponse;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.modules.llm.tools.UrlValidationUtils;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import ai.labs.eddi.utils.LogSanitizer;
import ai.labs.eddi.secrets.SecretResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static ai.labs.eddi.utils.MatchingUtilities.executeValuePath;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;
import static jakarta.ws.rs.core.MediaType.TEXT_PLAIN;
import static java.lang.String.format;
import static java.lang.System.currentTimeMillis;
import static java.util.Objects.requireNonNullElse;

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

    private final IHttpClient httpClient;
    private final IJsonSerialization jsonSerialization;
    private final IRuntime runtime;
    private final PrePostUtils prePostUtils;
    private final GlobalVariableResolver globalVariableResolver;
    private final SecretResolver secretResolver;
    private final CallerIdentityResolver callerIdentityResolver;
    private final CallerIdentityContext callerIdentityContext;
    private final RequestRedactor requestRedactor;
    private final boolean ssrfProtectionEnabled;
    private final long defaultTimeoutInMillis;
    private final int defaultMaxResponseSizeInBytes;

    @Inject
    public ApiCallExecutor(IHttpClient httpClient, IJsonSerialization jsonSerialization, IRuntime runtime, PrePostUtils prePostUtils,
            GlobalVariableResolver globalVariableResolver, SecretResolver secretResolver, CallerIdentityResolver callerIdentityResolver,
            CallerIdentityContext callerIdentityContext, RequestRedactor requestRedactor,
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
        this.ssrfProtectionEnabled = ssrfProtectionEnabled;
        this.defaultTimeoutInMillis = defaultTimeoutInMillis;
        this.defaultMaxResponseSizeInBytes = defaultMaxResponseSizeInBytes;
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

        try {
            IWritableConversationStep currentStep = memory.getCurrentStep();

            var preRequest = call.getPreRequest();
            templateDataObjects = prePostUtils.executePreRequestPropertyInstructions(memory, templateDataObjects, preRequest);

            if (call.getFireAndForget()) {
                executeFireAndForgetCalls(targetServerUrl, call, templateDataObjects);
                return Collections.emptyMap();
            } else {
                IRequest request;
                IResponse response = null;
                boolean retryCall = false;
                int amountOfExecutions = 0;
                Map<String, Object> result = new HashMap<>();

                do {
                    request = buildRequest(targetServerUrl, call, templateDataObjects);
                    var objectName = call.getName() + "Request";
                    var requestMap = request.toMap();
                    // Scrub resolved secrets — headers, query parameters and body — from
                    // the request map before it is persisted to conversation memory. The
                    // actual request was already built and still carries them; each entry
                    // here is REPLACED with a redacted copy, so this only affects the debug
                    // record. Shares RequestRedactor with the approval preview so the two
                    // cannot disagree about what counts as a credential.
                    requestRedactor.redactRequestMap(requestMap);
                    prePostUtils.createMemoryEntry(currentStep, requestMap, objectName, KEY_HTTP_CALLS);
                    response = executeAndMeasureRequest(call, request, retryCall, amountOfExecutions);

                    var isResponseSuccessful = response.getHttpCode() >= 200 && response.getHttpCode() < 300;
                    if (!isResponseSuccessful) {
                        String message = "ApiCall (%s) didn't return http code 2xx, instead %s.";
                        LOGGER.warn(format(message, call.getName(), response.getHttpCode()));
                        LOGGER.warn("Error Msg:" + response.getHttpCodeMessage());

                        // Store error body in memory so downstream templates / rules can inspect it
                        if (call.getSaveResponse()) {
                            String errorBody = response.getContentAsString();
                            String errorObjectName = call.getResponseObjectName() + "Error";
                            if (errorBody != null && !errorBody.isBlank()) {
                                String truncatedError = errorBody.length() > 2000
                                        ? errorBody.substring(0, 2000)
                                        : errorBody;
                                prePostUtils.createMemoryEntry(currentStep, truncatedError, errorObjectName, KEY_HTTP_CALLS);
                                templateDataObjects.put(errorObjectName, truncatedError);
                            }
                            prePostUtils.createMemoryEntry(currentStep, response.getHttpCode(),
                                    call.getResponseObjectName() + "HttpCode", KEY_HTTP_CALLS);
                            templateDataObjects.put(call.getResponseObjectName() + "HttpCode", response.getHttpCode());
                        }
                    }

                    var responseHeaderObjectName = call.getResponseHeaderObjectName();
                    if (!isNullOrEmpty(responseHeaderObjectName)) {
                        var responseObjectHeader = requireNonNullElse(response.getHttpHeader(), new HashMap<>());
                        templateDataObjects.put(responseHeaderObjectName, responseObjectHeader);
                        prePostUtils.createMemoryEntry(currentStep, responseObjectHeader, responseHeaderObjectName, KEY_HTTP_CALLS);
                        result.put("headers", responseObjectHeader);
                    }

                    if (isResponseSuccessful && call.getSaveResponse()) {
                        // Success bodies land in conversation memory, which is persisted as a
                        // single document — cap them just like the error bodies above.
                        final String responseBody = truncateResponseBody(response.getContentAsString(), resolveMaxResponseSize(call),
                                call.getName());
                        String actualContentType = response.getHttpHeader().get(CONTENT_TYPE);
                        if (actualContentType != null) {
                            actualContentType = actualContentType.split(";")[0];
                        } else {
                            actualContentType = "<not-present>";
                        }

                        Object responseObject;
                        if (CONTENT_TYPE_APPLICATION_JSON.equals(actualContentType)) {
                            try {
                                responseObject = jsonSerialization.deserialize(responseBody, Object.class);
                            } catch (java.io.IOException jsonEx) {
                                LOGGER.warnf("ApiCall (%s) returned application/json but body is not valid JSON, falling back to raw string: %s",
                                        call.getName(), jsonEx.getMessage());
                                responseObject = responseBody;
                            }
                        } else {
                            if (!actualContentType.startsWith("<not-present>") && !actualContentType.startsWith("text")) {
                                var message = "ApiCall (%s) didn't return application/json, text/plain nor text/html "
                                        + "as content-type, instead was (%s)";
                                LOGGER.warn(format(message, call.getName(), actualContentType));
                            }
                            responseObject = responseBody;
                        }

                        var responseObjectName = call.getResponseObjectName();
                        templateDataObjects.put(responseObjectName, responseObject);
                        prePostUtils.createMemoryEntry(currentStep, responseObject, responseObjectName, KEY_HTTP_CALLS);
                        result.put("body", responseObject);
                        result.put("httpCode", response.getHttpCode());
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
                prePostUtils.runPostResponse(memory, call.getPostResponse(), templateDataObjects, response.getHttpCode(), false);

                return result;
            }
        } catch (Exception e) {
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
            var requestMap = buildRequest(targetServerUrl, call, templateDataObjects).toMap();
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
                    requestRedactor.redactHeaders(headers),
                    body == null ? null : body.toString(),
                    !canExecuteDivergeFromResolve(call));
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
        if (postResponse instanceof ai.labs.eddi.configs.apicalls.model.HttpPostResponse httpPostResponse) {
            var retry = httpPostResponse.getRetryApiCallInstruction();
            return retry != null && retry.getMaxRetries() >= 1;
        }
        return false;
    }

    private IResponse executeAndMeasureRequest(ApiCall call, IRequest request, boolean retryCall, int amountOfExecutions)
            throws IRequest.HttpRequestException, ExecutionException, InterruptedException {

        LOGGER.info(call.getName() + " Request: " + (amountOfExecutions > 0 ? amountOfExecutions + ". retry - " : "") + request.toString());
        int delayInMillis = getDelayInMillis(call, retryCall, amountOfExecutions);

        long executionStart = currentTimeMillis();
        IResponse response = executeRequest(request, delayInMillis);
        long executionEnd = currentTimeMillis();
        long duration = executionEnd - executionStart;

        LOGGER.info(call.getName() + " Response: " + response.toString());
        LOGGER.info(call.getName()
                + format(" Execution time: Duration: %sms Delay: %sms Total: %sms\n", duration, delayInMillis, duration + delayInMillis));

        return response;
    }

    private void executeFireAndForgetCalls(String targetServerUrl, ApiCall call, Map<String, Object> templateDataObjects)
            throws ITemplatingEngine.TemplateEngineException, IRequest.HttpRequestException {

        var preRequest = call.getPreRequest();
        var callName = call.getName();

        if (preRequest != null && preRequest.getBatchRequests() != null) {
            BatchRequestBuildingInstruction batchRequest = preRequest.getBatchRequests();
            if (batchRequest.getExecuteCallsSequentially() == null) {
                batchRequest.setExecuteCallsSequentially(false);
            }

            // A batch runs on a thread of its own, where the caller binding does not
            // follow, so ${caller:...} in these requests would fail closed without
            // propagate() carrying it across.
            runtime.submitCallable(callerIdentityContext.propagate(() -> {
                List<Object> batchIterationList = prePostUtils.buildIterationValues(batchRequest.getIterationObjectName(),
                        batchRequest.getPathToTargetArray(), batchRequest.getTemplateFilterExpression(), templateDataObjects);

                IRequest request;
                for (Object iterationObject : batchIterationList) {
                    templateDataObjects.put(batchRequest.getIterationObjectName(), iterationObject);
                    request = buildRequest(targetServerUrl, call, templateDataObjects);
                    if (batchRequest.getExecuteCallsSequentially()) {
                        long executionStart = currentTimeMillis();
                        LOGGER.info(callName + " Batch Request: " + request);
                        IResponse response = request.send();
                        logExecutionResponse(response, callName, executionStart, currentTimeMillis(), false);
                    } else {
                        executeFireAndForgetCall(request, callName);
                    }
                }
                return null;
            }), null);
        } else {
            IRequest request = buildRequest(targetServerUrl, call, templateDataObjects);
            executeFireAndForgetCall(request, callName);
        }
    }

    private static void executeFireAndForgetCall(IRequest request, String httpCallsName) throws IRequest.HttpRequestException {

        LOGGER.info(httpCallsName + " Request (f'n'f): " + request);
        long executionStart = currentTimeMillis();
        request.send(res -> logExecutionResponse(res, httpCallsName, executionStart, currentTimeMillis(), true));
    }

    private static void logExecutionResponse(IResponse response, String httpCallsName, long executionStart, long executionEnd,
                                             boolean fireAndForget) {

        long duration = executionEnd - executionStart;
        LOGGER.info(httpCallsName + " Response " + (fireAndForget ? "(f'n'f)" : "") + ": " + response.toString());
        LOGGER.info(httpCallsName + format(" Execution time: %sms\n", duration));
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

    private IRequest buildRequest(String targetServerUrl, ApiCall call, Map<String, Object> templateDataObjects)
            throws ITemplatingEngine.TemplateEngineException {

        Request requestConfig = call.getRequest();
        String path = requestConfig.getPath().trim();
        if (!path.startsWith(SLASH_CHAR) && !path.isEmpty() && !path.startsWith("http")) {
            path = SLASH_CHAR + path;
        }
        var targetDestination = !path.startsWith("http") ? targetServerUrl + path : path;
        var targetUriStr = prePostUtils.templateValues(targetDestination, pathSafeView(templateDataObjects));
        // Resolve global variable references, then vault references in URL
        targetUriStr = globalVariableResolver.resolveValue(targetUriStr);
        targetUriStr = secretResolver.resolveValue(targetUriStr);
        // The path is not caller-resolved either, and a surviving reference would
        // reach URI.create() to fail as "Illegal character in path" — an error that
        // names the symptom and not the cause.
        callerIdentityResolver.rejectAnyReference(targetUriStr, "the request path");
        var targetUri = URI.create(targetUriStr);
        var requestBody = prePostUtils.templateValues(requestConfig.getBody(), templateDataObjects);
        // Resolve global variable references, then vault references in request body
        requestBody = globalVariableResolver.resolveValue(requestBody);
        requestBody = secretResolver.resolveValue(requestBody);

        // SSRF protection (opt-in): validate the fully-resolved target and disable
        // redirect-following so a 3xx cannot bounce the request to an internal host.
        // Off by default to preserve calls to internal/private APIs.
        if (ssrfProtectionEnabled) {
            UrlValidationUtils.validateUrl(targetUri.toString());
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

        Map<String, String> headers = requestConfig.getHeaders();
        for (String headerName : headers.keySet()) {
            String headerValue = prePostUtils.templateValues(headers.get(headerName), templateDataObjects);
            // Resolve global variable references, then vault references in headers
            headerValue = globalVariableResolver.resolveValue(headerValue);
            headerValue = secretResolver.resolveValue(headerValue);
            // Caller identity resolves last and needs the target URI: the token is
            // only released when the call goes back to the caller's own origin.
            headerValue = callerIdentityResolver.resolveValue(headerValue, targetUri);
            request.setHttpHeader(headerName, headerValue);
        }

        Map<String, String> queryParams = requestConfig.getQueryParams();
        for (String queryParam : queryParams.keySet()) {
            var qpValue = prePostUtils.templateValues(queryParams.get(queryParam), templateDataObjects);
            // Resolve global variable references, then vault references in query params
            qpValue = globalVariableResolver.resolveValue(qpValue);
            qpValue = secretResolver.resolveValue(qpValue);
            // A token in a query string leaks via access logs and proxies.
            callerIdentityResolver.rejectTokenReference(qpValue, "a query parameter");
            qpValue = callerIdentityResolver.resolveValue(qpValue, targetUri);
            request.setQueryParam(queryParam, qpValue);
        }
        return request;
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
     * <b>Why only top-level Strings:</b> that is exactly the model-controlled
     * surface. Conversation state lives in nested maps under reserved keys
     * ({@code properties}, {@code memory}, {@code context}, ... — see
     * {@code HttpCallToolsProvider#RESERVED_TEMPLATE_KEYS}, which the merge refuses
     * to overwrite), so hand-authored templates like {@code {properties.agentId}}
     * keep their existing behaviour unchanged.
     * <p>
     * Applied inside {@code buildRequest}, which serves both {@code resolve()} and
     * {@code execute()} — the gate-time fingerprint and the executed request see
     * identical encoding, so request pinning is unaffected.
     */
    private static Map<String, Object> pathSafeView(Map<String, Object> templateDataObjects) {
        var view = new HashMap<String, Object>(templateDataObjects.size());
        templateDataObjects.forEach((key, value) -> view.put(key,
                value instanceof String stringValue ? encodePathSegment(stringValue) : value));
        return view;
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
