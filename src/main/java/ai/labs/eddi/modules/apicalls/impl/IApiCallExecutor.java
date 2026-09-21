/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;

import java.util.Map;

/**
 * Interface for executing HTTP calls configured in EDDI. This abstraction
 * allows reuse of HTTP call execution logic across different tasks (e.g.,
 * ApiCallsTask, Agent tools).
 */
public interface IApiCallExecutor {
    /**
     * Executes a configured HTTP call.
     *
     * @param httpCall
     *            The HTTP call configuration
     * @param memory
     *            The conversation memory for templating and state
     * @param templateDataObjects
     *            The template data objects for variable substitution
     * @param targetServerUrl
     *            The target server URL
     * @return The response object (parsed JSON or raw string)
     * @throws LifecycleException
     *             if execution fails
     */
    Map<String, Object> execute(ApiCall httpCall, IConversationMemory memory, Map<String, Object> templateDataObjects, String targetServerUrl)
            throws LifecycleException;

    /**
     * Resolves what this call <em>would</em> send, without sending it.
     * <p>
     * Exists so a human approving a gated tool call can be shown the actual request
     * — method, target, query, body — rather than the tool's name and the model's
     * raw arguments, and so the approved request can be pinned to a fingerprint
     * that is re-checked immediately before execution.
     * <p>
     * <b>Side-effect free, and deliberately weaker than {@link #execute} because of
     * it.</b> {@code execute} first runs the call's pre-request property
     * instructions, which write to conversation memory; running those here would
     * apply them twice — once to preview a call and again to make it. So they are
     * skipped, and a call that has them cannot be resolved to the same request
     * {@code execute} will build. Such a call comes back with a null
     * {@link ResolvedRequest#fingerprint()}: the preview is still useful, but
     * nothing is pinned and the pre-execution check has nothing to compare. Tools
     * generated from an OpenAPI spec never carry pre-request instructions, so they
     * are always pinned.
     *
     * @return the resolved request with every credential redacted — never the live
     *         header values
     */
    ResolvedRequest resolve(ApiCall httpCall, IConversationMemory memory, Map<String, Object> templateDataObjects, String targetServerUrl)
            throws LifecycleException;
}
