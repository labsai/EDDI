/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.secrets.VaultGrantChecker;
import io.quarkus.security.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
    @DisplayName("nothing is checked without workspace enforcement, or for an administrator")
    void noOpWhenNotEnforcedOrAdmin() {
        when(settings.isEnforcing()).thenReturn(false);
        assertDoesNotThrow(() -> guard.requireMayUse(new RagConfiguration(), "knowledge base"));

        when(settings.isEnforcing()).thenReturn(true);
        when(accessGuard.isAdmin()).thenReturn(true);
        assertDoesNotThrow(() -> guard.requireMayUse(new RagConfiguration(), "knowledge base"));
        verifyNoInteractions(checker);
    }
}
