/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.apicalls;

import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;

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
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
