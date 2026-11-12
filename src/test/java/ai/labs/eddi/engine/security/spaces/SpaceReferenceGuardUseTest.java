/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces;

import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.secrets.VaultGrantChecker;
import io.quarkus.security.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link SpaceReferenceGuard#requireMayUse}: the deployment rule for a
 * configuration run on its own, outside any agent — a knowledge base searched
 * or ingested into through its standalone endpoints.
 */
class SpaceReferenceGuardUseTest {

    private static final String OWN_SPACE = "team:research";
    private static final String FOREIGN_SPACE = "team:finance";

    private VaultGrantChecker checker;
    private ResourceAccessGuard accessGuard;
    private WorkspaceSettings settings;
    private SpaceReferenceGuard guard;

    @BeforeEach
    void setUp() {
        checker = mock(VaultGrantChecker.class);
        accessGuard = mock(ResourceAccessGuard.class);
        settings = mock(WorkspaceSettings.class);
        when(accessGuard.settings()).thenReturn(settings);
        when(settings.isEnforcing()).thenReturn(true);
        when(accessGuard.callerSpaces()).thenReturn(new CallerSpaces(Set.of("alice"), Set.of(OWN_SPACE), Set.of()));
        guard = new SpaceReferenceGuard(checker, accessGuard);
    }

    @Test
    @DisplayName("a configuration naming a space the caller is not a member of is refused")
    void foreignTenantIsRefused() {
        var config = new RagConfiguration();
        when(checker.referencedTenantsOf(any())).thenReturn(Set.of(SpaceTenants.tenantFor(FOREIGN_SPACE)));

        var refused = assertThrows(ForbiddenException.class, () -> guard.requireMayUse(config, "knowledge base"));
        assertTrue(refused.getMessage().contains(SpaceTenants.tenantFor(FOREIGN_SPACE)), refused.getMessage());
    }

    @Test
    @DisplayName("the caller's own space, and tenants that are no space's, pass")
    void ownTenantPasses() {
        when(checker.referencedTenantsOf(any())).thenReturn(Set.of(SpaceTenants.tenantFor(OWN_SPACE), "default"));

        assertDoesNotThrow(() -> guard.requireMayUse(new RagConfiguration(), "knowledge base"));
    }

    @Test
    @DisplayName("a variable or connection that cannot be read fails closed")
    void unverifiableFailsClosed() {
        when(checker.referencedTenantsOf(any())).thenThrow(new VaultGrantChecker.UnverifiableReferencesException("variable x"));

        assertThrows(ForbiddenException.class, () -> guard.requireMayUse(new RagConfiguration(), "knowledge base"));
    }

    @Test
    @DisplayName("space membership is not checked without workspace enforcement; nothing at all for an administrator")
    void noOpWhenNotEnforcedOrAdmin() {
        when(settings.isEnforcing()).thenReturn(false);
        assertDoesNotThrow(() -> guard.requireMayUse(new RagConfiguration(), "knowledge base"));
        verify(checker, never()).referencedTenantsOf(any());

        var adminChecker = mock(VaultGrantChecker.class);
        var asAdmin = new SpaceReferenceGuard(adminChecker, accessGuard);
        when(settings.isEnforcing()).thenReturn(true);
        when(accessGuard.isAdmin()).thenReturn(true);
        assertDoesNotThrow(() -> asAdmin.requireMayUse(new RagConfiguration(), "knowledge base"));
        verifyNoInteractions(adminChecker);
    }

    @Nested
    @DisplayName("agent grants (security review 2026-11, finding 2)")
    class AgentGrants {

        private static final String AGENT_A = "6a1b2c3d4e5f6a7b8c9d0e1f";

        private final RagConfiguration config = new RagConfiguration();

        private void grantedTo(String... agents) {
            when(checker.findAgentRestrictedReferences(config))
                    .thenReturn(List.of(new VaultGrantChecker.RestrictedReference("${vault:agent-a-key}", List.of(agents), false)));
        }

        @Test
        @DisplayName("the exploit: an editor who may edit none of the granted agents is refused, without the agents being named")
        void editorWithoutAccessToTheGrantedAgentIsRefused() {
            grantedTo(AGENT_A);

            var refused = assertThrows(ForbiddenException.class, () -> guard.requireMayUse(config, "knowledge base"));
            assertTrue(refused.getMessage().contains("${vault:agent-a-key}"), refused.getMessage());
            assertFalse(refused.getMessage().contains(AGENT_A), refused.getMessage());
        }

        @Test
        @DisplayName("an editor who may edit a granted agent keeps using the knowledge base")
        void editorOfAGrantedAgentIsAllowed() {
            grantedTo(AGENT_A);
            when(accessGuard.hasAccess(AGENT_A, AccessLevel.EDIT)).thenReturn(true);

            assertDoesNotThrow(() -> guard.requireMayUse(config, "knowledge base"));
        }

        @Test
        @DisplayName("a grant naming only deleted or unknown agents lets no editor through, with or without workspaces")
        void grantOfUnknownAgentsIsRefused() {
            grantedTo();

            assertThrows(ForbiddenException.class, () -> guard.requireMayUse(config, "knowledge base"));
            when(settings.isEnforcing()).thenReturn(false);
            assertThrows(ForbiddenException.class, () -> guard.requireMayUse(config, "knowledge base"));
        }

        @Test
        @DisplayName("an unverifiable entry is refused even to an editor of every agent")
        void unverifiableIsRefused() {
            when(checker.findAgentRestrictedReferences(config))
                    .thenReturn(List.of(new VaultGrantChecker.RestrictedReference("${vault:x} (its grant could not be read)", List.of(), true)));
            when(accessGuard.hasAccess(anyString(), any())).thenReturn(true);

            assertThrows(ForbiddenException.class, () -> guard.requireMayUse(config, "knowledge base"));
        }

        @Test
        @DisplayName("an administrator is exempt")
        void adminIsExempt() {
            grantedTo(AGENT_A);
            when(accessGuard.isAdmin()).thenReturn(true);

            assertDoesNotThrow(() -> guard.requireMayUse(config, "knowledge base"));
        }

        @Test
        @DisplayName("a wildcard or empty grant is not reported, so it passes as before")
        void unrestrictedPasses() {
            when(checker.findAgentRestrictedReferences(config)).thenReturn(List.of());

            assertDoesNotThrow(() -> guard.requireMayUse(config, "knowledge base"));
        }

        @Test
        @DisplayName("a check that cannot run fails closed")
        void checkFailureFailsClosed() {
            when(checker.findAgentRestrictedReferences(config)).thenThrow(new IllegalStateException("store down"));

            assertThrows(ForbiddenException.class, () -> guard.requireMayUse(config, "knowledge base"));
        }

        @Test
        @DisplayName("grant-enforcement=warn lets it through, off does not look")
        void followsTheEnforcementMode() {
            grantedTo(AGENT_A);

            guard.grantEnforcement = "warn";
            assertDoesNotThrow(() -> guard.requireMayUse(config, "knowledge base"));

            guard.grantEnforcement = "off";
            assertDoesNotThrow(() -> guard.requireMayUse(config, "knowledge base"));
            verify(checker).findAgentRestrictedReferences(config);
        }
    }
}