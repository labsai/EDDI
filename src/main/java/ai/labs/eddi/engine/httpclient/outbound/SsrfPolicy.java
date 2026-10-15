/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.outbound;

/**
 * Which targets of an exchange are checked against the SSRF rules in
 * {@code UrlValidationUtils}, and how strictly.
 */
public enum SsrfPolicy {

    /**
     * The initial URL and every redirect hop get the full check: no loopback,
     * private, link-local or cloud-metadata target. For user- or LLM-chosen URLs.
     */
    STRICT,

    /**
     * The initial URL is trusted — it was built from configuration — but every
     * redirect hop gets the full check, because the server chose it.
     */
    HOPS_ONLY,

    /**
     * Only the cloud instance-metadata service is refused, on the initial URL and
     * every hop. For operator-configured targets that legitimately live on a
     * private network.
     */
    METADATA_ONLY,

    /** No check at all. The caller has a stricter gate of its own. */
    NONE,

    /**
     * {@link #STRICT} when {@code eddi.security.ssrf-protection.enabled} is on,
     * {@link #METADATA_ONLY} otherwise — the rule httpcalls, MCP and A2A have
     * always followed.
     */
    FOLLOW_GLOBAL_FLAG;

    /** This policy with {@link #FOLLOW_GLOBAL_FLAG} decided. */
    public SsrfPolicy resolve(boolean ssrfProtectionEnabled) {
        if (this != FOLLOW_GLOBAL_FLAG) {
            return this;
        }
        return ssrfProtectionEnabled ? STRICT : METADATA_ONLY;
    }
}
