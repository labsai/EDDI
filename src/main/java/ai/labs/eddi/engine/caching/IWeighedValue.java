/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.caching;

/**
 * A cache value that knows roughly how much memory it holds, in characters. A
 * cache {@link CacheFactory} bounds by weight rather than by entry count
 * charges each entry this plus the length of its key.
 */
public interface IWeighedValue {

    /** Characters this value holds. */
    int weight();
}
