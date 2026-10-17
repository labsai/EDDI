/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.apicalls;

import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ApiCallsConfigurationValidator")
class ApiCallsConfigurationValidatorTest {

    private static ApiCall call(String name, Boolean saveResponse, String responseObjectName) {
        ApiCall call = new ApiCall();
        call.setName(name);
        call.setSaveResponse(saveResponse);
        call.setResponseObjectName(responseObjectName);
        return call;
    }

    private static ApiCallsConfiguration configOf(ApiCall... calls) {
        ApiCallsConfiguration configuration = new ApiCallsConfiguration();
        configuration.setHttpCalls(new ArrayList<>(Arrays.asList(calls)));
        return configuration;
    }

    @Test
    @DisplayName("saveResponse without a responseObjectName is the first problem, naming the call")
    void saveResponseWithoutName() {
        String problem = ApiCallsConfigurationValidator.firstProblem(configOf(call("ok", true, "weather"), call("broken", true, null)));

        assertTrue(problem.startsWith("http call 'broken'"), problem);
        assertTrue(problem.contains("responseObjectName"), problem);
    }

    @Test
    @DisplayName("a blank responseObjectName is as missing as a null one")
    void blankName() {
        assertNotNull(ApiCallsConfigurationValidator.firstProblem(configOf(call("broken", true, "  "))));
    }

    @Test
    @DisplayName("saveResponse false, or unset, needs no name")
    void noSaveNoName() {
        assertNull(ApiCallsConfigurationValidator.firstProblem(configOf(call("a", false, null), call("b", null, null))));
    }

    @Test
    @DisplayName("a consistent config, a null config and a null call list pass")
    void consistent() {
        assertNull(ApiCallsConfigurationValidator.firstProblem(configOf(call("a", true, "a"))));
        assertNull(ApiCallsConfigurationValidator.firstProblem(null));
        assertNull(ApiCallsConfigurationValidator.firstProblem(new ApiCallsConfiguration()));
    }
}
