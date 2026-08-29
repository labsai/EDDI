/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces.rest;

import ai.labs.eddi.engine.security.spaces.CallerSpaces;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.SpaceContext;
import ai.labs.eddi.engine.security.spaces.Subjects;
import ai.labs.eddi.engine.security.spaces.WorkspaceSettings;
import ai.labs.eddi.engine.security.spaces.rest.model.SpaceInfo;
import ai.labs.eddi.engine.security.spaces.rest.model.WorkspaceInfo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;

/**
 * @author ginccc
 */
@ApplicationScoped
public class RestWorkspaces implements IRestWorkspaces {

    private final SpaceContext spaceContext;
    private final WorkspaceSettings settings;
    private final ResourceAccessGuard accessGuard;

    @Inject
    public RestWorkspaces(SpaceContext spaceContext, WorkspaceSettings settings, ResourceAccessGuard accessGuard) {
        this.spaceContext = spaceContext;
        this.settings = settings;
        this.accessGuard = accessGuard;
    }

    @Override
    public WorkspaceInfo readWorkspaceInfo() {
        CallerSpaces caller = spaceContext.current();

        // Reported even when enforcement is off, so a client can show the same
        // "you are alice" affordances either way. What changes with the flag is
        // whether any of it is *enforced* — which `enabled` says plainly.
        return new WorkspaceInfo(settings.isEnforcing(),
                spaceContext.currentPrincipal(),
                spaceContext.defaultWriteSpace(),
                describe(caller),
                accessGuard.seesEverything());
    }

    /**
     * Renders the caller's spaces in the order {@link CallerSpaces} holds them —
     * personal first, then teams in token order. The label is decoded here rather
     * than by the client, because the encoding is this package's business.
     */
    private List<SpaceInfo> describe(CallerSpaces caller) {
        List<SpaceInfo> spaces = new ArrayList<>(caller.spaces().size());
        for (String id : caller.spaces()) {
            if (id.startsWith(Subjects.USER_PREFIX)) {
                spaces.add(new SpaceInfo(id, SpaceInfo.KIND_PERSONAL,
                        Subjects.decode(id.substring(Subjects.USER_PREFIX.length()))));
            } else if (id.startsWith(Subjects.TEAM_PREFIX)) {
                spaces.add(new SpaceInfo(id, SpaceInfo.KIND_TEAM,
                        Subjects.decode(id.substring(Subjects.TEAM_PREFIX.length()))));
            }
            // Anything else is not a space a client can switch to. Dropping it is
            // deliberate: an id whose shape we do not recognise would render as a
            // filter that silently matches nothing.
        }
        return spaces;
    }
}
