package ai.labs.eddi.engine.security;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AuthStartupGuard}. Plain unit test (not @QuarkusTest).
 * <p>
 * Uses reflection to set @ConfigProperty fields and a Mockito spy to override
 * {@code getLaunchMode()} since {@code LaunchMode.current()} is static.
 *
 * @since 6.0.2
 */
class AuthStartupGuardTest {

    private AuthStartupGuard createGuard(boolean oidcEnabled, boolean allowUnauthenticated,
                                         LaunchMode launchMode)
            throws Exception {
        AuthStartupGuard guard = spy(new AuthStartupGuard());

        // Set @ConfigProperty fields via reflection
        setField(guard, "oidcEnabled", oidcEnabled);
        setField(guard, "allowUnauthenticated", allowUnauthenticated);

        // Override getLaunchMode() to control the static LaunchMode.current()
        doReturn(launchMode).when(guard).getLaunchMode();

        return guard;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        // Walk up class hierarchy to find the field (spy creates a subclass)
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    @Test
    @DisplayName("Dev mode + OIDC disabled → no exception")
    void devModeOidcDisabled_shouldNotThrow() throws Exception {
        AuthStartupGuard guard = createGuard(false, false, LaunchMode.DEVELOPMENT);

        assertDoesNotThrow(() -> guard.onStart(new StartupEvent()));
    }

    @Test
    @DisplayName("Test mode + OIDC disabled → no exception (tests must not be blocked)")
    void testModeOidcDisabled_shouldNotThrow() throws Exception {
        AuthStartupGuard guard = createGuard(false, false, LaunchMode.TEST);

        assertDoesNotThrow(() -> guard.onStart(new StartupEvent()));
    }

    @Test
    @DisplayName("Prod mode + OIDC disabled + no escape hatch → throws IllegalStateException")
    void prodModeOidcDisabled_shouldThrow() throws Exception {
        AuthStartupGuard guard = createGuard(false, false, LaunchMode.NORMAL);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guard.onStart(new StartupEvent()));
        assertTrue(ex.getMessage().contains("OIDC must be enabled"), ex.getMessage());
    }

    @Test
    @DisplayName("Prod mode + OIDC disabled + escape hatch → does not throw, sets warnMode")
    void prodModeOidcDisabledWithEscapeHatch_shouldWarnNotThrow() throws Exception {
        AuthStartupGuard guard = createGuard(false, true, LaunchMode.NORMAL);

        assertDoesNotThrow(() -> guard.onStart(new StartupEvent()));

        // Verify warnMode is set (periodic warning would fire)
        Field warnModeField = AuthStartupGuard.class.getDeclaredField("warnMode");
        warnModeField.setAccessible(true);
        assertTrue((boolean) warnModeField.get(guard),
                "warnMode should be true when escape hatch is used");
    }

    @Test
    @DisplayName("Prod mode + OIDC enabled → no exception, no warning")
    void prodModeOidcEnabled_shouldNotThrow() throws Exception {
        AuthStartupGuard guard = createGuard(true, false, LaunchMode.NORMAL);

        assertDoesNotThrow(() -> guard.onStart(new StartupEvent()));

        // warnMode should remain false
        Field warnModeField = AuthStartupGuard.class.getDeclaredField("warnMode");
        warnModeField.setAccessible(true);
        assertFalse((boolean) warnModeField.get(guard),
                "warnMode should be false when OIDC is enabled");
    }
}
