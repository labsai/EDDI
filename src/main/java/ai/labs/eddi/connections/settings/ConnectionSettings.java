/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.settings;

import java.util.List;
import java.util.Objects;

/**
 * The deployment-level settings of the connections feature, as an administrator
 * writes them through {@code PUT /connectionstore/settings}.
 * <p>
 * Every field is nullable, and null means <em>not set</em>: the effective value
 * then comes from a pinned property or from the built-in default. That is what
 * lets a request clear one setting without restating the others' defaults, and
 * what lets the effective view say where each value came from.
 * <p>
 * These used to be properties only, which made every change a restart. They are
 * not per-connection settings — a connection document must not be able to
 * approve its own token endpoint or its own plaintext origin — so they live in
 * their own document rather than on each connection. An operator who wants a
 * value out of administrators' reach still sets the property, which pins it;
 * see {@code ConnectionsConfig}.
 */
public class ConnectionSettings {

    /** Master switch. Default {@code false}. */
    private Boolean enabled;

    /**
     * EDDI's own public origin, from which the OAuth {@code redirect_uri} is built.
     * Default: none — authorization-code linking is refused until it is set.
     */
    private String publicBaseUrl;

    /**
     * Origins a client secret may be sent to. Default: empty — no OAuth connection
     * can resolve (fail closed).
     */
    private List<String> credentialEndpointAllowlist;

    /**
     * Whether a connection's credential may travel over plaintext http to a
     * non-loopback host. Default {@code false}.
     */
    private Boolean allowPlaintextRemoteOrigins;

    public ConnectionSettings() {
    }

    public ConnectionSettings(Boolean enabled, String publicBaseUrl, List<String> credentialEndpointAllowlist,
            Boolean allowPlaintextRemoteOrigins) {
        this.enabled = enabled;
        this.publicBaseUrl = publicBaseUrl;
        this.credentialEndpointAllowlist = credentialEndpointAllowlist;
        this.allowPlaintextRemoteOrigins = allowPlaintextRemoteOrigins;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getPublicBaseUrl() {
        return publicBaseUrl;
    }

    public void setPublicBaseUrl(String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }

    public List<String> getCredentialEndpointAllowlist() {
        return credentialEndpointAllowlist;
    }

    public void setCredentialEndpointAllowlist(List<String> credentialEndpointAllowlist) {
        this.credentialEndpointAllowlist = credentialEndpointAllowlist;
    }

    public Boolean getAllowPlaintextRemoteOrigins() {
        return allowPlaintextRemoteOrigins;
    }

    public void setAllowPlaintextRemoteOrigins(Boolean allowPlaintextRemoteOrigins) {
        this.allowPlaintextRemoteOrigins = allowPlaintextRemoteOrigins;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ConnectionSettings that)) {
            return false;
        }
        return Objects.equals(enabled, that.enabled) && Objects.equals(publicBaseUrl, that.publicBaseUrl)
                && Objects.equals(credentialEndpointAllowlist, that.credentialEndpointAllowlist)
                && Objects.equals(allowPlaintextRemoteOrigins, that.allowPlaintextRemoteOrigins);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, publicBaseUrl, credentialEndpointAllowlist, allowPlaintextRemoteOrigins);
    }

    @Override
    public String toString() {
        return "ConnectionSettings{enabled=" + enabled + ", publicBaseUrl=" + publicBaseUrl + ", credentialEndpointAllowlist="
                + credentialEndpointAllowlist + ", allowPlaintextRemoteOrigins=" + allowPlaintextRemoteOrigins + "}";
    }
}
