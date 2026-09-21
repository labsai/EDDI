/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tenancy;

import java.util.concurrent.RejectedExecutionException;

/**
 * Thrown when the quota store could not answer — the request is refused for
 * safety, not because any limit was reached.
 * <p>
 * Both outcomes fail closed, but they are different events and used to be
 * indistinguishable: a {@code SQLException} inside
 * {@code PostgresTenantQuotaStore} came back as an ordinary denial, so the
 * caller received {@code 429 quota_exceeded} with {@code Retry-After: 60} and
 * {@code eddi.tenant.quota.denied} spiked as if the tenant had burned through
 * its allowance. Only the reason string differed, which no client and no
 * dashboard reads.
 * <p>
 * Extends {@link RejectedExecutionException} deliberately: every surface that
 * already answers 503 for backpressure — the
 * {@code RejectedExecutionExceptionMapper}, the explicit
 * {@code RestAgentEngine.sayInternal} branch that exists because an
 * {@code AsyncResponse} never reaches a mapper — therefore answers 503 for this
 * too, rather than 500, without having to enumerate it. The surfaces that
 * distinguish it further do so by catching this type first, and
 * {@link ai.labs.eddi.engine.tenancy.rest.QuotaAccountingUnavailableExceptionMapper}
 * gives the synchronous endpoints the honest error code that the
 * {@code RejectedExecutionException} mapper cannot.
 * <p>
 * It implements {@link QuotaRefusal} because it is still the quota layer saying
 * no: callers that abort on a quota refusal rather than degrade — the
 * group-conversation engines — must treat it exactly as they treat
 * {@link QuotaExceededException}.
 * <p>
 * The stores also raise it from {@code getQuota}, which cannot express the
 * outage in its return value: {@code null} there already means "this tenant has
 * no quota configured", which {@code TenantQuotaService} treats as unlimited.
 * {@link TenantQuotaService} catches it at every gate and converts it back into
 * a {@code QuotaCheckResult} flagged {@code accountingUnavailable}, so the read
 * half and the write half of an outage are counted and answered identically.
 *
 * @author ginccc
 * @since 6.0.0
 */
public class QuotaAccountingUnavailableException extends RejectedExecutionException implements QuotaRefusal {

    public QuotaAccountingUnavailableException(String message) {
        super(message);
    }

    /**
     * Keeps the driver exception attached. The stores raise this in place of the
     * {@code SQLException} or {@code MongoException} they caught, and a schema or
     * connection failure is not diagnosable without the original stack.
     */
    public QuotaAccountingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public String refusalSummary() {
        return "Tenant quota accounting unavailable";
    }
}
