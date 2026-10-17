/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.outbound;

/**
 * A class of outbound traffic with its own connection pool, timeouts, TLS and
 * default SSRF/redirect policy.
 * <p>
 * The set is fixed on purpose: every profile's configuration keys are spelled
 * out literally ({@code eddi.http.outbound.<key>.*}) so the configuration
 * reference can be checked against the code, which an open-ended map of
 * profiles would defeat.
 */
public enum Profile {

    /** LLM tools, the crawler, OAuth token requests, attachment downloads. */
    DEFAULT("default", SsrfPolicy.STRICT, RedirectPolicy.follow(5, MethodRule.JDK_SAFE)),

    /** Model providers and embedding endpoints (langchain4j). */
    LLM_PROVIDER("llm-provider", SsrfPolicy.METADATA_ONLY, RedirectPolicy.follow(5, MethodRule.JDK_SAFE)),

    /**
     * {@code httpcalls.json}. Redirects follow the Vert.x client's rules, and stop
     * entirely when {@code eddi.security.ssrf-protection.enabled} is on — see
     * {@link #defaultRedirects(SsrfPolicy)}.
     */
    APICALLS("apicalls", SsrfPolicy.FOLLOW_GLOBAL_FLAG, RedirectPolicy.follow(5, MethodRule.VERTX_COMPAT)),

    /**
     * Another EDDI instance (remote agent sync). The caller's own source validator
     * is the gate, so the facade adds no SSRF check of its own.
     */
    INTERNAL_PEER("internal-peer", SsrfPolicy.NONE, RedirectPolicy.NEVER);

    private final String key;
    private final SsrfPolicy defaultSsrf;
    private final RedirectPolicy defaultRedirects;

    Profile(String key, SsrfPolicy defaultSsrf, RedirectPolicy defaultRedirects) {
        this.key = key;
        this.defaultSsrf = defaultSsrf;
        this.defaultRedirects = defaultRedirects;
    }

    /** The segment naming this profile in {@code eddi.http.outbound.<key>.*}. */
    public String key() {
        return key;
    }

    public SsrfPolicy defaultSsrf() {
        return defaultSsrf;
    }

    /**
     * The redirect policy a request of this profile gets when it names none, given
     * the SSRF policy it ended up with. Fully validated {@code apicalls} traffic
     * follows no redirect at all, which is what the Vert.x client does today with
     * SSRF protection on.
     */
    public RedirectPolicy defaultRedirects(SsrfPolicy effectiveSsrf) {
        if (this == APICALLS && effectiveSsrf == SsrfPolicy.STRICT) {
            return RedirectPolicy.NEVER;
        }
        return defaultRedirects;
    }
}
