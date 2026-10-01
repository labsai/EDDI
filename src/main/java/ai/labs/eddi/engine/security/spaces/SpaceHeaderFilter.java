/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Refuses a write that names a space, in {@link SpaceContext#SPACE_HEADER}, the
 * caller does not belong to — before anything is created.
 *
 * <h3>Why a request filter and not a check at stamping time</h3> A resource's
 * descriptor is born in {@code DocumentDescriptorFilter}, a <em>response</em>
 * filter, after the store has already written the resource. Refusing there
 * would leave an orphaned resource behind a 403. So the header is checked on
 * the way in, where saying no costs nothing.
 *
 * <h3>Why administrators are not exempt</h3> An administrator sees every space,
 * but filing a new resource in a team they are not part of would put it where
 * they cannot find it again through the space switcher, and the team would find
 * work nobody on it created. They can create it in their own space and move it
 * — or transfer it — deliberately.
 */
@Provider
public class SpaceHeaderFilter implements ContainerRequestFilter {

    private final SpaceContext spaceContext;
    private final WorkspaceSettings settings;

    @Inject
    public SpaceHeaderFilter(SpaceContext spaceContext, WorkspaceSettings settings) {
        this.spaceContext = spaceContext;
        this.settings = settings;
    }

    @Override
    public void filter(ContainerRequestContext request) {
        // Only a POST creates a resource — and so stamps a space. An update carries the
        // resource's existing space forward and ignores the header entirely.
        if (!"POST".equals(request.getMethod())) {
            return;
        }
        String requested = request.getHeaderString(SpaceContext.SPACE_HEADER);
        if (requested == null || requested.isBlank() || !settings.isStampingOwnership()) {
            // Without authentication nothing is stamped, so the header means nothing and
            // refusing it would only break a client that always sends it.
            return;
        }
        String space = requested.trim();
        if (spaceContext.currentPrincipal() == null) {
            request.abortWith(refusal(Response.Status.UNAUTHORIZED, "Creating in a space needs a signed-in caller."));
            return;
        }
        if (!spaceContext.current().spaces().contains(space)) {
            request.abortWith(refusal(Response.Status.FORBIDDEN, "You are not a member of the space '" + sanitize(space)
                    + "'. Pick one of your own spaces (GET /workspaces lists them), or leave " + SpaceContext.SPACE_HEADER
                    + " out to use your default."));
        }
    }

    private static Response refusal(Response.Status status, String message) {
        return Response.status(status).type(MediaType.APPLICATION_JSON).entity(Map.of("error", message)).build();
    }
}
