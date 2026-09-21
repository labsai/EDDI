/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.apicalls.model;

import com.fasterxml.jackson.annotation.JsonAlias;

public class HttpPostResponse extends PostResponse {
    /**
     * Renamed from {@code retryHttpCallInstruction} in the v6 http→api sweep. The
     * alias is not cosmetic: without it Jackson silently discarded the old key, so
     * every stored config written before the rename lost its retry policy without a
     * word in the logs — including EDDI's own reference agent, where the Agent
     * Father's {@code create_agent} call has been running with no retry at all
     * despite declaring {@code maxRetries: 3} on 502/503.
     * <p>
     * Stored and exported JSON configs are the one backward-compatibility contract
     * EDDI keeps, and this is the mechanism that keeps it — the same pattern as
     * {@code @JsonAlias("packages")} on {@code AgentConfiguration.workflows}.
     * Removing it would turn those documents into 400s at the REST boundary now
     * that {@code StrictConfigurationBodyInterceptor} rejects unknown keys.
     */
    @JsonAlias("retryHttpCallInstruction")
    private RetryApiCallInstruction retryApiCallInstruction;

    public RetryApiCallInstruction getRetryApiCallInstruction() {
        return retryApiCallInstruction;
    }

    public void setRetryApiCallInstruction(RetryApiCallInstruction retryApiCallInstruction) {
        this.retryApiCallInstruction = retryApiCallInstruction;
    }
}
