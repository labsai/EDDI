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
 * Marks a tool method whose answer depends on when it is called, so no result
 * of it may be served from, or stored in, the tool result cache — the current
 * time, for instance, which an entry would have kept "current" for its whole
 * TTL.
 * <p>
 * Read by
 * {@link ToolCacheService#isConversationBound(dev.langchain4j.service.tool.ToolExecutor)}
 * next to the class-level rules, so the dispatch loop switches caching off for
 * the call exactly as it does for a conversation-bound tool.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface NeverCached {
}
