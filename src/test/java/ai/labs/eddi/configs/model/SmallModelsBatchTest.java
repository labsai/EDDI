/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.model;

import ai.labs.eddi.configs.apicalls.model.HttpCodeValidator;
import ai.labs.eddi.configs.apicalls.model.HttpPreRequest;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.configs.propertysetter.model.PropertySetterConfiguration;
import ai.labs.eddi.engine.memory.DataFactory;
import ai.labs.eddi.engine.memory.model.ConversationStatus;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Deployment;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Batch test for small model classes with 0% coverage.
 */
class SmallModelsBatchTest {

    // --- DeploymentInfo ---

    @Nested
    class DeploymentInfoTest {

        @Test
        void defaults() {
            var di = new DeploymentInfo();
            assertNull(di.getAgentId());
            assertNull(di.getAgentVersion());
            assertNull(di.getEnvironment());
            assertNull(di.getDeploymentStatus());
        }

        @Test
        void setters() {
            var di = new DeploymentInfo();
            di.setAgentId("agent-1");
            di.setAgentVersion(3);
            di.setEnvironment(Deployment.Environment.production);
            di.setDeploymentStatus(DeploymentInfo.DeploymentStatus.deployed);
            assertEquals("agent-1", di.getAgentId());
            assertEquals(3, di.getAgentVersion());
            assertEquals(Deployment.Environment.production, di.getEnvironment());
            assertEquals(DeploymentInfo.DeploymentStatus.deployed, di.getDeploymentStatus());
        }

        @Test
        void deploymentStatusEnum() {
            assertEquals(2, DeploymentInfo.DeploymentStatus.values().length);
            assertNotNull(DeploymentInfo.DeploymentStatus.valueOf("deployed"));
            assertNotNull(DeploymentInfo.DeploymentStatus.valueOf("undeployed"));
        }
    }

    // --- ConversationStatus ---

    @Nested
    class ConversationStatusTest {

        @Test
        void defaults() {
            var cs = new ConversationStatus();
            assertNull(cs.getConversationId());
            assertNull(cs.getAgentId());
            assertNull(cs.getAgentVersion());
            assertNull(cs.getConversationState());
            assertNull(cs.getLastInteraction());
        }

        @Test
        void setters() {
            var cs = new ConversationStatus();
            cs.setConversationId("conv-1");
            cs.setAgentId("agent-1");
            cs.setAgentVersion(2);
            cs.setConversationState(ConversationState.READY);
            var now = new Date();
            cs.setLastInteraction(now);
            assertEquals("conv-1", cs.getConversationId());
            assertEquals("agent-1", cs.getAgentId());
            assertEquals(2, cs.getAgentVersion());
            assertEquals(ConversationState.READY, cs.getConversationState());
            assertEquals(now, cs.getLastInteraction());
        }
    }

    // --- DataFactory ---

    @Nested
    class DataFactoryTest {

        @Test
        void createData_keyAndValue() {
            var factory = new DataFactory();
            var data = factory.createData("input", "hello");
            assertEquals("input", data.getKey());
            assertEquals("hello", data.getResult());
        }

        @Test
        void createData_public() {
            var factory = new DataFactory();
            var data = factory.createData("output", "response", true);
            assertEquals("output", data.getKey());
            assertTrue(data.isPublic());
        }

        @Test
        void createData_private() {
            var factory = new DataFactory();
            var data = factory.createData("internal", "value", false);
            assertFalse(data.isPublic());
        }

        @Test
        void createData_withPossibleValues() {
            var factory = new DataFactory();
            var data = factory.createData("choice", "a", List.of("a", "b", "c"));
            assertEquals("choice", data.getKey());
            assertEquals("a", data.getResult());
            assertEquals(3, data.getPossibleResults().size());
        }
    }

    // --- HttpPreRequest ---

    @Nested
    class HttpPreRequestTest {

        @Test
        void defaults() {
            var hpr = new HttpPreRequest();
            assertNull(hpr.getBatchRequests());
            assertEquals(0, hpr.getDelayBeforeExecutingInMillis());
        }

        @Test
        void setters() {
            var hpr = new HttpPreRequest();
            hpr.setDelayBeforeExecutingInMillis(500);
            assertEquals(500, hpr.getDelayBeforeExecutingInMillis());
        }
    }

    // --- HttpCodeValidator ---

    @Nested
    class HttpCodeValidatorTest {

        @Test
        void defaultConstant() {
            assertNotNull(HttpCodeValidator.DEFAULT);
            assertEquals(List.of(200, 201), HttpCodeValidator.DEFAULT.getRunOnHttpCode());
            assertTrue(HttpCodeValidator.DEFAULT.getSkipOnHttpCode().contains(400));
            assertTrue(HttpCodeValidator.DEFAULT.getSkipOnHttpCode().contains(500));
        }

        @Test
        void emptyConstructor() {
            var v = new HttpCodeValidator();
            assertNull(v.getRunOnHttpCode());
            assertNull(v.getSkipOnHttpCode());
        }

        @Test
        void parameterizedConstructor() {
            var v = new HttpCodeValidator(List.of(200), List.of(500));
            assertEquals(List.of(200), v.getRunOnHttpCode());
            assertEquals(List.of(500), v.getSkipOnHttpCode());
        }
    }

    // --- PropertySetterConfiguration ---

    @Nested
    class PropertySetterConfigurationTest {

        @Test
        void defaults() {
            var config = new PropertySetterConfiguration();
            assertNotNull(config.getSetOnActions());
            assertTrue(config.getSetOnActions().isEmpty());
        }
    }

    // --- Deployment.Environment.fromString ---

    @Nested
    class DeploymentEnvironmentFromStringTest {

        @Test
        void null_returnsProduction() {
            assertEquals(Deployment.Environment.production, Deployment.Environment.fromString(null));
        }

        @Test
        void production_returnsProduction() {
            assertEquals(Deployment.Environment.production, Deployment.Environment.fromString("production"));
        }

        @Test
        void test_returnsTest() {
            assertEquals(Deployment.Environment.test, Deployment.Environment.fromString("test"));
        }

        @Test
        void unrestricted_legacyMapping() {
            assertEquals(Deployment.Environment.production, Deployment.Environment.fromString("unrestricted"));
        }

        @Test
        void restricted_legacyMapping() {
            assertEquals(Deployment.Environment.production, Deployment.Environment.fromString("restricted"));
        }

        @Test
        void unknown_defaultsToProduction() {
            assertEquals(Deployment.Environment.production, Deployment.Environment.fromString("whatever"));
        }

        @Test
        void caseInsensitive() {
            assertEquals(Deployment.Environment.production, Deployment.Environment.fromString("PRODUCTION"));
            assertEquals(Deployment.Environment.test, Deployment.Environment.fromString("TEST"));
        }

        @Test
        void toValue() {
            assertEquals("production", Deployment.Environment.production.toValue());
            assertEquals("test", Deployment.Environment.test.toValue());
        }
    }

    // --- Deployment.Status ---

    @Nested
    class DeploymentStatusTest {

        @Test
        void allValues() {
            assertEquals(4, Deployment.Status.values().length);
            assertNotNull(Deployment.Status.valueOf("READY"));
            assertNotNull(Deployment.Status.valueOf("IN_PROGRESS"));
            assertNotNull(Deployment.Status.valueOf("NOT_FOUND"));
            assertNotNull(Deployment.Status.valueOf("ERROR"));
        }
    }
}
