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

    /**
     * Answers 207 whenever the bundle is not everything EDDI holds on the user.
     * <p>
     * Same reasoning as the 207 above, and now the same status. Two things used to
     * be wrong here. The cap was visible only in the server log, so a
     * data-portability request for a user with 1,200 conversations returned 200
     * with 200 of them silently missing — an arbitrary 200 on MongoDB, whose
     * natural order is not insertion order. And the cap was then the <em>only</em>
     * completeness check, while the exporter has never covered group transcripts,
     * shared artifacts, schedules or HITL journal entries: a user whose data lives
     * only in those four categories received an empty bundle, 200 OK, documented as
     * complete — an Art. 20 answer that overstates itself to the one reader who
     * cannot check it. {@link UserDataExport#complete()} answers both questions and
     * the status follows it; {@code omittedCategories} in the body names what is
     * missing.
     * <p>
     * 207, not the 206 this used to send. 206 Partial Content is defined for range
     * requests and RFC 9110 requires a {@code Content-Range} with it, which this
     * endpoint neither reads nor produces — a conforming client is entitled to
     * treat the body as a malformed range response. 207 already means "composite
     * operation, read the body for what actually happened" on the erasure half of
     * this API, which is the same thing being said.
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
        if (!export.complete()) {
            LOGGER.warnf("GDPR export incomplete — conversationsTruncated: %s, omitted categories: %s",
                    export.conversationsTruncated(), export.omittedCategories());
        }
        return Response.status(export.complete() ? Response.Status.OK.getStatusCode() : MULTI_STATUS)
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
