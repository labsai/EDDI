/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents.rest;

import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.agents.crypto.AgentPublicKey;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

class RestAgentStoreTest {

    // Realistic IDs (extractResourceId requires 18+ hex chars)
    private static final String AGENT_ID = "aabbccddee1122334455";
    private static final String PKG1_ID = "ff00112233445566aa77";
    private static final String PKG2_ID = "bb99887766554433cc22";

    @Mock
    private IAgentStore AgentStore;
    @Mock
    private IRestWorkflowStore restWorkflowStore;
    @Mock
    private IDocumentDescriptorStore documentDescriptorStore;
    @Mock
    private IJsonSchemaCreator jsonSchemaCreator;
    @Mock
    private IScheduleStore scheduleStore;
    @Mock
    private CapabilityRegistryService capabilityRegistryService;
    @Mock
    private IDeploymentStore deploymentStore;
    @Mock
    private AgentSigningService agentSigningService;

    private RestAgentStore restAgentStore;

    @BeforeEach
    void setUp() throws Exception {
        openMocks(this);
        restAgentStore = new RestAgentStore(AgentStore, restWorkflowStore, documentDescriptorStore, jsonSchemaCreator, scheduleStore,
                capabilityRegistryService, deploymentStore, mock(ResourceAccessGuard.class), agentSigningService, "default");
        // The Agent is live at v1: a cascade is only allowed against the CURRENT
        // version, since it tears down workflows and schedules before the delete —
        // the only place the version used to be checked — has run.
        when(AgentStore.getCurrentResourceId(AGENT_ID)).thenReturn(resourceId(AGENT_ID, 1));
    }

    static IResourceStore.IResourceId resourceId(String id, int version) {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Integer getVersion() {
                return version;
            }
        };
    }

    /** Helper to create a dummy DocumentDescriptor for reference-count mocking */
    private DocumentDescriptor dummyDescriptor() {
        return new DocumentDescriptor();
    }

    @Nested
    @DisplayName("deleteAgent")
    class DeleteAgentTests {

        @Test
        @DisplayName("should delete Agent without cascade when cascade=false")
        void deleteAgent_noCascade() throws Exception {
            restAgentStore.deleteAgent(AGENT_ID, 1, false, false);

            verify(restWorkflowStore, never()).deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean());
            verify(AgentStore).delete(eq(AGENT_ID), eq(1));
        }

        @Test
        @DisplayName("should delete the Agent's deployment records even without cascade")
        void deleteAgent_deletesDeploymentRecords() throws Exception {
            restAgentStore.deleteAgent(AGENT_ID, 1, false, false);

            // A surviving record makes the runtime retry a doomed redeploy
            // of a now-missing Agent on every startup.
            verify(deploymentStore).deleteDeploymentInfos(AGENT_ID);
        }

        @Test
        @DisplayName("should keep deployment records when the Agent delete itself fails")
        void deleteAgent_keepsDeploymentRecordsWhenDeleteFails() throws Exception {
            // A stale/unknown version is rejected inside restVersionInfo.delete.
            // Clearing first would strip a still-live Agent of what it needs.
            doThrow(new IResourceStore.ResourceModifiedException("not the latest version")).when(AgentStore).delete(AGENT_ID, 1);

            assertThrows(IResourceStore.ResourceModifiedException.class, () -> restAgentStore.deleteAgent(AGENT_ID, 1, false, false));

            verify(deploymentStore, never()).deleteDeploymentInfos(any());
        }

        @Test
        @DisplayName("should still delete the Agent when clearing its deployment records fails")
        void deleteAgent_deploymentCleanupFailureIsNotFatal() throws Exception {
            when(deploymentStore.deleteDeploymentInfos(AGENT_ID))
                    .thenThrow(new IResourceStore.ResourceStoreException("boom", new RuntimeException()));

            restAgentStore.deleteAgent(AGENT_ID, 1, false, false);

            verify(AgentStore).delete(eq(AGENT_ID), eq(1));
        }

        @Test
        @DisplayName("should cascade-delete packages when cascade=true and packages are not shared")
        void deleteAgent_cascade() throws Exception {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG1_ID + "?version=2"),
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG2_ID + "?version=1"))));
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(config);
            // Each package is only referenced by this one agent
            when(AgentStore.getAgentDescriptorsContainingWorkflow(PKG1_ID, 2, false)).thenReturn(List.of(dummyDescriptor()));
            when(AgentStore.getAgentDescriptorsContainingWorkflow(PKG2_ID, 1, false)).thenReturn(List.of(dummyDescriptor()));
            when(restWorkflowStore.deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean())).thenReturn(Response.ok().build());

            restAgentStore.deleteAgent(AGENT_ID, 1, true, true);

            // permanent=false on the cascaded workflows even though the request said
            // permanent=true. These two assertions used to read `true` and so pinned the
            // defect: the reference guard above asks a VERSION-scoped question
            // ("who references W?version=2?") while a permanent delete is ID-scoped and
            // drops every version plus all history. The Agent itself is still erased
            // permanently — that is what the caller asked for and owns.
            verify(restWorkflowStore).deleteWorkflow(PKG1_ID, 2, false, true);
            verify(restWorkflowStore).deleteWorkflow(PKG2_ID, 1, false, true);
            verify(AgentStore).deleteAllPermanently(AGENT_ID);
        }

        @Test
        @DisplayName("should delete deployment records on the cascade path too")
        void deleteAgent_cascade_alsoDeletesDeploymentRecords() throws Exception {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG1_ID + "?version=1"))));
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(config);
            when(AgentStore.getAgentDescriptorsContainingWorkflow(PKG1_ID, 1, false)).thenReturn(List.of(dummyDescriptor()));
            when(restWorkflowStore.deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean())).thenReturn(Response.ok().build());

            restAgentStore.deleteAgent(AGENT_ID, 1, true, true);

            verify(deploymentStore).deleteDeploymentInfos(AGENT_ID);
        }

        @Test
        @DisplayName("should skip cascade-delete of packages shared with other agents")
        void deleteAgent_cascade_skipsSharedWorkflows() throws Exception {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG1_ID + "?version=2"),
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG2_ID + "?version=1"))));
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(config);
            // PKG1 is shared with 2 agents — should be SKIPPED
            when(AgentStore.getAgentDescriptorsContainingWorkflow(PKG1_ID, 2, false)).thenReturn(List.of(dummyDescriptor(), dummyDescriptor()));
            // PKG2 is only in this Agent — should be deleted
            when(AgentStore.getAgentDescriptorsContainingWorkflow(PKG2_ID, 1, false)).thenReturn(List.of(dummyDescriptor()));
            when(restWorkflowStore.deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean())).thenReturn(Response.ok().build());

            restAgentStore.deleteAgent(AGENT_ID, 1, true, true);

            // Only PKG2 should be deleted — PKG1 is shared
            verify(restWorkflowStore, never()).deleteWorkflow(eq(PKG1_ID), anyInt(), anyBoolean(), anyBoolean());
            verify(restWorkflowStore).deleteWorkflow(PKG2_ID, 1, false, true);
            verify(AgentStore).deleteAllPermanently(AGENT_ID);
        }

        @Test
        @DisplayName("should continue deleting Agent even when package cascade fails")
        void deleteAgent_cascade_partialFailure() throws Exception {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG1_ID + "?version=1"),
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG2_ID + "?version=1"))));
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(config);
            // both packages only referenced by this agent
            when(AgentStore.getAgentDescriptorsContainingWorkflow(anyString(), anyInt(), eq(false))).thenReturn(List.of(dummyDescriptor()));
            when(restWorkflowStore.deleteWorkflow(PKG1_ID, 1, false, true)).thenThrow(new RuntimeException("Workflow in use"));
            when(restWorkflowStore.deleteWorkflow(PKG2_ID, 1, false, true)).thenReturn(Response.ok().build());

            assertDoesNotThrow(() -> restAgentStore.deleteAgent(AGENT_ID, 1, true, true));

            verify(restWorkflowStore).deleteWorkflow(PKG1_ID, 1, false, true);
            verify(restWorkflowStore).deleteWorkflow(PKG2_ID, 1, false, true);
            verify(AgentStore).deleteAllPermanently(AGENT_ID);
        }

        @Test
        @DisplayName("should still delete Agent when Agent config not found for cascade")
        void deleteAgent_cascade_agentNotFound() throws Exception {
            when(AgentStore.read(AGENT_ID, 1)).thenThrow(new IResourceStore.ResourceNotFoundException("not found"));

            assertDoesNotThrow(() -> restAgentStore.deleteAgent(AGENT_ID, 1, true, true));

            verify(restWorkflowStore, never()).deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean());
            verify(AgentStore).deleteAllPermanently(AGENT_ID);
        }

        @Test
        @DisplayName("should handle empty packages list in cascade")
        void deleteAgent_cascade_emptyWorkflows() throws Exception {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>());
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(config);

            assertDoesNotThrow(() -> restAgentStore.deleteAgent(AGENT_ID, 1, true, true));

            verify(restWorkflowStore, never()).deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean());
            verify(AgentStore).deleteAllPermanently(AGENT_ID);
        }
    }

    @Nested
    @DisplayName("deleteAgent — cascade version guard")
    class CascadeVersionGuard {

        /**
         * The cascade used to run against whatever version the request named, because
         * {@code agentStore.read} falls back to history for a superseded version and
         * the only version check lived in {@code restVersionInfo.delete()} — at the
         * very end. A stale tab deleting v1 of an Agent that is at v2 therefore
         * destroyed v1's workflows and schedules and only then answered 409.
         */
        @Test
        @DisplayName("refuses a stale version with 409 before touching workflows or schedules")
        void staleVersionRefusedBeforeAnyDeletion() throws Exception {
            when(AgentStore.getCurrentResourceId(AGENT_ID)).thenReturn(resourceId(AGENT_ID, 2));

            var thrown = assertThrows(WebApplicationException.class,
                    () -> restAgentStore.deleteAgent(AGENT_ID, 1, false, true));

            assertEquals(Response.Status.CONFLICT.getStatusCode(), thrown.getResponse().getStatus());
            verify(scheduleStore, never()).deleteSchedulesByAgentId(anyString());
            verify(restWorkflowStore, never()).deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean());
            verify(AgentStore, never()).delete(anyString(), anyInt());
            verify(AgentStore, never()).deleteAllPermanently(anyString());
        }

        /**
         * {@code ?version=0} is the documented "current version" shorthand. It used to
         * be resolved only inside {@code restVersionInfo.delete()}, so the cascade read
         * version 0, found nothing, and skipped itself with a WARN while the delete
         * went through — {@code cascade=true} silently did not cascade.
         */
        @Test
        @DisplayName("version=0 resolves to the current version and does cascade")
        void versionZeroCascades() throws Exception {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG1_ID + "?version=1"))));
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(config);
            when(AgentStore.getAgentDescriptorsContainingWorkflow(PKG1_ID, 1, false)).thenReturn(List.of(dummyDescriptor()));
            when(restWorkflowStore.deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean())).thenReturn(Response.ok().build());

            restAgentStore.deleteAgent(AGENT_ID, 0, false, true);

            verify(restWorkflowStore).deleteWorkflow(PKG1_ID, 1, false, true);
            verify(AgentStore).delete(AGENT_ID, 1);
        }

        /**
         * The ordinary "purge what I already soft-deleted" flow:
         * {@code ?permanent=true&cascade=true} against an Agent with no current row.
         * {@code getCurrentResourceId} throws for it, and the documented contract is to
         * skip the cascade and still purge the history — so the guard has to answer
         * false rather than propagate, and must not dereference the missing current id,
         * which on this path would NPE partway through a destructive operation.
         */
        @Test
        @DisplayName("an already soft-deleted Agent skips the cascade and still purges")
        void softDeletedAgentSkipsCascadeButStillPurges() throws Exception {
            when(AgentStore.getCurrentResourceId(AGENT_ID))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("no current version"));

            assertDoesNotThrow(() -> restAgentStore.deleteAgent(AGENT_ID, 1, true, true));

            verify(scheduleStore, never()).deleteSchedulesByAgentId(anyString());
            verify(restWorkflowStore, never()).deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean());
            verify(AgentStore).deleteAllPermanently(AGENT_ID);
        }

        /**
         * The capability index feeds {@code capabilityMatch} behaviour rules and A2A
         * discovery. Clearing it before the delete stripped a still-live Agent of what
         * it routes on when the delete then answered 409.
         */
        @Test
        @DisplayName("keeps the capability registration when the delete itself fails")
        void capabilityRegistrationSurvivesAFailedDelete() throws Exception {
            doThrow(new IResourceStore.ResourceModifiedException("not the latest version")).when(AgentStore).delete(AGENT_ID, 1);

            assertThrows(IResourceStore.ResourceModifiedException.class, () -> restAgentStore.deleteAgent(AGENT_ID, 1, false, false));

            verify(capabilityRegistryService, never()).unregister(anyString());
        }

        @Test
        @DisplayName("clears the capability registration once the delete succeeded")
        void capabilityRegistrationClearedAfterDelete() throws Exception {
            restAgentStore.deleteAgent(AGENT_ID, 1, false, false);

            verify(capabilityRegistryService).unregister(AGENT_ID);
        }

        /**
         * Nothing else in the codebase removes an agent's private key, and the vault
         * entry outlives the config that documented what it was for. This assertion
         * used to pass {@code permanent=false} — see
         * {@link #keepsSigningKeyPairOnASoftDelete()} for why that was wrong.
         */
        @Test
        @DisplayName("removes the signing key from the vault when the Agent is permanently deleted")
        void deletesSigningKeyPair() throws Exception {
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(agentWithSigningIdentity());

            restAgentStore.deleteAgent(AGENT_ID, 1, true, false);

            verify(agentSigningService).deleteKeyPair("default", AGENT_ID);
        }

        /**
         * A soft delete is the deliberately recoverable path, and the Ed25519 private
         * key is the one thing about an Agent that cannot be recreated: no endpoint
         * generates one (see {@code validateSecurityFlags}), so an Agent restored from
         * a ZIP backup would come back with a public key whose private half is gone and
         * {@code signInterAgentMessages} permanently broken. The vault cleanup was
         * briefly unconditional, which made every recoverable delete irreversible for
         * key material.
         */
        @Test
        @DisplayName("keeps the signing key in the vault on a soft delete")
        void keepsSigningKeyPairOnASoftDelete() throws Exception {
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(agentWithSigningIdentity());

            restAgentStore.deleteAgent(AGENT_ID, 1, false, false);

            verify(AgentStore).delete(AGENT_ID, 1);
            verify(agentSigningService, never()).deleteKeyPair(anyString(), anyString());
        }

        @Test
        @DisplayName("does not probe the vault for an Agent with no key material")
        void skipsVaultForAgentWithoutIdentity() throws Exception {
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(new AgentConfiguration());

            restAgentStore.deleteAgent(AGENT_ID, 1, true, false);

            verify(agentSigningService, never()).deleteKeyPair(anyString(), anyString());
        }

        /**
         * The key-cleanup probe is best-effort by design: an Agent that cannot be read
         * reports "no key material" rather than failing the delete. A leaked vault
         * entry is recoverable; a config that cannot be deleted is not — so the probe
         * must never decide whether the delete happens.
         */
        @Test
        @DisplayName("an unreadable Agent is still deleted, with the vault probe answering no")
        void unreadableAgentDoesNotBlockThePermanentDelete() throws Exception {
            when(AgentStore.read(AGENT_ID, 1)).thenThrow(new IResourceStore.ResourceStoreException("mongo down"));

            assertDoesNotThrow(() -> restAgentStore.deleteAgent(AGENT_ID, 1, true, false));

            verify(agentSigningService, never()).deleteKeyPair(anyString(), anyString());
            verify(AgentStore).deleteAllPermanently(AGENT_ID);
        }

        private AgentConfiguration agentWithSigningIdentity() {
            var config = new AgentConfiguration();
            var identity = new AgentConfiguration.AgentIdentity();
            identity.setPublicKey("MCowBQYDK2VwAyEA-not-a-real-key");
            config.setIdentity(identity);
            return config;
        }
    }

    // --- Wave 3: Security flag validation ---

    @Nested
    @DisplayName("rejectInertSecurityFlags")
    class SecurityFlagValidationTests {

        @Test
        @DisplayName("createAgent should reject signInterAgentMessages=true with HTTP 400")
        void createAgent_rejectsSignInterAgentMessages() {
            var config = new AgentConfiguration();
            var security = new AgentConfiguration.SecurityConfig();
            security.setSignInterAgentMessages(true);
            config.setSecurity(security);

            assertThrows(BadRequestException.class,
                    () -> restAgentStore.createAgent(config));
        }

        @Test
        @DisplayName("createAgent should reject requirePeerVerification=true with HTTP 400")
        void createAgent_rejectsRequirePeerVerification() {
            var config = new AgentConfiguration();
            var security = new AgentConfiguration.SecurityConfig();
            security.setRequirePeerVerification(true);
            config.setSecurity(security);

            assertThrows(BadRequestException.class,
                    () -> restAgentStore.createAgent(config));
        }

        @Test
        @DisplayName("createAgent should allow null security block")
        void createAgent_allowsNullSecurity() throws Exception {
            var config = new AgentConfiguration();
            config.setSecurity(null);
            config.setWorkflows(new ArrayList<>());

            when(AgentStore.create(any())).thenReturn(new IResourceStore.IResourceId() {
                @Override
                public String getId() {
                    return AGENT_ID;
                }
                @Override
                public Integer getVersion() {
                    return 1;
                }
            });

            // Validation passes — no BadRequestException thrown
            assertDoesNotThrow(() -> restAgentStore.createAgent(config));
        }

        @Test
        @DisplayName("createAgent should allow security block with all flags false")
        void createAgent_allowsAllFlagsFalse() throws Exception {
            var config = new AgentConfiguration();
            var security = new AgentConfiguration.SecurityConfig();
            // all default to false
            config.setSecurity(security);
            config.setWorkflows(new ArrayList<>());

            when(AgentStore.create(any())).thenReturn(new IResourceStore.IResourceId() {
                @Override
                public String getId() {
                    return AGENT_ID;
                }
                @Override
                public Integer getVersion() {
                    return 1;
                }
            });

            // Validation passes — no BadRequestException thrown
            assertDoesNotThrow(() -> restAgentStore.createAgent(config));
        }

        @Test
        @DisplayName("updateAgent should reject signInterAgentMessages=true")
        void updateAgent_rejectsSecurityFlags() {
            var config = new AgentConfiguration();
            var security = new AgentConfiguration.SecurityConfig();
            security.setSignInterAgentMessages(true);
            config.setSecurity(security);

            assertThrows(BadRequestException.class,
                    () -> restAgentStore.updateAgent(AGENT_ID, 1, config));
        }

        @Test
        @DisplayName("duplicateAgent should reject security flags from source config when no keys")
        void duplicateAgent_rejectsSecurityFlags() throws Exception {
            var sourceConfig = new AgentConfiguration();
            var security = new AgentConfiguration.SecurityConfig();
            security.setSignInterAgentMessages(true);
            // No identity/keys — should fail validation
            sourceConfig.setSecurity(security);
            sourceConfig.setWorkflows(new ArrayList<>());

            when(AgentStore.read(AGENT_ID, 1)).thenReturn(sourceConfig);

            assertThrows(BadRequestException.class,
                    () -> restAgentStore.duplicateAgent(AGENT_ID, 1, false));
        }

        @Test
        @DisplayName("createAgent should accept crypto flags when rotated keys exist")
        void createAgent_acceptsCryptoWithRotatedKeys() throws Exception {
            var config = new AgentConfiguration();
            var security = new AgentConfiguration.SecurityConfig();
            security.setSignInterAgentMessages(true);
            config.setSecurity(security);
            config.setWorkflows(new ArrayList<>());

            var identity = new AgentConfiguration.AgentIdentity();
            // No legacy publicKey, but rotated keys list is populated
            identity.setKeys(List.of(
                    AgentPublicKey.createCurrent(1, "base64key==")));
            config.setIdentity(identity);

            when(AgentStore.create(any())).thenReturn(new IResourceStore.IResourceId() {
                @Override
                public String getId() {
                    return AGENT_ID;
                }
                @Override
                public Integer getVersion() {
                    return 1;
                }
            });

            assertDoesNotThrow(() -> restAgentStore.createAgent(config));
        }

        @Test
        @DisplayName("createAgent should accept crypto flags when legacy publicKey exists")
        void createAgent_acceptsCryptoWithLegacyKey() throws Exception {
            var config = new AgentConfiguration();
            var security = new AgentConfiguration.SecurityConfig();
            security.setSignInterAgentMessages(true);
            config.setSecurity(security);
            config.setWorkflows(new ArrayList<>());

            var identity = new AgentConfiguration.AgentIdentity();
            identity.setPublicKey("legacyBase64Key==");
            config.setIdentity(identity);

            when(AgentStore.create(any())).thenReturn(new IResourceStore.IResourceId() {
                @Override
                public String getId() {
                    return AGENT_ID;
                }
                @Override
                public Integer getVersion() {
                    return 1;
                }
            });

            assertDoesNotThrow(() -> restAgentStore.createAgent(config));
        }
    }
}
