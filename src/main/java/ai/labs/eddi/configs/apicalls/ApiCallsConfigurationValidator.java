/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.apicalls;

import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.apicalls.model.ForEachInstruction;

/**
 * Save-time consistency rules for an {@link ApiCallsConfiguration} — the
 * mistakes that are accepted by the schema but fail every turn once deployed.
 * <p>
 * The first one: {@code saveResponse: true} without a
 * {@code responseObjectName}. The executor stored the response body under a
 * {@code null} key, the memory serializer refused it ("Null key for a Map not
 * allowed in JSON"), and the conversation ended in ERROR on every turn that ran
 * the call — a config error reported as a runtime failure, at the worst
 * possible distance from its cause.
 */
public final class ApiCallsConfigurationValidator {

    private ApiCallsConfigurationValidator() {
    }

    /**
     * @return a message describing the first problem found, naming the call and how
     *         to fix it, or {@code null} when the configuration is consistent
     */
    public static String firstProblem(ApiCallsConfiguration configuration) {
        if (configuration == null || configuration.getHttpCalls() == null) {
            return null;
        }
        for (ApiCall call : configuration.getHttpCalls()) {
            if (call == null) {
                continue;
            }
            if (Boolean.TRUE.equals(call.getSaveResponse()) && isBlank(call.getResponseObjectName())) {
                return "http call '" + call.getName() + "': saveResponse is true but responseObjectName is missing. "
                        + "Set responseObjectName (the key the response is stored under in conversation memory), "
                        + "or set saveResponse to false.";
            }
            String forEachProblem = forEachProblem(call);
            if (forEachProblem != null) {
                return "http call '" + call.getName() + "': " + forEachProblem;
            }
        }
        return null;
    }

    /**
     * The first inconsistency of the call's {@code forEach}, or null. Each one is a
     * config that either cannot run or would silently not mean what it says.
     */
    static String forEachProblem(ApiCall call) {
        ForEachInstruction forEach = call.getForEach();
        if (forEach == null) {
            return null;
        }
        if (Boolean.TRUE.equals(call.getFireAndForget())) {
            return "forEach and fireAndForget cannot be combined — forEach collects every item's result. For fire-and-forget "
                    + "fan-out use preRequest.batchRequests and remove forEach.";
        }
        if (call.getPreRequest() != null && call.getPreRequest().getBatchRequests() != null) {
            return "forEach and preRequest.batchRequests cannot be combined — forEach already sends one request per element. "
                    + "Remove batchRequests.";
        }
        if (isBlank(forEach.getPathToTargetArray())) {
            return "forEach.pathToTargetArray is missing. Set it to the dot path of the array to run the call for, "
                    + "e.g. 'properties.orders'.";
        }
        Integer concurrency = forEach.getMaxConcurrency();
        if (concurrency != null && (concurrency < 1 || concurrency > ForEachInstruction.MAX_CONCURRENCY_CEILING)) {
            return "forEach.maxConcurrency " + concurrency + " is outside 1.." + ForEachInstruction.MAX_CONCURRENCY_CEILING
                    + ". Leave it unset for one item at a time.";
        }
        if (!ForEachInstruction.isKnownOnItemFailure(forEach.getOnItemFailure())) {
            return "forEach.onItemFailure '" + forEach.getOnItemFailure() + "' is not one of '" + ForEachInstruction.ON_ITEM_FAILURE_CONTINUE
                    + "' or '" + ForEachInstruction.ON_ITEM_FAILURE_FAIL + "'.";
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
