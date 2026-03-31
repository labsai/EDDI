package ai.labs.eddi.secrets;

import ai.labs.eddi.secrets.model.SecretReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SecretResolverTest {

    private ISecretProvider secretProvider;
    private SecretResolver resolver;

    @BeforeEach
    void setUp() {
        secretProvider = mock(ISecretProvider.class);
        when(secretProvider.isAvailable()).thenReturn(true);
        resolver = new SecretResolver(secretProvider, 5, 100);
        resolver.init(); // Initialize the Caffeine cache (@PostConstruct)
    }

    @Test
    void resolveValue_noVaultRef_passthrough() {
        String input = "just a plain string";
        assertEquals(input, resolver.resolveValue(input));
    }

    @Test
    void resolveValue_null_returnsNull() {
        assertNull(resolver.resolveValue(null));
    }

    @Test
    void resolveValue_empty_returnsEmpty() {
        assertEquals("", resolver.resolveValue(""));
    }

    @Test
    void resolveValue_shortForm_resolvesToPlaintext() throws ISecretProvider.SecretNotFoundException, ISecretProvider.SecretProviderException {
        var ref = new SecretReference("default", "openaiKey");
        when(secretProvider.resolve(ref)).thenReturn("sk-actual-secret-key");

        String input = "Bearer ${eddivault:openaiKey}";
        String result = resolver.resolveValue(input);

        assertEquals("Bearer sk-actual-secret-key", result);
    }

    @Test
    void resolveValue_fullForm_resolvesToPlaintext() throws ISecretProvider.SecretNotFoundException, ISecretProvider.SecretProviderException {
        var ref = new SecretReference("myTenant", "openaiKey");
        when(secretProvider.resolve(ref)).thenReturn("sk-tenant-key");

        String input = "Bearer ${eddivault:myTenant/openaiKey}";
        String result = resolver.resolveValue(input);

        assertEquals("Bearer sk-tenant-key", result);
    }

    @Test
    void resolveValue_multipleRefs_allResolved() throws ISecretProvider.SecretNotFoundException, ISecretProvider.SecretProviderException {
        when(secretProvider.resolve(new SecretReference("default", "key1"))).thenReturn("val1");
        when(secretProvider.resolve(new SecretReference("default", "key2"))).thenReturn("val2");

        String input = "${eddivault:key1}:${eddivault:key2}";
        String result = resolver.resolveValue(input);

        assertEquals("val1:val2", result);
    }

    @Test
    void resolveValue_mixedFormats_allResolved() throws ISecretProvider.SecretNotFoundException, ISecretProvider.SecretProviderException {
        when(secretProvider.resolve(new SecretReference("default", "key1"))).thenReturn("val1");
        when(secretProvider.resolve(new SecretReference("acme", "key2"))).thenReturn("val2");

        String input = "${eddivault:key1}:${eddivault:acme/key2}";
        String result = resolver.resolveValue(input);

        assertEquals("val1:val2", result);
    }

    @Test
    void resolveValue_secretNotFound_keepsRef() throws ISecretProvider.SecretNotFoundException, ISecretProvider.SecretProviderException {
        when(secretProvider.resolve(any(SecretReference.class))).thenThrow(new ISecretProvider.SecretNotFoundException("not found"));

        String input = "Bearer ${eddivault:missingKey}";
        String result = resolver.resolveValue(input);

        // When secret is not found, the reference should remain as-is
        assertEquals(input, result);
    }

    @Test
    void resolveValue_vaultNotAvailable_passthrough() {
        ISecretProvider unavailable = mock(ISecretProvider.class);
        when(unavailable.isAvailable()).thenReturn(false);
        SecretResolver passthroughResolver = new SecretResolver(unavailable, 5, 100);
        passthroughResolver.init();

        String input = "Bearer ${eddivault:key}";
        assertEquals(input, passthroughResolver.resolveValue(input));
    }

    @Test
    void resolveValue_autoVaultKey_withAgentPrefix() throws ISecretProvider.SecretNotFoundException, ISecretProvider.SecretProviderException {
        var ref = new SecretReference("default", "69c687.userApiKey");
        when(secretProvider.resolve(ref)).thenReturn("user-secret");

        String result = resolver.resolveValue("${eddivault:69c687.userApiKey}");

        assertEquals("user-secret", result);
    }
}
