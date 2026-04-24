/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import org.jboss.logging.Logger;

/**
 * REST implementation for GDPR compliance operations.
 * <p>
 * Protected by {@code eddi-admin} role — only administrators may trigger data
 * deletion or export.
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
@RolesAllowed("eddi-admin")
public class RestGdprAdmin implements IRestGdprAdmin {

    private static final Logger LOGGER = Logger.getLogger(RestGdprAdmin.class);

    private final GdprComplianceService gdprComplianceService;

    @Inject
    public RestGdprAdmin(GdprComplianceService gdprComplianceService) {
        this.gdprComplianceService = gdprComplianceService;
    }

    @Override
    public GdprDeletionResult deleteUserData(String userId) {
        validateUserId(userId);
        LOGGER.info("GDPR erasure request received");
        return gdprComplianceService.deleteUserData(userId);
    }

    @Override
    public UserDataExport exportUserData(String userId) {
        validateUserId(userId);
        LOGGER.info("GDPR export request received");
        return gdprComplianceService.exportUserData(userId);
    }

    @Override
    public void restrictProcessing(String userId) {
        validateUserId(userId);
        LOGGER.info("GDPR processing restriction request received");
        gdprComplianceService.restrictProcessing(userId);
    }

    @Override
    public void unrestrictProcessing(String userId) {
        validateUserId(userId);
        LOGGER.info("GDPR processing unrestriction request received");
        gdprComplianceService.unrestrictProcessing(userId);
    }

    @Override
    public boolean isProcessingRestricted(String userId) {
        validateUserId(userId);
        return gdprComplianceService.isProcessingRestricted(userId);
    }

    private static void validateUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new BadRequestException("userId must not be blank");
        }
    }
}
