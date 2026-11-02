/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a tool class whose instances act for one conversation even though the
 * class itself is a CDI bean — the bean is a template, and production calls
 * copies bound to a conversation (see
 * {@code FetchToolResponsePageTool#boundTo}).
 * <p>
 * {@link ToolCacheService#isConversationBound(Class)} otherwise infers "shared
 * by every conversation, safe to cache" from a normal scope, and a cache hit
 * then returned a result without the bound copy ever checking that it belonged
 * to the asking conversation. Declaring it explicitly keeps such tools out of
 * the general result cache.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ConversationBound {
}
