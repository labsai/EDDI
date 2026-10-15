/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

/**
 * A call was refused because a credential reference in its rendered request
 * came from conversation data instead of the configuration — see
 * {@link ConfigReferenceGuard}.
 * <p>
 * A type of its own, still an {@link IllegalArgumentException} so every
 * existing catch keeps working, because the tool-approval gate must tell this
 * refusal apart from any other failure to resolve a request: it is
 * <em>deterministic</em> (the same arguments are refused on every attempt, so a
 * human approving the call can only be approving something that cannot run) and
 * its message is <em>safe to show the model</em> (it names the reference and
 * the field, never a resolved value — a reference is a name, not a secret). A
 * resolve failure of any other kind quotes the material being rendered and must
 * stay generic.
 */
public class ConfigReferenceRefusedException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public ConfigReferenceRefusedException(String message) {
        super(message);
    }
}
