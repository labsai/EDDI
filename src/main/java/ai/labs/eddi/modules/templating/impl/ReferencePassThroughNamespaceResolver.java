/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating.impl;

import io.quarkus.qute.EvalContext;
import io.quarkus.qute.NamespaceResolver;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Lets a {@code ${namespace:...}} reference survive Qute templating unchanged,
 * so the resolver that owns it can handle it afterwards.
 * <p>
 * Apicall values — URL, headers, body, query parameters — are templated
 * <em>before</em> the global-variable, vault, caller-identity and connection
 * resolvers run ({@code ApiCallExecutor#buildRequest}). Qute parses
 * {@code {vault:key}} as a namespaced expression, and an unresolvable namespace
 * is a hard rendering failure regardless of {@code strictRendering} — so
 * without a pass-through every such reference failed the whole call with "No
 * namespace resolver found for [vault]" and never reached its resolver.
 * <p>
 * A subclass emits the placeholder back verbatim. It resolves <b>nothing</b>
 * itself: the value it returns is the literal {@code {namespace:name}}, never a
 * secret, and the {@code $} in front of it is literal template text Qute has
 * already emitted.
 * <p>
 * Letting a reference through templating is only safe together with the guard
 * in {@code ApiCallExecutor}: a reference is resolved where the agent
 * configuration wrote it, never where conversation data put it (see
 * {@code ConfigReferenceGuard}). And the executor redacts the plaintexts it
 * substituted from everything it records about the request.
 */
public abstract class ReferencePassThroughNamespaceResolver implements NamespaceResolver {

    private final String namespace;

    protected ReferencePassThroughNamespaceResolver(String namespace) {
        this.namespace = namespace;
    }

    @Override
    public String getNamespace() {
        return namespace;
    }

    @Override
    public CompletionStage<Object> resolve(EvalContext context) {
        return CompletableFuture.completedFuture("{" + namespace + ":" + context.getName() + "}");
    }
}
