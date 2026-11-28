/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle.internal;

import ai.labs.eddi.secrets.ResolvedCredentials;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The task-error digest and the audit/SSE summary are scrubbed by value of the
 * credentials handed to model clients — a prefix-less key the pattern redaction
 * cannot recognise.
 */
@DisplayName("LifecycleManager — task errors scrubbed of resolved credentials")
class LifecycleManagerCredentialScrubbingTest {

    /**
     * A prefix-less value, built at runtime so no key-shaped literal is in the
     * source.
     */
    private static final String GATEWAY_VALUE = "gateway" + "q".repeat(12);

    @Test
    @DisplayName("the digest and the audit summary do not carry an echoed credential")
    void echoedCredentialIsScrubbed() {
        var credentials = new ResolvedCredentials();
        credentials.record(List.of(GATEWAY_VALUE));
        var failure = new IllegalStateException("gateway rejected " + GATEWAY_VALUE + " for model gpt-x");

        String digest = LifecycleManager.summarizeException(failure, credentials::scrub);
        String audit = LifecycleManager.summarizeForAudit(failure, credentials::scrub);

        assertFalse(digest.contains(GATEWAY_VALUE), digest);
        assertTrue(digest.contains("gateway rejected"), digest);
        assertFalse(audit.contains(GATEWAY_VALUE), audit);
    }

    @Test
    @DisplayName("without the scrubber the pattern redaction alone does not catch it — the gap this closes")
    void patternRedactionAloneMissesIt() {
        String digest = LifecycleManager.summarizeException(new IllegalStateException("gateway rejected " + GATEWAY_VALUE));

        assertTrue(digest.contains(GATEWAY_VALUE), digest);
    }
}
