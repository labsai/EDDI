package ai.labs.eddi.secrets.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class SecretReferenceTest {

    @Test
    void parse_validReference() {
        var ref = SecretReference.parse("${eddivault:default/myBot/openaiKey}");

        assertEquals("default", ref.tenantId());
        assertEquals("myBot", ref.botId());
        assertEquals("openaiKey", ref.keyName());
    }

    @Test
    void toReferenceString_roundtrip() {
        var ref = new SecretReference("tenant1", "bot42", "apiKey");
        String refStr = ref.toReferenceString();

        assertEquals("${eddivault:tenant1/bot42/apiKey}", refStr);

        var parsed = SecretReference.parse(refStr);
        assertEquals(ref, parsed);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-a-vault-ref",
            "${vault:foo/bar}",
            "${eddivault:}",
            "${eddivault:onlyOneSegment}",
            "${eddivault:two/segments}"
    })
    void parse_invalidReference_throws(String input) {
        assertThrows(IllegalArgumentException.class, () -> SecretReference.parse(input));
    }

    @Test
    void isVaultReference_positive() {
        assertTrue(SecretReference.isVaultReference("${eddivault:t/b/k}"));
        assertTrue(SecretReference.isVaultReference("Bearer ${eddivault:t/b/k} extra"));
    }

    @Test
    void isVaultReference_negative() {
        assertFalse(SecretReference.isVaultReference("plain text"));
        assertFalse(SecretReference.isVaultReference(""));
        assertFalse(SecretReference.isVaultReference(null));
        assertFalse(SecretReference.isVaultReference("${vault:different}"));
    }
}
