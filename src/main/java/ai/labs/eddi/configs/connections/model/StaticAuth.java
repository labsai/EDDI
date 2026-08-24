/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.connections.model;

/**
 * The {@link AuthType#STATIC} and {@link AuthType#BASIC} half of a connection.
 * <p>
 * Every secret-bearing field here is <b>reference-only</b> — see
 * {@link ConnectionConfiguration#validate()}. A plaintext key in a connection
 * document would bypass the vault, export scrubbing and deploy-time grant
 * enforcement in one move.
 */
public class StaticAuth {

    /**
     * Header the credential is sent in — {@code Authorization}, {@code X-Api-Key},
     * … Non-secret by nature, so it is stored verbatim.
     */
    private String headerName = "Authorization";

    /**
     * The header's value, with references interpolated — e.g. {@code "Bearer
     * ${vault:jira-token}"}. Any interpolated segment must be a {@code ${vault:…}}
     * or {@code ${vars:…}} reference; a literal that looks like a credential is
     * rejected at validate time.
     */
    private String valueTemplate;

    /** BASIC only. A username is an identifier, not a secret. */
    private String username;

    /** BASIC only. Must be a {@code ${vault:…}} reference. */
    private String passwordRef;

    public String getHeaderName() {
        return headerName;
    }

    public void setHeaderName(String headerName) {
        this.headerName = headerName;
    }

    public String getValueTemplate() {
        return valueTemplate;
    }

    public void setValueTemplate(String valueTemplate) {
        this.valueTemplate = valueTemplate;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordRef() {
        return passwordRef;
    }

    public void setPasswordRef(String passwordRef) {
        this.passwordRef = passwordRef;
    }
}
