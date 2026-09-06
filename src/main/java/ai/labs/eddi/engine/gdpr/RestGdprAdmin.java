/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Response;
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
public class RestGdprAdmin implements IRestGdprAdmin {

    private static final Logger LOGGER = Logger.getLogger(RestGdprAdmin.class);

    private final GdprComplianceService gdprComplianceService;

    @Inject
    public RestGdprAdmin(GdprComplianceService gdprComplianceService) {
        this.gdprComplianceService = gdprComplianceService;
    }

    /** HTTP 207 Multi-Status, which {@link Response.Status} does not define. */
    static final int MULTI_STATUS = 207;

    /**
     * Answers 207 when any cascade step failed.
     * <p>
     * The cascade deliberately continues past a failing store so the remaining
     * categories are still erased — which used to mean the admin got a 200 and a
     * result whose {@code conversationsDeleted=0} was indistinguishable from "this
     * user had no conversations", and filed the Art. 17 request as fulfilled while
     * the conversations were still there. The status now carries that distinction
     * even for a caller that does not read the body.
     */
    @Override
    public Response deleteUserData(String userId) {
        validateUserId(userId);
        LOGGER.info("GDPR erasure request received");
        GdprDeletionResult result = gdprComplianceService.deleteUserData(userId);
        if (!result.complete()) {
            LOGGER.warnf("GDPR erasure incomplete — failed steps: %s", result.failedSteps());
        }
        return Response.status(result.complete() ? Response.Status.OK.getStatusCode() : MULTI_STATUS)
                .entity(result)
                .build();
    }

    /** HTTP 206 Partial Content, for a bundle the conversation cap truncated. */
    static final int PARTIAL_CONTENT = Response.Status.PARTIAL_CONTENT.getStatusCode();

    /**
     * Answers 206 when the conversation cap truncated the bundle.
     * <p>
     * Same reasoning as the 207 above. The cap used to be visible only in the
     * server log, so a data-portability request for a user with 1,200 conversations
     * returned 200 with 200 of them silently missing — an arbitrary 200 on MongoDB,
     * whose natural order is not insertion order. The body now carries
     * {@code totalConversations} and {@code conversationsTruncated}; the status
     * carries the same distinction for a caller that does not read the body.
     */
    @Override
    public Response exportUserData(String userId) {
        validateUserId(userId);
        LOGGER.info("GDPR export request received");
        UserDataExport export = gdprComplianceService.exportUserData(userId);
        if (export.conversationsTruncated()) {
            LOGGER.warnf("GDPR export truncated — %d of %d conversations returned",
                    export.conversations().size(), export.totalConversations());
        }
        return Response.status(export.conversationsTruncated() ? PARTIAL_CONTENT : Response.Status.OK.getStatusCode())
                .entity(export)
                .build();
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
