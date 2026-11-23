/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces.directory;

import ai.labs.eddi.engine.org.OrgRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * Notes every authenticated REST caller in the {@link UserDirectory}.
 * <p>
 * A request filter rather than a hook in one endpoint, because the directory is
 * only useful if it holds everybody who uses EDDI — the Manager, the chat UI
 * and API clients alike — and there is no single endpoint they all call. The
 * cost per request is one cache lookup; the write happens off the request
 * thread and only when something about the person changed.
 */
@Provider
public class UserDirectoryRecorder implements ContainerRequestFilter {

    private final UserDirectory directory;
    private final OrgRequest orgRequest;

    @Inject
    public UserDirectoryRecorder(UserDirectory directory, OrgRequest orgRequest) {
        this.directory = directory;
        this.orgRequest = orgRequest;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        // a request filter runs before the resource method's org binding (plan §6.2);
        // UserDirectory carries the binding on to its write executor
        orgRequest.runBound(directory::recordCurrentCaller);
    }
}
