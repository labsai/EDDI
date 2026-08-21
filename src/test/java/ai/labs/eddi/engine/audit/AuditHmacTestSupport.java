/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Signs a canonical string the way the ledger's writer does, so a test can
 * plant a row in an older canonical form without {@link AuditHmac} having to
 * expose a way to write one. Production code only ever writes the current form.
 */
final class AuditHmacTestSupport {

    private AuditHmacTestSupport() {
        // Test helper
    }

    static String hmacHex(String canonical, byte[] hmacKey) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign canonical string for test", e);
        }
    }
}
