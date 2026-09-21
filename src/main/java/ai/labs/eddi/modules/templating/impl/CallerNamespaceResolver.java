/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating.impl;

import io.quarkus.qute.EvalContext;
import io.quarkus.qute.NamespaceResolver;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Lets {@code ${caller:...}} references survive Qute templating unchanged.
 * <p>
 * Apicall header values are templated <em>before</em> the caller-identity,
 * global-variable and vault resolvers run
 * ({@code ApiCallExecutor#buildRequest}). Qute parses {@code {caller:token}} as
 * a namespaced expression, and an unresolvable namespace is a hard rendering
 * failure regardless of {@code strictRendering} — so without this resolver a
 * header of {@code "Bearer ${caller:token}"} fails the whole call with "No
 * namespace resolver found for [caller]" and the reference never reaches
 * {@code CallerIdentityResolver}.
 * <p>
 * This resolver emits the placeholder back verbatim so it round-trips through
 * templating to the real resolver. It resolves <b>nothing</b> itself: the value
 * it returns is the literal {@code {caller:token}}, never a token.
 *
 * <h2>Why only {@code caller}</h2> {@code ${vault:...}} and
 * {@code ${eddivault:...}} deliberately do <b>not</b> get the same treatment,
 * even though they fail identically today. Making them survive templating would
 * widen where a vault secret can be substituted — and the resolved request
 * <em>body</em> is written into conversation memory ({@code ApiCallExecutor}
 * scrubs headers, not bodies), so a vault reference in a body template would
 * put a plaintext API key into MongoDB. Keeping vault references failing loudly
 * in templated positions is the safer behaviour; use a vault reference where it
 * is resolved without templating (LLM credentials, for example).
 *
 * @author ginccc
 * @since 6.2.0
 */
@ApplicationScoped
public class CallerNamespaceResolver implements NamespaceResolver {

    public static final String NAMESPACE = "caller";

    @Override
    public String getNamespace() {
        return NAMESPACE;
    }

    @Override
    public CompletionStage<Object> resolve(EvalContext context) {
        // Rebuild the expression without the leading '$' — that character is
        // literal text in the template and is emitted by Qute already.
        return CompletableFuture.completedFuture("{" + NAMESPACE + ":" + context.getName() + "}");
    }
}
