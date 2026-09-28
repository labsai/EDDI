/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces.notifications;

import ai.labs.eddi.engine.gdpr.IGdprParticipant;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Erases and exports workspace notifications that involve a person — both the
 * ones waiting for them and the ones they caused in somebody else's inbox,
 * which name them.
 */
@ApplicationScoped
public class WorkspaceNotificationsGdprParticipant implements IGdprParticipant {

    private final WorkspaceNotifications notifications;

    @Inject
    public WorkspaceNotificationsGdprParticipant(WorkspaceNotifications notifications) {
        this.notifications = notifications;
    }

    @Override
    public String name() {
        return "workspaceNotifications";
    }

    @Override
    public long erase(String userId) {
        return notifications.erase(userId);
    }

    @Override
    public Object export(String userId) {
        List<WorkspaceNotification> held = notifications.export(userId);
        return held.isEmpty() ? null : held;
    }
}
