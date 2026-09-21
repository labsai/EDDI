/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces;

import io.quarkus.runtime.Startup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorkspaceSettings} refuses to boot on an unrecognised
 * {@code legacy-visibility} — the two accepted values differ on whether every
 * pre-upgrade agent is visible, which is not a difference to resolve by
 * guessing.
 * <p>
 * There was no test for that refusal, and it did not do what its comment said.
 * An {@code @ApplicationScoped} bean is instantiated on first use through its
 * client proxy, and every injection point here is lazily proxied, so a typo
 * booted green and threw a {@code CreationException} as a 500 on the first
 * guarded request — and on every request after it, since the bean is never
 * created. "Fail loud at startup" had become "fail on the hot path after
 * deploy".
 */
@DisplayName("workspace settings")
class WorkspaceSettingsTest {

    private static WorkspaceSettings settings(String legacyVisibility) {
        return new WorkspaceSettings(false, false, "groups", legacyVisibility, Optional.empty());
    }

    /**
     * The annotation is the fix. Without it the validation below still throws, just
     * at the wrong moment and against a caller rather than an operator, so
     * asserting the exception alone would have passed before the change too.
     */
    @Test
    @DisplayName("the bean is eager, so a bad value fails the boot rather than the first request")
    void beanIsInstantiatedAtStartup() {
        assertTrue(WorkspaceSettings.class.isAnnotationPresent(Startup.class),
                "WorkspaceSettings must be @Startup: its validation is the boot-time refusal an operator relies on, and a "
                        + "lazily-created bean defers it to the first guarded request instead");
    }

    @Test
    @DisplayName("an unrecognised legacy-visibility is refused, naming the value and both options")
    void unrecognisedLegacyVisibilityIsRefused() {
        // 'admin_only' rather than 'admin-only' — an underscore for a hyphen is the
        // typo this guard exists for.
        var thrown = assertThrows(IllegalStateException.class, () -> settings("admin_only").validate());

        assertTrue(thrown.getMessage().contains("admin_only"), "the failure must quote the offending value: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains(WorkspaceSettings.LEGACY_SHARED)
                && thrown.getMessage().contains(WorkspaceSettings.LEGACY_ADMIN_ONLY),
                "the failure must name both accepted values so the operator can fix it without reading the source: " + thrown.getMessage());
    }

    @Test
    @DisplayName("both accepted values pass, case-insensitively")
    void acceptedValuesPass() {
        for (String value : new String[]{WorkspaceSettings.LEGACY_SHARED, WorkspaceSettings.LEGACY_ADMIN_ONLY, "SHARED", "Admin-Only"}) {
            assertDoesNotThrow(() -> settings(value).validate(), value + " is a legal legacy-visibility");
        }
    }

    /**
     * An absent or blank value is the common case — the property is optional — and
     * must not be treated as a typo.
     */
    @Test
    @DisplayName("a blank value falls back to the shared default rather than failing")
    void blankFallsBackToTheDefault() {
        for (String value : new String[]{null, "", "   "}) {
            var s = settings(value);
            assertDoesNotThrow(s::validate);
            assertTrue(s.admitsLegacy(), "the default admits unowned data to everyone");
        }
    }

    /**
     * The two options differ on exactly one observable: whether unowned,
     * pre-upgrade data is admitted. Asserting the parse without asserting that
     * would leave the branch untested.
     */
    @Test
    @DisplayName("the chosen value decides whether unowned data is admitted")
    void legacyVisibilityDecidesAdmission() {
        var shared = settings(WorkspaceSettings.LEGACY_SHARED);
        shared.validate();
        assertTrue(shared.admitsLegacy(), "'shared' admits unowned data to everyone");

        var adminOnly = settings(WorkspaceSettings.LEGACY_ADMIN_ONLY);
        adminOnly.validate();
        assertEquals(false, adminOnly.admitsLegacy(), "'admin-only' withholds unowned data from non-admins");
    }
}
