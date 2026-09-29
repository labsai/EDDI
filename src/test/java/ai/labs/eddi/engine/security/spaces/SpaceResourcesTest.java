/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.configs.variables.IGlobalVariableStore;
import ai.labs.eddi.configs.variables.model.GlobalVariable;
import ai.labs.eddi.configs.variables.rest.RestGlobalVariableStore;
import ai.labs.eddi.engine.security.spaces.rest.IRestSpaceResources;
import ai.labs.eddi.engine.security.spaces.rest.RestSpaceResources;
import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.VaultGrantChecker;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import jakarta.ws.rs.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Each space has its own vault and variable tenant. What must hold: only
 * members reach it, two spaces never share one, and an agent cannot be deployed
 * against a space its deployer is not in.
 */
@DisplayName("space secrets and variables")
class SpaceResourcesTest {

    private static final String ENGINEERING = Subjects.teamSpace("engineering");
    private static final String FINANCE = Subjects.teamSpace("finance");

    private ResourceAccessGuard guard;

    @BeforeEach
    void setUp() {
        guard = mock(ResourceAccessGuard.class);
        when(guard.callerSpaces()).thenReturn(CallerSpaces.of("alice", Set.of("/engineering")));
        when(guard.currentPrincipal()).thenReturn("alice");
        when(guard.settings()).thenReturn(new WorkspaceSettings(true, true, "groups", WorkspaceSettings.LEGACY_SHARED, Optional.empty()));
    }

    @Nested
    @DisplayName("tenant ids")
    class Tenants {

        @Test
        @DisplayName("are readable, valid for both stores, and recognisable")
        void readableAndValid() {
            String tenant = SpaceTenants.tenantFor(ENGINEERING);

            assertTrue(tenant.startsWith("t.engineering."), tenant);
            assertTrue(tenant.matches("[a-zA-Z0-9._-]{1,128}"), "the vault refuses anything else: " + tenant);
            assertTrue(SpaceTenants.isSpaceTenant(tenant));
            assertFalse(SpaceTenants.isSpaceTenant("default"));
            assertTrue(SpaceTenants.tenantFor(Subjects.personalSpace("alice@example.com")).startsWith("u.alice_example_com."));
        }

        @Test
        @DisplayName("never coincide for two spaces whose names slug the same")
        void unique() {
            // Both slug to "eng_backend"; only the hash tells them apart, and it must.
            assertNotEquals(SpaceTenants.tenantFor(Subjects.teamSpace("eng/backend")), SpaceTenants.tenantFor(Subjects.teamSpace("eng.backend")));
            assertNotEquals(SpaceTenants.tenantFor(Subjects.teamSpace("alice")), SpaceTenants.tenantFor(Subjects.personalSpace("alice")));
        }

        @Test
        @DisplayName("are stable")
        void stable() {
            assertEquals(SpaceTenants.tenantFor(ENGINEERING), SpaceTenants.tenantFor(ENGINEERING));
        }
    }

    @Nested
    @DisplayName("the /spacestore endpoints")
    class Endpoints {

        private ISecretProvider vault;
        private IGlobalVariableStore variables;
        private RestSpaceResources sut;

        @BeforeEach
        void setUp() {
            vault = mock(ISecretProvider.class);
            when(vault.isAvailable()).thenReturn(true);
            variables = mock(IGlobalVariableStore.class);
            sut = new RestSpaceResources(guard, vault, mock(SecretResolver.class), variables, mock(GlobalVariableResolver.class));
        }

        @Test
        @DisplayName("a member stores a secret in the space's own tenant and gets back the reference to use")
        void memberStoresSecret() throws Exception {
            String tenant = SpaceTenants.tenantFor(ENGINEERING);
            var reference = new SecretReference(tenant, "openai");
            when(vault.getMetadata(reference)).thenReturn(new SecretMetadata(tenant, "openai", Instant.EPOCH, null, null, null, "key", List.of("*")));

            var response = sut.storeSecret(ENGINEERING, "openai", new IRestSpaceResources.SecretWrite("sk-test", "key", null));

            verify(vault).store(reference, "sk-test", "key", null);
            var stored = (IRestSpaceResources.SpaceSecret) response.getEntity();
            assertEquals("${vault:" + tenant + "/openai}", stored.reference());
        }

        @Test
        @DisplayName("a non-member reaches nothing — administrators included")
        void nonMemberRefused() throws Exception {
            assertThrows(ForbiddenException.class, () -> sut.listSecrets(FINANCE));
            assertThrows(ForbiddenException.class, () -> sut.storeSecret(FINANCE, "k", new IRestSpaceResources.SecretWrite("v", null, null)));
            assertThrows(ForbiddenException.class, () -> sut.listVariables(FINANCE));
            assertThrows(ForbiddenException.class, () -> sut.storeVariable(FINANCE, "k", new IRestSpaceResources.VariableWrite("v", null)));

            verify(vault, never()).store(any(), anyString(), any(), any());
            verify(variables, never()).upsert(any());
        }

        @Test
        @DisplayName("a space variable is stored non-exportable, in the space's tenant")
        void variableIsSpaceScoped() {
            sut.storeVariable(ENGINEERING, "model", new IRestSpaceResources.VariableWrite("gpt-x", null));

            verify(variables).upsert(new GlobalVariable(SpaceTenants.tenantFor(ENGINEERING), "model", "gpt-x", null, false));
        }

        @Test
        @DisplayName("a member may not point a space variable at a secret — the deploy check would never see it")
        void variableMayNotReferenceASecret() {
            // ${vars:...} resolves before ${vault:...}, and space membership is checked
            // when an agent is deployed. A variable edited afterwards to hold a vault
            // or connection reference would redirect a running agent's credentials.
            for (String value : List.of("${vault:t.finance.00000000/openai}", "${eddivault:openai}", "${connection:crm}")) {
                assertThrows(ForbiddenException.class,
                        () -> sut.storeVariable(ENGINEERING, "key", new IRestSpaceResources.VariableWrite(value, null)), value);
            }
            verify(variables, never()).upsert(any());

            when(guard.isAdmin()).thenReturn(true);
            sut.storeVariable(ENGINEERING, "key", new IRestSpaceResources.VariableWrite("${vault:t.finance.00000000/openai}", null));
            verify(variables).upsert(any());
        }
    }

    @Nested
    @DisplayName("deployment-wide variables under enforcement")
    class GlobalVariables {

        private IGlobalVariableStore store;
        private RestGlobalVariableStore sut;

        @BeforeEach
        void setUp() {
            store = mock(IGlobalVariableStore.class);
            sut = new RestGlobalVariableStore(store, mock(GlobalVariableResolver.class), guard);
        }

        @Test
        @DisplayName("an editor can no longer overwrite what every team reads")
        void editorCannotWriteDefault() {
            assertThrows(ForbiddenException.class, () -> sut.upsertVariable("default", "model", new GlobalVariable("model", "mine")));
            assertThrows(ForbiddenException.class, () -> sut.deleteVariable("default", "model"));
            verify(store, never()).upsert(any());
        }

        @Test
        @DisplayName("but still reads them, and writes their own space's by tenant id")
        void editorReadsDefaultAndWritesOwnSpace() {
            assertDoesNotThrow(() -> sut.listVariables("default"));
            String own = SpaceTenants.tenantFor(ENGINEERING);
            assertDoesNotThrow(() -> sut.upsertVariable(own, "model", new GlobalVariable("model", "ours")));
        }

        @Test
        @DisplayName("another team's tenant is neither readable nor writable here")
        void otherTeamsTenantRefused() {
            String theirs = SpaceTenants.tenantFor(FINANCE);
            assertThrows(ForbiddenException.class, () -> sut.listVariables(theirs));
            assertThrows(ForbiddenException.class, () -> sut.upsertVariable(theirs, "x", new GlobalVariable("x", "y")));
        }

        @Test
        @DisplayName("an administrator is not restricted")
        void adminUnrestricted() {
            when(guard.isAdmin()).thenReturn(true);
            assertDoesNotThrow(() -> sut.upsertVariable("default", "model", new GlobalVariable("model", "v")));
        }

        @Test
        @DisplayName("nothing changes without enforcement")
        void noEnforcementNoChange() {
            when(guard.settings()).thenReturn(new WorkspaceSettings(false, true, "groups", WorkspaceSettings.LEGACY_SHARED, Optional.empty()));
            assertDoesNotThrow(() -> sut.upsertVariable("default", "model", new GlobalVariable("model", "v")));
        }
    }

    @Nested
    @DisplayName("deploying an agent that references a space")
    class Deploying {

        private VaultGrantChecker checker;
        private SpaceReferenceGuard sut;

        @BeforeEach
        void setUp() {
            checker = mock(VaultGrantChecker.class);
            sut = new SpaceReferenceGuard(checker, guard);
        }

        @Test
        @DisplayName("is allowed for a member of every referenced space")
        void memberDeploys() {
            when(checker.referencedTenants("agent", 1)).thenReturn(Set.of(SpaceTenants.tenantFor(ENGINEERING), "default"));

            assertDoesNotThrow(() -> sut.requireMayDeploy("agent", 1));
        }

        @Test
        @DisplayName("is refused when any referenced space is not the deployer's — naming it")
        void nonMemberRefused() {
            String theirs = SpaceTenants.tenantFor(FINANCE);
            when(checker.referencedTenants("agent", 1)).thenReturn(Set.of(SpaceTenants.tenantFor(ENGINEERING), theirs));

            // Quarkus's ForbiddenException, not the JAX-RS one imported above — both
            // map to 403; asserting on RuntimeException keeps the two out of one file.
            var refusal = assertThrows(RuntimeException.class, () -> sut.requireMayDeploy("agent", 1));
            assertTrue(refusal.getClass().getSimpleName().contains("Forbidden"), refusal.getClass().getName());
            assertTrue(refusal.getMessage().contains(theirs));
        }

        @Test
        @DisplayName("is not checked for an administrator, or without enforcement")
        void exemptions() {
            when(checker.referencedTenants("agent", 1)).thenReturn(Set.of(SpaceTenants.tenantFor(FINANCE)));

            when(guard.isAdmin()).thenReturn(true);
            assertDoesNotThrow(() -> sut.requireMayDeploy("agent", 1));

            when(guard.isAdmin()).thenReturn(false);
            when(guard.settings()).thenReturn(new WorkspaceSettings(false, true, "groups", WorkspaceSettings.LEGACY_SHARED, Optional.empty()));
            assertDoesNotThrow(() -> sut.requireMayDeploy("agent", 1));
        }
    }
}
