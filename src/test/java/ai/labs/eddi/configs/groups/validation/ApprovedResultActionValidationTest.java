/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.validation;

import ai.labs.eddi.configs.apicalls.IApiCallsStore;
import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.apicalls.model.HttpPreRequest;
import ai.labs.eddi.configs.apicalls.model.Request;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ApprovedAction;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextScope;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.OnApprovedConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import ai.labs.eddi.configs.hitl.HitlGranularity;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApprovedResultActionValidationTest {

    private static final String URI = "eddi://ai.labs.apicalls/apicallstore/apicalls/6740a1b2c3d4e5f6a7b8c9d0?version=2";

    private IApiCallsStore apiCallsStore;
    private ServiceJudgeVaultGrants vaultGrants;
    private ApprovedResultActionValidation validation;
    private ApiCall call;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        apiCallsStore = mock(IApiCallsStore.class);
        vaultGrants = mock(ServiceJudgeVaultGrants.class);
        when(vaultGrants.problems(anyString(), anyMap())).thenReturn(List.of());
        Instance<ResourceAccessGuard> guard = mock(Instance.class);
        when(guard.isResolvable()).thenReturn(false);
        validation = new ApprovedResultActionValidation(apiCallsStore, vaultGrants, guard);

        call = new ApiCall();
        call.setName("postComment");
        var request = new Request();
        request.setMethod("POST");
        request.setPath("/repos/acme/site/issues/{task.subject}/comments");
        request.setHeaders(Map.of("Authorization", "Bearer ${vault:github-token}"));
        request.setBody("{\"body\": {json:serialize(task.result)}}");
        call.setRequest(request);
        var httpCalls = new ApiCallsConfiguration();
        httpCalls.setTargetServerUrl("https://api.github.com");
        httpCalls.setHttpCalls(List.of(call));
        when(apiCallsStore.read("6740a1b2c3d4e5f6a7b8c9d0", 2)).thenReturn(httpCalls);
    }

    private static DiscussionPhase phase(PhaseType type, boolean requiresApproval, ApprovedAction action) {
        return new DiscussionPhase("Draft", type, "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1, requiresApproval, null,
                false, null, null, new OnApprovedConfig(action, null));
    }

    private static AgentGroupConfiguration config(HitlGranularity granularity, DiscussionPhase phase) {
        var config = new AgentGroupConfiguration();
        config.setPhases(List.of(phase));
        if (granularity != null) {
            var hitl = new AgentGroupConfiguration.HitlConfig();
            hitl.setGranularity(granularity);
            config.setHitlConfig(hitl);
        }
        return config;
    }

    private static ApprovedAction action(String uri, String call) {
        return new ApprovedAction(null, uri, call);
    }

    @Test
    void aValidAction_hasNoProblems_andDefaultsToHttpCallAndFailTask() {
        var phase = phase(PhaseType.EXECUTE, true, action(URI, "postComment"));

        assertEquals(List.of(), validation.problems(config(HitlGranularity.TASK, phase)));
        assertEquals(AgentGroupConfiguration.ApprovedActionType.HTTPCALL, phase.onApproved().action().type());
        assertEquals(AgentGroupConfiguration.ApprovedActionFailurePolicy.FAIL_TASK, phase.onApproved().onFailure());
    }

    @Test
    void aPhaseWithoutOnApproved_isNotChecked() {
        var plain = new DiscussionPhase("Talk", PhaseType.OPINION);
        assertEquals(List.of(), validation.problems(config(null, plain)));
        verifyNoInteractions(apiCallsStore);
    }

    @Test
    void notExecute_notGated_notTaskGranularity_areEachNamed() {
        var problems = ApprovedResultActionValidation.structuralProblems(
                config(HitlGranularity.PHASE, phase(PhaseType.OPINION, false, action(URI, "postComment"))));

        assertEquals(3, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("only valid on an EXECUTE phase"));
        assertTrue(problems.get(1).contains("requiresApproval: true"));
        assertTrue(problems.get(2).contains("hitlConfig.granularity: TASK"));
        assertTrue(problems.get(0).startsWith("phases[0].onApproved"));
    }

    @Test
    void aMissingAction_callOrUnpinnedUri_isRefused() {
        assertTrue(ApprovedResultActionValidation.structuralProblems(config(HitlGranularity.TASK, phase(PhaseType.EXECUTE, true, null)))
                .getFirst().contains(".action is required"));
        var problems = ApprovedResultActionValidation.structuralProblems(config(HitlGranularity.TASK,
                phase(PhaseType.EXECUTE, true, action("eddi://ai.labs.apicalls/apicallstore/apicalls/6740a1b2c3d4e5f6a7b8c9d0", " "))));
        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains(".action.call is required"));
        assertTrue(problems.get(1).contains("explicit ?version="));
        assertTrue(ApprovedResultActionValidation.structuralProblems(config(HitlGranularity.TASK,
                phase(PhaseType.EXECUTE, true, action("eddi://ai.labs.langchain/langchainstore/langchains/6740a1b2c3d4e5f6a7b8c9d0?version=1", "x"))))
                .getFirst().contains("must be an httpcalls URI"));
    }

    @Test
    void anUnknownHttpCallsConfiguration_isRefused() throws Exception {
        when(apiCallsStore.read("6740a1b2c3d4e5f6a7b8c9d0", 2)).thenThrow(new IResourceStore.ResourceNotFoundException("nope"));

        var problems = validation.problems(config(HitlGranularity.TASK, phase(PhaseType.EXECUTE, true, action(URI, "postComment"))));

        assertEquals(1, problems.size());
        assertTrue(problems.getFirst().contains("does not exist"), problems.getFirst());
    }

    @Test
    void anUnknownCallName_listsTheCallsThereAre() {
        var problems = validation.problems(config(HitlGranularity.TASK, phase(PhaseType.EXECUTE, true, action(URI, "postCommnet"))));

        assertEquals(1, problems.size());
        assertTrue(problems.getFirst().contains("is not a call of that httpcalls configuration"));
        assertTrue(problems.getFirst().contains("postComment"));
    }

    @Test
    void aFireAndForgetCall_isRefused() {
        call.setFireAndForget(true);

        var problems = validation.referenceProblems("a", action(URI, "postComment"));

        assertEquals(1, problems.size());
        assertTrue(problems.getFirst().contains("fire-and-forget"));
    }

    @Test
    void connectionAndCallerReferences_areRefused() {
        call.getRequest().setHeaders(Map.of("Authorization", "${connection:github}", "X-User", "${caller:userId}"));

        var problems = validation.referenceProblems("a", action(URI, "postComment"));

        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.stream().allMatch(p -> p.contains("${connection:...}, ${caller:...} or ${context:...}")));
    }

    @Test
    void vaultReferences_mustBeGrantedToAllAgents() {
        when(vaultGrants.problems(anyString(), anyMap()))
                .thenReturn(List.of("a call.request.headers.Authorization references vault secret ${vault:github-token}, which is not granted"));

        var problems = validation.referenceProblems("a", action(URI, "postComment"));

        assertEquals(1, problems.size());
        verify(vaultGrants).problems(eq("a call"),
                argThat(fields -> "Bearer ${vault:github-token}".equals(fields.get("request.headers.Authorization"))
                        && fields.containsKey("request.body") && fields.containsKey("targetServerUrl")));
    }

    @Test
    void aVaultReferenceSplitAcrossServerUrlAndPath_isCheckedAsTheJoinedUrl() throws Exception {
        var httpCalls = new ApiCallsConfiguration();
        httpCalls.setTargetServerUrl("https://api.example.com/${vault:");
        call.getRequest().setPath("restricted}/comments");
        httpCalls.setHttpCalls(List.of(call));
        when(apiCallsStore.read("6740a1b2c3d4e5f6a7b8c9d0", 2)).thenReturn(httpCalls);

        validation.referenceProblems("a", action(URI, "postComment"));

        verify(vaultGrants).problems(eq("a call"),
                argThat(fields -> "https://api.example.com/${vault:/restricted}/comments".equals(fields.get("request URL"))));
    }

    @Test
    void theJoinedUrl_mirrorsTheExecutor() {
        assertEquals("https://h/x", ApprovedResultActionValidation.effectiveUrl("https://h", "x"));
        assertEquals("https://h/x", ApprovedResultActionValidation.effectiveUrl("https://h", " /x "));
        assertEquals("https://other/x", ApprovedResultActionValidation.effectiveUrl("https://h", "https://other/x"));
        assertEquals("https://h", ApprovedResultActionValidation.effectiveUrl("https://h", ""));
    }

    @Test
    void preRequestPropertyInstructions_areRefused() {
        var preRequest = new HttpPreRequest();
        preRequest.setPropertyInstructions(List.of());
        call.setPreRequest(preRequest);

        var problems = validation.referenceProblems("a", action(URI, "postComment"));

        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().contains("preRequest.propertyInstructions"));
    }
}
