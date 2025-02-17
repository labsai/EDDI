package ai.labs.eddi.modules.templating.impl.dialects.encoding;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class EncoderWrapperTest {
    private final EncoderWrapper encoderWrapper = new EncoderWrapper();

    @Test
    void testBase64WithHelloWorld() {
        String plain = "Hello World";
        // Compute the expected result using the standard Base64 encoder.
        String expected = Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
        Object result = encoderWrapper.base64(plain);
        assertInstanceOf(String.class, result, "Result should be a String");
        assertEquals(expected, result);
    }

    @Test
    void testBase64Empty() {
        String plain = "";
        String expected = Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
        Object result = encoderWrapper.base64(plain);
        assertEquals(expected, result);
    }

    @Test
    void testBase64NullInput() {
        // A null input will cause plain.getBytes(...) to throw a NullPointerException.
        assertThrows(NullPointerException.class, () -> encoderWrapper.base64(null));
    }

    @Test
    void testBase64UrlWithSpecialCharacters() {
        // The URL encoder should replace these with '-' and '_' respectively.
        String plain = "Test+and/Check";
        String expected = Base64.getUrlEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
        Object result = encoderWrapper.base64Url(plain);
        assertInstanceOf(String.class, result, "Result should be a String");
        assertEquals(expected, result);

        // Additionally, check that the URL-safe encoding does not contain '+' or '/'.
        String encodedUrl = (String) result;
        assertFalse(encodedUrl.contains("+"), "URL-safe Base64 should not contain '+'");
        assertFalse(encodedUrl.contains("/"), "URL-safe Base64 should not contain '/'");
    }

    @Test
    void testBase64UrlEmpty() {
        String plain = "";
        String expected = Base64.getUrlEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
        Object result = encoderWrapper.base64Url(plain);
        assertEquals(expected, result);
    }

    @Test
    void testBase64UrlNullInput() {
        assertThrows(NullPointerException.class, () -> encoderWrapper.base64Url(null));
    }

    @Test
    void testBase64MimeWithHelloWorld() {
        String plain = "Hello World";
        // For a short input, MIME encoding returns the same output as the standard encoder (no line breaks).
        String expected = Base64.getMimeEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
        Object result = encoderWrapper.base64Mime(plain);
        assertInstanceOf(String.class, result, "Result should be a String");
        assertEquals(expected, result);
    }

    @Test
    void testBase64MimeEmpty() {
        String plain = "";
        String expected = Base64.getMimeEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
        Object result = encoderWrapper.base64Mime(plain);
        assertEquals(expected, result);
    }

    @Test
    void testBase64MimeNullInput() {
        assertThrows(NullPointerException.class, () -> encoderWrapper.base64Mime(null));
    }

    @Test
    void testBase64MimeWithLongInput() {
        // Build a long string so that the MIME encoder will insert line breaks (typically CRLF every 76 characters).
        // Repeat a pattern to ensure the encoded output is long.
        String plain = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".repeat(100);

        // Compute expected result using the standard MIME encoder.
        String expected = Base64.getMimeEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
        Object result = encoderWrapper.base64Mime(plain);
        assertEquals(expected, result);

        // Additionally, assert that the MIME-encoded string contains line breaks.
        String encodedMime = (String) result;
        assertTrue(encodedMime.contains("\r\n") || encodedMime.contains("\n"),
                "MIME encoded string should contain line breaks");
    }
}
