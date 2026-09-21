/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.rest;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.OperatorMetricsService;
import ai.labs.eddi.engine.api.model.OperatorCanaryReport;
import ai.labs.eddi.engine.api.model.OperatorGateDryRunRequest;
import ai.labs.eddi.engine.api.model.OperatorGateStatusReport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import io.micrometer.core.instrument.Counter;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * Validates at the REST boundary, then delegates — the service tests own the
 * metric assertions. {@code gateDryRun} is the exception: it answers here, so
 * its classification behaviour is asserted here.
 */
@DisplayName("RestOperatorMetrics")
class RestOperatorMetricsTest {

    private SimpleMeterRegistry registry;
    private IAgentStore agentStore;
    private RestOperatorMetrics rest;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        var service = new OperatorMetricsService(registry);
        service.registerGateGauge();
        agentStore = mock(IAgentStore.class);
        rest = new RestOperatorMetrics(service, agentStore);
    }

    @Test
    @DisplayName("a valid canary report is 204 and reaches the meter")
    void validCanaryReportIs204() {
        var response = rest.reportCanaryResult(new OperatorCanaryReport("pass", 100L));
        assertEquals(204, response.getStatus());
        assertEquals(1.0, registry.counter("eddi.operator.canary", "outcome", "pass").count());
    }

    @Test
    @DisplayName("a null report body is rejected, and says so rather than blaming the outcome field")
    void nullCanaryReportIsRejected() {
        // A caller who sent no body should not be sent looking at a field they
        // never supplied — the gate-status endpoint already words this correctly.
        var e = assertThrows(BadRequestException.class, () -> rest.reportCanaryResult(null));
        assertTrue(e.getMessage().contains("body"), e.getMessage());
    }

    @Test
    @DisplayName("an outcome outside the fixed vocabulary is rejected before it reaches the meter")
    void invalidOutcomeIsRejected() {
        // The vocabulary is enforced HERE, not trusted from the client — a free-text
        // outcome would let cardinality grow unbounded on a metric label.
        assertThrows(BadRequestException.class, () -> rest.reportCanaryResult(new OperatorCanaryReport("PASS", 100L)));
        assertThrows(BadRequestException.class, () -> rest.reportCanaryResult(new OperatorCanaryReport("", 100L)));
        assertThrows(BadRequestException.class, () -> rest.reportCanaryResult(new OperatorCanaryReport(null, 100L)));
        assertEquals(0.0, registry.find("eddi.operator.canary").counters().stream().mapToDouble(Counter::count)
                .sum());
    }

    @Test
    @DisplayName("a valid gate-status report is 204 and moves the gauge")
    void validGateStatusReportIs204() {
        var response = rest.reportGateStatus(new OperatorGateStatusReport(true));
        assertEquals(204, response.getStatus());
        assertEquals(1.0, registry.find("eddi.operator.gate.verified").gauge().value());
    }

    @Test
    @DisplayName("a null gate-status body is rejected")
    void nullGateStatusReportIsRejected() {
        assertThrows(BadRequestException.class, () -> rest.reportGateStatus(null));
    }

    @Nested
    @DisplayName("gateDryRun")
    class GateDryRun {

        /** The operator's real gate shape — method-based require, GET exempt. */
        private AgentConfiguration operatorLikeAgent() {
            var approvals = new ToolApprovalsConfig();
            approvals.setRequireApproval(List.of("http.post:*", "http.put:*", "http.patch:*", "http.delete:*"));
            approvals.setExempt(List.of("http.get:*"));
            var hitl = new AgentConfiguration.HitlConfig();
            hitl.setToolApprovals(approvals);
            var config = new AgentConfiguration();
            config.setHitlConfig(hitl);
            return config;
        }

        private OperatorGateDryRunRequest patchRequest() {
            return new OperatorGateDryRunRequest("op-1", 1, "patchDescriptor", "http",
                    "patch:/descriptorstore/descriptors/{id}");
        }

        @Test
        @DisplayName("a write under the operator's gate classifies as gated, naming the pattern")
        void writeIsGated() throws Exception {
            doReturn(operatorLikeAgent()).when(agentStore).read("op-1", 1);

            var result = rest.gateDryRun(patchRequest());

            assertTrue(result.policyPresent());
            assertTrue(result.gated(), "the canary's own target write must classify as gated");
            assertEquals("http.patch:*", result.matchedPattern());
        }

        @Test
        @DisplayName("an exempt read classifies as allowed")
        void exemptReadIsAllowed() throws Exception {
            doReturn(operatorLikeAgent()).when(agentStore).read("op-1", 1);

            var result = rest.gateDryRun(new OperatorGateDryRunRequest("op-1", 1, "readAgent", "http",
                    "get:/agentstore/agents/{id}"));

            assertTrue(result.policyPresent());
            assertFalse(result.gated());
            assertNull(result.matchedPattern());
        }

        /**
         * Unlike the operator-shaped case above (whose require list can never match a
         * GET anyway), this one is allowed ONLY because exemption wins over a matching
         * require — deleting the exempt consultation turns it red.
         */
        @Test
        @DisplayName("exemption beats a matching require pattern at this boundary")
        void exemptBeatsRequireHere() throws Exception {
            var approvals = new ToolApprovalsConfig();
            approvals.setRequireApproval(List.of("*"));
            approvals.setExempt(List.of("http.get:*"));
            var hitl = new AgentConfiguration.HitlConfig();
            hitl.setToolApprovals(approvals);
            var config = new AgentConfiguration();
            config.setHitlConfig(hitl);
            doReturn(config).when(agentStore).read("op-1", 1);

            var result = rest.gateDryRun(new OperatorGateDryRunRequest("op-1", 1, "readAgent", "http",
                    "get:/agentstore/agents/{id}"));

            assertFalse(result.gated(), "an exempt read must stay allowed even under require [\"*\"]");
        }

        /**
         * Same normalization as discovery ({@code generateSlug} lower-cases): an
         * upper-case method in the request must not silently classify as ungated.
         */
        @Test
        @DisplayName("an upper-case method classifies the same as lower-case")
        void methodCaseIsNormalized() throws Exception {
            doReturn(operatorLikeAgent()).when(agentStore).read("op-1", 1);

            var result = rest.gateDryRun(new OperatorGateDryRunRequest("op-1", 1, "patchDescriptor", "http",
                    "PATCH:/descriptorstore/descriptors/{id}"));

            assertTrue(result.gated());
        }

        /**
         * Discovery lower-cases ONLY the method when recording an endpoint — the path
         * keeps its case. A dry-run that lower-cased the whole string classified
         * differently from the runtime gate for any spec with an upper-case path
         * segment: deterministic, and deterministically wrong.
         */
        @Test
        @DisplayName("path case is preserved — only the method is normalized")
        void pathCaseIsPreserved() throws Exception {
            var approvals = new ToolApprovalsConfig();
            approvals.setRequireApproval(List.of("http.patch:/CaseSensitive/*"));
            var hitl = new AgentConfiguration.HitlConfig();
            hitl.setToolApprovals(approvals);
            var config = new AgentConfiguration();
            config.setHitlConfig(hitl);
            doReturn(config).when(agentStore).read("op-1", 1);

            var result = rest.gateDryRun(new OperatorGateDryRunRequest("op-1", 1, "patchThing", "http",
                    "PATCH:/CaseSensitive/{id}"));

            assertTrue(result.gated(), "lower-casing the path would break the match against a case-preserving pattern");
        }

        @Test
        @DisplayName("an mcp-source call classifies against mcp patterns")
        void mcpSourceClassifies() throws Exception {
            var approvals = new ToolApprovalsConfig();
            approvals.setRequireApproval(List.of("mcp:*"));
            var hitl = new AgentConfiguration.HitlConfig();
            hitl.setToolApprovals(approvals);
            var config = new AgentConfiguration();
            config.setHitlConfig(hitl);
            doReturn(config).when(agentStore).read("op-1", 1);

            var result = rest.gateDryRun(new OperatorGateDryRunRequest("op-1", 1, "createAgent", "mcp", null));

            assertTrue(result.gated());
            assertEquals("mcp:*", result.matchedPattern());
        }

        @Test
        @DisplayName("a bare tool-name pattern matches without any endpoint")
        void bareToolNamePatternMatches() throws Exception {
            var approvals = new ToolApprovalsConfig();
            approvals.setRequireApproval(List.of("delete_*"));
            var hitl = new AgentConfiguration.HitlConfig();
            hitl.setToolApprovals(approvals);
            var config = new AgentConfiguration();
            config.setHitlConfig(hitl);
            doReturn(config).when(agentStore).read("op-1", 1);

            var result = rest.gateDryRun(new OperatorGateDryRunRequest("op-1", 1, "delete_agent", "http", null));

            assertTrue(result.gated());
        }

        @Test
        @DisplayName("a null source defaults to http; an upper-case source is normalized")
        void sourceDefaultsAndNormalizes() throws Exception {
            doReturn(operatorLikeAgent()).when(agentStore).read("op-1", 1);

            assertTrue(rest.gateDryRun(new OperatorGateDryRunRequest("op-1", 1, "patchDescriptor", null,
                    "patch:/descriptorstore/descriptors/{id}")).gated());
            assertTrue(rest.gateDryRun(new OperatorGateDryRunRequest("op-1", 1, "patchDescriptor", "HTTP",
                    "patch:/descriptorstore/descriptors/{id}")).gated());
        }

        /**
         * An unknown source can never match a source-qualified pattern, so it would
         * classify as ungated with full confidence — reject it instead.
         */
        @Test
        @DisplayName("an unknown source is a 400, never a confident ungated answer")
        void unknownSourceIsRejected() throws Exception {
            doReturn(operatorLikeAgent()).when(agentStore).read("op-1", 1);

            assertThrows(BadRequestException.class,
                    () -> rest.gateDryRun(new OperatorGateDryRunRequest("op-1", 1, "patchDescriptor", "htpp",
                            "patch:/descriptorstore/descriptors/{id}")));
        }

        @Test
        @DisplayName("no policy → policyPresent false and gated false, never a guess")
        void noPolicyIsExplicit() throws Exception {
            doReturn(new AgentConfiguration()).when(agentStore).read("op-1", 1);

            var result = rest.gateDryRun(patchRequest());

            assertFalse(result.policyPresent());
            assertFalse(result.gated());
        }

        @Test
        @DisplayName("an absent agent document is 404, not an inert-gate answer")
        void missingAgentIs404() throws Exception {
            doThrow(new IResourceStore.ResourceNotFoundException("gone")).when(agentStore).read("op-1", 1);

            assertThrows(NotFoundException.class, () -> rest.gateDryRun(patchRequest()));
        }

        /**
         * A store failure must not read as "no policy": the caller uses this answer to
         * decide whether a write-capable agent is safely gated, and "could not read the
         * policy" reported as "there is no policy" is the exact fail-open the HITL
         * carrier fix closed on the conversation path.
         */
        @Test
        @DisplayName("a store error is a 500, never policyPresent=false")
        void storeErrorFailsLoudly() throws Exception {
            doThrow(new IResourceStore.ResourceStoreException("db down")).when(agentStore).read("op-1", 1);

            assertThrows(InternalServerErrorException.class, () -> rest.gateDryRun(patchRequest()));
        }

        @Test
        @DisplayName("validation rejects missing body, id, version and toolName")
        void validation() {
            assertThrows(BadRequestException.class, () -> rest.gateDryRun(null));
            assertThrows(BadRequestException.class,
                    () -> rest.gateDryRun(new OperatorGateDryRunRequest(" ", 1, "t", null, null)));
            assertThrows(BadRequestException.class,
                    () -> rest.gateDryRun(new OperatorGateDryRunRequest("op-1", null, "t", null, null)));
            assertThrows(BadRequestException.class,
                    () -> rest.gateDryRun(new OperatorGateDryRunRequest("op-1", 0, "t", null, null)));
            assertThrows(BadRequestException.class,
                    () -> rest.gateDryRun(new OperatorGateDryRunRequest("op-1", 1, "", null, null)));
        }
    }
}
