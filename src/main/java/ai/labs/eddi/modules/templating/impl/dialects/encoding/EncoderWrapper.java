package ai.labs.eddi.modules.templating.impl.dialects.encoding;

import java.util.Base64;

import static java.nio.charset.StandardCharsets.UTF_8;

public class EncoderWrapper {
    public String base64(String plain) {
        return Base64.getEncoder().encodeToString(plain.getBytes(UTF_8));
    }

    public String base64Url(String plain) {
        return Base64.getUrlEncoder().encodeToString(plain.getBytes(UTF_8));
    }

    public String base64Mime(String plain) {
        return Base64.getMimeEncoder().encodeToString(plain.getBytes(UTF_8));
    }
}
