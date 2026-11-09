/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The expected encodings are literals produced independently (Python's
 * {@code base64}, {@code urllib.parse}, {@code json} and {@code html}), not
 * recomputed with the JDK calls the implementation uses.
 */
@DisplayName("SecretEncodings")
class SecretEncodingsTest {

    @Nested
    @DisplayName("base64")
    class Base64Forms {

        @Test
        @DisplayName("the review's echo: standard base64, padded and unpadded")
        void standard() {
            Set<String> forms = SecretEncodings.encodedForms("deadbeefcafe1234");
            assertTrue(forms.contains("ZGVhZGJlZWZjYWZlMTIzNA=="), forms.toString());
            assertTrue(forms.contains("ZGVhZGJlZWZjYWZlMTIzNA"), forms.toString());
        }

        @Test
        @DisplayName("URL-safe alphabet, padded and unpadded, where it differs from the standard one")
        void urlSafe() {
            Set<String> forms = SecretEncodings.encodedForms("p@ss word/123~?>>");
            assertTrue(forms.containsAll(List.of("cEBzcyB3b3JkLzEyM34/Pj4=", "cEBzcyB3b3JkLzEyM34/Pj4", "cEBzcyB3b3JkLzEyM34_Pj4=",
                    "cEBzcyB3b3JkLzEyM34_Pj4")), forms.toString());
        }
    }

    @Nested
    @DisplayName("percent-encoding")
    class PercentForms {

        @Test
        @DisplayName("a space as + or %20, hex in either case, ~ escaped or literal")
        void spellings() {
            Set<String> forms = SecretEncodings.encodedForms("p@ss word/123~?>>");
            assertTrue(forms.containsAll(List.of(
                    "p%40ss+word%2F123~%3F%3E%3E",
                    "p%40ss%20word%2F123~%3F%3E%3E",
                    "p%40ss+word%2F123%7E%3F%3E%3E",
                    "p%40ss%20word%2F123%7E%3F%3E%3E",
                    "p%40ss+word%2f123~%3f%3e%3e",
                    "p%40ss%20word%2f123~%3f%3e%3e")), forms.toString());
        }

        @Test
        @DisplayName("a literal + in the value is %2B, never confused with a space")
        void literalPlus() {
            Set<String> forms = SecretEncodings.encodedForms("a+b c+d-1234");
            assertTrue(forms.contains("a%2Bb+c%2Bd-1234"), forms.toString());
            assertTrue(forms.contains("a%2Bb%20c%2Bd-1234"), forms.toString());
        }
    }

    @Nested
    @DisplayName("JSON string escaping")
    class JsonForms {

        @Test
        @DisplayName("quote, backslash and control characters escaped; / escaped or not")
        void escapes() {
            Set<String> forms = SecretEncodings.encodedForms("ab\"cd\\ef/gh\n1");
            assertTrue(forms.contains("ab\\\"cd\\\\ef/gh\\n1"), forms.toString());
            assertTrue(forms.contains("ab\\\"cd\\\\ef\\/gh\\n1"), forms.toString());
        }

        @Test
        @DisplayName("non-ASCII as \\u escapes in either hex case (Python's ensure_ascii default)")
        void asciiOnly() {
            Set<String> forms = SecretEncodings.encodedForms("pässwörd-123");
            assertTrue(forms.contains("p\\u00e4ssw\\u00f6rd-123"), forms.toString());
            assertTrue(forms.contains("p\\u00E4ssw\\u00F6rd-123"), forms.toString());
        }
    }

    @Nested
    @DisplayName("HTML entity escaping")
    class HtmlForms {

        @Test
        @DisplayName("& < > \" ' in the spellings of common escapers")
        void spellings() {
            Set<String> forms = SecretEncodings.encodedForms("a<b>&\"c'1234");
            assertTrue(forms.containsAll(List.of(
                    "a&lt;b&gt;&amp;&quot;c&#x27;1234",
                    "a&lt;b&gt;&amp;&#34;c&#39;1234",
                    "a&lt;b&gt;&amp;&quot;c&#039;1234",
                    "a&lt;b&gt;&amp;&quot;c&#39;1234",
                    "a&lt;b&gt;&amp;&quot;c'1234")), forms.toString());
        }
    }

    @Nested
    @DisplayName("withEncodedForms")
    class WithEncodedForms {

        @Test
        @DisplayName("keeps every value, encodes only those at or above the minimum length")
        void minimumLength() {
            Set<String> needles = SecretEncodings.withEncodedForms(Arrays.asList("alice12", "deadbeefcafe1234", null, ""), 8);

            assertTrue(needles.contains("alice12"), "a short value is kept as it is");
            assertFalse(needles.contains("YWxpY2UxMg=="), "but its encodings are not added: " + needles);
            assertFalse(needles.contains("YWxpY2UxMg"), needles.toString());
            assertTrue(needles.contains("deadbeefcafe1234"));
            assertTrue(needles.contains("ZGVhZGJlZWZjYWZlMTIzNA=="), needles.toString());
            assertFalse(needles.contains(null));
            assertFalse(needles.contains(""));
        }

        @Test
        @DisplayName("an alphanumeric value adds only its base64 forms — it is its own percent, JSON and HTML form")
        void noDuplicates() {
            Set<String> needles = SecretEncodings.withEncodedForms(List.of("deadbeefcafe1234"), 8);
            assertEquals(Set.of("deadbeefcafe1234", "ZGVhZGJlZWZjYWZlMTIzNA==", "ZGVhZGJlZWZjYWZlMTIzNA"), needles);
        }

        @Test
        @DisplayName("every encoded form is at least as long as the value, so the floor bounds the forms too")
        void formsNotShorter() {
            for (String value : List.of("abcdefgh", "p@ss word/123~?>>", "a<b>&\"c'1234", "pässwörd")) {
                for (String form : SecretEncodings.encodedForms(value)) {
                    assertTrue(form.length() >= value.length(), value + " -> " + form);
                }
            }
        }

        @Test
        @DisplayName("null and empty input yield an empty set")
        void empty() {
            assertTrue(SecretEncodings.withEncodedForms(null, 8).isEmpty());
            assertTrue(SecretEncodings.withEncodedForms(List.of(), 8).isEmpty());
        }
    }

    @Nested
    @DisplayName("Basic credentials")
    class BasicCredentials {

        @Test
        @DisplayName("a whole Basic credential also yields user:password and the password")
        void decodesWholeCredential() {
            Set<String> needles = SecretEncodings.withEncodedForms(List.of("Basic c3ZjLXVzZXI6U3VwM3JTM2NyZXRQdw=="), 8);

            assertTrue(needles.contains("svc-user:Sup3rS3cretPw"), needles.toString());
            assertTrue(needles.contains("Sup3rS3cretPw"), needles.toString());
            assertFalse(needles.contains("svc-user"), "the username alone is not a secret: " + needles);
        }

        @Test
        @DisplayName("a user:password secret covers an echoed Basic header through its base64 form")
        void credentialCoversHeader() {
            Set<String> needles = SecretEncodings.withEncodedForms(List.of("svc-user:Sup3rS3cretPw"), 8);
            assertTrue(needles.contains("c3ZjLXVzZXI6U3VwM3JTM2NyZXRQdw=="), needles.toString());
        }

        @Test
        @DisplayName("a password shorter than the minimum is not added on its own")
        void shortPassword() {
            // base64("user:pw12")
            assertEquals(List.of("user:pw12", "pw12"), SecretEncodings.basicCredentialParts("Basic dXNlcjpwdzEy"));
            Set<String> needles = SecretEncodings.withEncodedForms(List.of("Basic dXNlcjpwdzEy"), 8);
            assertTrue(needles.contains("user:pw12"));
            assertFalse(needles.contains("pw12"), needles.toString());
        }

        @Test
        @DisplayName("prose, a token without a colon and invalid base64 decode to nothing")
        void notACredential() {
            assertEquals(List.of(), SecretEncodings.basicCredentialParts("Basic configuration"));
            assertEquals(List.of(), SecretEncodings.basicCredentialParts("Basic ZGVhZGJlZWZjYWZlMTIzNA=="));
            assertEquals(List.of(), SecretEncodings.basicCredentialParts("Basic !!!"));
            assertEquals(List.of(), SecretEncodings.basicCredentialParts("Bearer c3ZjLXVzZXI6U3VwM3JTM2NyZXRQdw=="));
            // 0xFF 0xFE 0x3A is not UTF-8
            assertEquals(List.of(), SecretEncodings.basicCredentialParts("Basic //46"));
        }
    }
}
