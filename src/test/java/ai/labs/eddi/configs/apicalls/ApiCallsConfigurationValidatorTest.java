/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.apicalls;

import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.apicalls.model.BatchRequestBuildingInstruction;
import ai.labs.eddi.configs.apicalls.model.ForEachInstruction;
import ai.labs.eddi.configs.apicalls.model.HttpPreRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Nested
    @DisplayName("forEach")
    class ForEach {

        private ApiCall loop(ForEachInstruction forEach) {
            ApiCall call = call("loop", true, "results");
            call.setForEach(forEach);
            return call;
        }

        private ForEachInstruction valid() {
            var forEach = new ForEachInstruction();
            forEach.setPathToTargetArray("properties.items");
            return forEach;
        }

        @Test
        @DisplayName("a minimal forEach passes, and so do both known policies and the concurrency bounds")
        void validPasses() {
            assertNull(ApiCallsConfigurationValidator.firstProblem(configOf(loop(valid()))));
            for (String policy : new String[]{"continue", "fail", "FAIL"}) {
                var forEach = valid();
                forEach.setOnItemFailure(policy);
                assertNull(ApiCallsConfigurationValidator.firstProblem(configOf(loop(forEach))), policy);
            }
            for (int concurrency : new int[]{1, ForEachInstruction.MAX_CONCURRENCY_CEILING}) {
                var forEach = valid();
                forEach.setMaxConcurrency(concurrency);
                assertNull(ApiCallsConfigurationValidator.firstProblem(configOf(loop(forEach))), "concurrency " + concurrency);
            }
        }

        @Test
        @DisplayName("forEach with fireAndForget is refused and points at batchRequests")
        void withFireAndForget() {
            ApiCall call = loop(valid());
            call.setFireAndForget(true);
            String problem = ApiCallsConfigurationValidator.firstProblem(configOf(call));
            assertTrue(problem.contains("batchRequests"), problem);
        }

        @Test
        @DisplayName("forEach with batchRequests is refused")
        void withBatchRequests() {
            ApiCall call = loop(valid());
            var preRequest = new HttpPreRequest();
            preRequest.setBatchRequests(new BatchRequestBuildingInstruction());
            call.setPreRequest(preRequest);
            assertNotNull(ApiCallsConfigurationValidator.firstProblem(configOf(call)));
        }

        @Test
        @DisplayName("a missing path, an out-of-range concurrency and an unknown policy are refused")
        void invalidFields() {
            var noPath = valid();
            noPath.setPathToTargetArray(" ");
            assertTrue(ApiCallsConfigurationValidator.firstProblem(configOf(loop(noPath))).contains("pathToTargetArray"));

            for (int concurrency : new int[]{0, ForEachInstruction.MAX_CONCURRENCY_CEILING + 1}) {
                var forEach = valid();
                forEach.setMaxConcurrency(concurrency);
                assertTrue(ApiCallsConfigurationValidator.firstProblem(configOf(loop(forEach))).contains("maxConcurrency"), "" + concurrency);
            }

            var policy = valid();
            policy.setOnItemFailure("ignore");
            assertTrue(ApiCallsConfigurationValidator.firstProblem(configOf(loop(policy))).contains("onItemFailure"));
        }

        @Test
        @DisplayName("an unknown stored policy fails the step at run time rather than passing silently")
        void unknownPolicyFailsClosed() {
            var forEach = valid();
            assertFalse(forEach.failsStepOnItemFailure());
            forEach.setOnItemFailure("continue");
            assertFalse(forEach.failsStepOnItemFailure());
            forEach.setOnItemFailure("fail");
            assertTrue(forEach.failsStepOnItemFailure());
            forEach.setOnItemFailure("ignore");
            assertTrue(forEach.failsStepOnItemFailure());
        }
    }
}
